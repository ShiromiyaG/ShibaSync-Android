package com.shirou.shibasync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.PendingIntent
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioFocusRequest
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import android.util.Base64
import android.Manifest
import android.content.pm.PackageManager
import android.annotation.SuppressLint
import android.graphics.BitmapFactory
import androidx.core.app.NotificationCompat
import androidx.lifecycle.MutableLiveData
import androidx.media.app.NotificationCompat as MediaNotificationCompat
import io.socket.client.IO
import io.socket.client.Socket
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

enum class ConnectionState {
    IDLE, CONNECTING, CONNECTED, DISCONNECTED, FAILED
}

@SuppressLint("MissingPermission")
class AudioPlaybackService : Service(), AudioManager.OnAudioFocusChangeListener {
    
    // Binder
    private val binder = AudioPlaybackBinder()
    
    // Oboe player
    private var oboePlayer: OboeAudioPlayer? = null
    
    // Socket
    private var socket: Socket? = null
    
    // LiveData
    val connectionState = MutableLiveData(ConnectionState.IDLE)
    val isPlayingLive = MutableLiveData(false)
    val activeSenderCount = MutableLiveData(0)
    val activeListenerCount = MutableLiveData(0)
    
    // Estado
    private val isPlaying = AtomicBoolean(false)
    private val chunksReceived = AtomicLong(0)
    
    // Monitorar taxa de chegada de chunks
    private var lastChunkTime = System.currentTimeMillis()
    private var chunkIntervals = mutableListOf<Long>()

    // === Rate limiter para processamento de chunks (throttling)
    private var lastChunkProcessTime = 0L
    private val minChunkInterval = 8L // 8ms = 125 chunks/s (margem para picos de latência)
    private val chunkChannel = kotlinx.coroutines.channels.Channel<ByteArray>(capacity = 500) // Canal thread-safe
    private var processingJob: Job? = null
    
    // Configurações de áudio
    private val SAMPLE_RATE = 48000
    private val CHANNEL_COUNT = 2
    
    // Media Session e Audio
    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var audioManager: AudioManager
    private lateinit var notificationManager: NotificationManager
    private var audioFocusRequest: AudioFocusRequest? = null
    
    // Coroutines
    private val audioDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val serviceScope = CoroutineScope(audioDispatcher + SupervisorJob())
    
    companion object {
        const val CHANNEL_ID = "AudioPlaybackServiceChannel"
        const val NOTIFICATION_ID = 101
        private const val TAG = "AudioPlayback"
    }
    
    inner class AudioPlaybackBinder : Binder() {
        fun getService(): AudioPlaybackService = this@AudioPlaybackService
    }
    
    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AudioManager::class.java)
        notificationManager = getSystemService(NotificationManager::class.java)
        createNotificationChannel()
        mediaSession = MediaSessionCompat(this, "AudioPlaybackService").apply {
            setCallback(mediaSessionCallback)
        }
    }
    
    override fun onBind(intent: Intent): IBinder = binder
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (canPostNotifications()) {
            startForeground(NOTIFICATION_ID, buildNotification(isPlaying.get()))
        }
        return START_NOT_STICKY
    }
    
    fun connectToServer(serverIp: String) {
        if (connectionState.value == ConnectionState.CONNECTING || 
            connectionState.value == ConnectionState.CONNECTED) {
            Log.d(TAG, "⚠️ Já conectado ou conectando, ignorando nova tentativa")
            return
        }
            
        connectionState.postValue(ConnectionState.CONNECTING)
        
        try {
            val serverUrl = if (serverIp.startsWith("http")) serverIp else "http://$serverIp:3000"
            val opts = IO.Options().apply {
                reconnection = true
                timeout = 20000
            }
            
            socket = IO.socket(serverUrl, opts)
            activeSenderCount.postValue(0)
            activeListenerCount.postValue(0)
            socket?.on("stream-stats") { args ->
                handleStreamStats(args.firstOrNull())
            }
            socket?.on(Socket.EVENT_CONNECT) { onSocketConnected() }
            socket?.on(Socket.EVENT_DISCONNECT) { 
                connectionState.postValue(ConnectionState.DISCONNECTED)
                activeSenderCount.postValue(0)
                activeListenerCount.postValue(0)
                stopPlayback()
            }
            socket?.on(Socket.EVENT_CONNECT_ERROR) { 
                connectionState.postValue(ConnectionState.FAILED)
                activeSenderCount.postValue(0)
                activeListenerCount.postValue(0)
            }
            socket?.connect()
            
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao conectar socket", e)
            connectionState.postValue(ConnectionState.FAILED)
        }
    }

    // Inicia o processador de chunks com rate limiting
    private fun handleStreamStats(payload: Any?) {
        val counts = parseStreamStatsPayload(payload) ?: return
        activeSenderCount.postValue(counts.first)
        activeListenerCount.postValue(counts.second)
    }

    private fun parseStreamStatsPayload(payload: Any?): Pair<Int, Int>? {
        return when (payload) {
            is JSONObject -> Pair(payload.optInt("senders", 0), payload.optInt("listeners", 0))
            is Map<*, *> -> {
                val senders = (payload["senders"] as? Number)?.toInt() ?: 0
                val listeners = (payload["listeners"] as? Number)?.toInt() ?: 0
                Pair(senders, listeners)
            }
            is String -> runCatching { JSONObject(payload) }.getOrNull()?.let { json ->
                Pair(json.optInt("senders", 0), json.optInt("listeners", 0))
            }
            else -> null
        }
    }

    private fun startChunkProcessor() {
        processingJob?.cancel()
        processingJob = serviceScope.launch(audioDispatcher) {
            // Processamento de chunks com prioridade
            // Aguardar acumular chunks suficientes antes de começar (pre-buffering)
            var preBufferCount = 0
            val TARGET_PREBUFFER_CHUNKS = 50  // Aumentar de 20 para 50 (~500-1000ms)

            while (isActive && isPlaying.get() && preBufferCount < TARGET_PREBUFFER_CHUNKS) {
                try {
                    withTimeout(50) {  // Aumentar timeout de 10ms para 50ms
                        chunkChannel.receive()
                        preBufferCount++
                    }
                } catch (e: Exception) {
                    delay(10)
                }
            }
            
            if (preBufferCount >= 20) {
                Log.d(TAG, "✅ Pre-buffer completo: $preBufferCount chunks (~${preBufferCount * 10}ms)")
            }
            
            // Loop principal: consumir do canal e processar SEM rate limiting artificial
            while (isActive && isPlaying.get()) {
                try {
                    val chunk = withTimeout(100) {
                        chunkChannel.receive()
                    }
                    
                    // REMOVER o rate limiting artificial
                    // val timeSinceLastChunk = now - lastChunkProcessTime
                    // if (timeSinceLastChunk < minChunkInterval) { delay(...) }
                    
                    // Enviar IMEDIATAMENTE para Oboe
                    val success = oboePlayer?.addData(chunk) ?: false
                    
                    if (success) {
                        chunksReceived.incrementAndGet()
                        lastChunkProcessTime = System.currentTimeMillis()
                        
                        // Log a cada 100 chunks
                        if (chunksReceived.get() % 100 == 0L) {
                            val queueSize = chunkChannel.isEmpty.let { if (it) 0 else "??" }
                            Log.d(TAG, "📊 Chunk #${chunksReceived.get()} | Canal: $queueSize | Processado imediatamente")
                        }
                    } else {
                        delay(1) // Apenas se Oboe recusar
                    }
                    
                } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                    continue
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Erro no processador: ${e.message}")
                }
            }
        }
    }

    private fun onSocketConnected() {
        connectionState.postValue(ConnectionState.CONNECTED)
        socket?.emit("join-stream")
        socket?.emit("request-stats")
        
        Log.d(TAG, "🔌 Socket conectado, criando Oboe stream...")
        
        // Criar Oboe player
        oboePlayer = OboeAudioPlayer()
        if (!oboePlayer!!.createStream(SAMPLE_RATE, CHANNEL_COUNT)) {
            Log.e(TAG, "❌ Falha ao criar Oboe stream")
            connectionState.postValue(ConnectionState.FAILED)
            return
        }
        
        // Iniciar playback
        oboePlayer?.start()
        isPlaying.set(true)
        isPlayingLive.postValue(true)
        
        // ✅ INICIAR PROCESSADOR DE CHUNKS COM RATE LIMITING
        startChunkProcessor()
        
        // Receber chunks e enviar para o canal (producer)
        socket?.on("audio-chunk") { args ->
            try {
                val rawData = processAudioData(args[0]) ?: return@on
                val validData = validateAndProcessAudioData(rawData) ?: return@on
                
                // Usar offer() em vez de trySend() para backpressure adequado
                val result = chunkChannel.trySend(validData)
                
                if (result.isFailure) {
                    // Logar mas não descartar - Socket.IO vai fazer backpressure
                    Log.w(TAG, "⚠️ Canal saturado, aplicando backpressure")
                    // Socket.IO vai desacelerar sender automaticamente
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Erro ao processar chunk", e)
            }
        }
        
        socket?.on("error") { args ->
            Log.e(TAG, "❌ Socket error: ${args.firstOrNull()}")
        }
        
        socket?.on("stream-started") {
            Log.d(TAG, "✅ Stream iniciado no servidor")
        }
        
        socket?.on("stream-stopped") {
            Log.w(TAG, "⚠️ Stream parado no servidor")
        }
        
        // 🔍 MONITORAR SE CHUNKS PARAM DE CHEGAR (Timeout)
        serviceScope.launch {
            delay(15000) // Esperar 15 segundos após conectar
            
            while (isPlaying.get() && connectionState.value == ConnectionState.CONNECTED) {
                val lastChunkAge = System.currentTimeMillis() - lastChunkTime
                
                if (lastChunkAge > 5000 && chunksReceived.get() > 0) {
                    Log.e(TAG, "🔴 SEM CHUNKS HÁ ${lastChunkAge/1000}s! Sender pode ter parado.")
                    Log.e(TAG, "   Total recebido: ${chunksReceived.get()}, Buffer: ${oboePlayer?.getBufferSize() ?: 0}")
                    Log.e(TAG, "   Socket conectado: ${socket?.connected()}")
                }
                
                delay(5000) // Verificar a cada 5 segundos
            }
        }
        
        requestAudioFocus()
        updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
        mediaSession.isActive = true
        
        if (canPostNotifications()) {
            notificationManager.notify(NOTIFICATION_ID, buildNotification(true))
        }
    }
    
    private fun processAudioData(data: Any?): ByteArray? {
        return try {
            when (data) {
                is ByteArray -> data
                
                is String -> {
                    // ✅ REMOVER PREFIXO "AUDIO_DATA:" SE EXISTIR (do C#)
                    val cleanData = if (data.startsWith("AUDIO_DATA:")) {
                        data.substring("AUDIO_DATA:".length)
                    } else {
                        data
                    }
                    
                    if (cleanData.startsWith("[") || cleanData.startsWith("{")) {
                        processJsonString(cleanData)
                    } else {
                        Base64.decode(cleanData, Base64.DEFAULT)
                    }
                }
                
                is JSONArray -> {
                    val length = data.length()
                    val byteArray = ByteArray(length)
                    
                    for (i in 0 until length) {
                        byteArray[i] = when (val item = data.get(i)) {
                            is Int -> item.toByte()
                            is Double -> item.toInt().toByte()
                            is Float -> item.toInt().toByte()
                            is Long -> item.toByte()
                            else -> 0
                        }
                    }
                    
                    byteArray
                }
                
                is JSONObject -> {
                    // ✅ SUPORTE PARA MÚLTIPLOS FORMATOS
                    when {
                        // Formato do C# com Base64: { type: "audio-chunk", data: "base64..." }
                        data.has("data") && data.has("type") && data.getString("type") == "audio-chunk" -> {
                            val dataField = data.get("data")
                            if (dataField is String) {
                                // Base64 string
                                Base64.decode(dataField, Base64.DEFAULT)
                            } else {
                                // É um JSONArray ou outro tipo
                                processAudioData(dataField)
                            }
                        }
                        // Formato do Electron: { chunk: N, timestamp: T, data: [1,2,3,...], size: N }
                        data.has("data") && data.has("chunk") && data.has("timestamp") -> {
                            val dataField = data.get("data")
                            if (dataField is JSONArray) {
                                // Converter JSONArray para ByteArray
                                val length = dataField.length()
                                val byteArray = ByteArray(length)
                                for (i in 0 until length) {
                                    byteArray[i] = when (val item = dataField.get(i)) {
                                        is Int -> item.toByte()
                                        is Double -> item.toInt().toByte()
                                        is Float -> item.toInt().toByte()
                                        is Long -> item.toByte()
                                        else -> 0
                                    }
                                }
                                byteArray
                            } else {
                                processAudioData(dataField)
                            }
                        }
                        // Formato genérico com campo 'data'
                        data.has("data") -> processAudioData(data.get("data"))
                        data.has("audio") -> processAudioData(data.get("audio"))
                        data.has("buffer") -> processAudioData(data.get("buffer"))
                        else -> {
                            Log.w(TAG, "⚠️ JSONObject sem campo conhecido: ${data.keys().asSequence().joinToString()}")
                            null
                        }
                    }
                }
                
                is List<*> -> {
                    ByteArray(data.size) { i ->
                        when (val value = data[i]) {
                            is Number -> value.toByte()
                            else -> 0
                        }
                    }
                }
                
                else -> null
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Erro processando áudio: ${e.message}")
            null
        }
    }
    
    private fun processJsonString(jsonString: String): ByteArray? {
        return try {
            when {
                jsonString.startsWith("[") -> {
                    val jsonArray = JSONArray(jsonString)
                    processAudioData(jsonArray)
                }
                jsonString.startsWith("{") -> {
                    val jsonObject = JSONObject(jsonString)
                    
                    // ✅ LOG DETALHADO DO FORMATO C#
                    if (chunksReceived.get() < 3) {
                        Log.d(TAG, "📋 JSON do C#:")
                        if (jsonObject.has("type")) Log.d(TAG, "  type: ${jsonObject.getString("type")}")
                        if (jsonObject.has("chunkNumber")) Log.d(TAG, "  chunk#: ${jsonObject.getInt("chunkNumber")}")
                        if (jsonObject.has("dataSize")) Log.d(TAG, "  dataSize: ${jsonObject.getInt("dataSize")}")
                        if (jsonObject.has("frames")) Log.d(TAG, "  frames: ${jsonObject.getInt("frames")}")
                        if (jsonObject.has("format")) {
                            val format = jsonObject.getJSONObject("format")
                            Log.d(TAG, "  format: ${format.getInt("SampleRate")}Hz, ${format.getInt("Channels")}ch, ${format.getInt("BitsPerSample")}bit")
                        }
                    }
                    
                    processAudioData(jsonObject)
                }
                else -> null
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Erro processando JSON string", e)
            null
        }
    }
    
    // ✅ VALIDAÇÃO ADAPTADA PARA MÚLTIPLOS TAMANHOS DE CHUNK
    private fun validateAndProcessAudioData(rawData: ByteArray): ByteArray? {
        // Tamanhos esperados (48kHz, 2ch, PCM16):
        // - 20ms: 960 frames × 2 ch × 2 bytes = 3840 bytes (C# sender)
        // - 10ms: 480 frames × 2 ch × 2 bytes = 1920 bytes (Electron sender)
        // - Android: varia conforme AudioRecord buffer
        
        val validSizes = listOf(1920, 3840) // 10ms e 20ms
        val isValidSize = validSizes.any { expected ->
            rawData.size >= (expected * 0.9) && rawData.size <= (expected * 1.1)
        }
        
        if (!isValidSize && rawData.size > 4000) {
            // Só logar se for muito diferente dos tamanhos esperados
            Log.w(TAG, "⚠️ Chunk size não padrão: ${rawData.size} bytes (esperado: 1920 ou 3840)")
        }
        
        // ✅ VERIFICAR SE OS DADOS SÃO PCM16 VÁLIDOS (Little Endian como C# envia)
        var hasValidAudio = false
        var maxValue = 0
        var silentSamples = 0
        val samplesToCheck = minOf(100, (rawData.size / 2))
        
        for (i in 0 until samplesToCheck) {
            val byteIdx = i * 2
            if (byteIdx + 1 >= rawData.size) break
            
            // ✅ Ler sample PCM16 Little Endian (mesmo formato do C#)
            val low = rawData[byteIdx].toInt() and 0xFF
            val high = rawData[byteIdx + 1].toInt() and 0xFF
            val sample = low or (high shl 8)
            val signedSample = if (sample > 32767) sample - 65536 else sample
            
            val absValue = kotlin.math.abs(signedSample)
            
            if (absValue > 100) {
                hasValidAudio = true
            }
            if (absValue < 10) {
                silentSamples++
            }
            
            maxValue = maxOf(maxValue, absValue)
        }
        
        // ✅ LOG DETALHADO NOS PRIMEIROS CHUNKS
        if (chunksReceived.get() < 5) {
            Log.d(TAG, "🔍 Validação chunk #${chunksReceived.get()}: " +
                      "size=${rawData.size}, " +
                      "max=$maxValue, " +
                      "valid=$hasValidAudio, " +
                      "silent=$silentSamples/$samplesToCheck")
        }
        
        if (!hasValidAudio && maxValue < 50) {
            Log.w(TAG, "⚠️ Chunk quase silencioso (max: $maxValue, silent: $silentSamples/$samplesToCheck)")
        }
        
        return rawData
    }
    
    private fun pausePlayback() {
        if (!isPlaying.get()) return
        
        Log.d(TAG, "⏸️ Pausando playback...")
        
        oboePlayer?.pause()
        isPlaying.set(false)
        isPlayingLive.postValue(false)
        
        updatePlaybackState(PlaybackStateCompat.STATE_PAUSED)
        
        if (canPostNotifications()) {
            notificationManager.notify(NOTIFICATION_ID, buildNotification(false))
        }
    }
    
    private fun resumePlayback() {
        if (isPlaying.get()) return
        
        Log.d(TAG, "▶️ Retomando playback...")
        
        oboePlayer?.start()
        isPlaying.set(true)
        isPlayingLive.postValue(true)
        
        updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
        
        if (canPostNotifications()) {
            notificationManager.notify(NOTIFICATION_ID, buildNotification(true))
        }
    }
    
    fun togglePlayback() {
        if (isPlaying.get()) {
            pausePlayback()
        } else {
            resumePlayback()
        }
    }
    
    fun disconnect() {
        Log.d(TAG, "🔌 Desconectando socket e limpando listeners...")

        // Parar processador de chunks
        processingJob?.cancel()
        processingJob = null

        // Limpar canal de chunks
        chunkChannel.close()
        // Drenar qualquer chunk restante
        serviceScope.launch {
            while (!chunkChannel.isEmpty) {
                try { chunkChannel.receive() } catch (e: Exception) { break }
            }
        }

        // Pausar o playback
        if (isPlaying.get()) {
            pausePlayback()
        }
        
        // Remover todos os listeners do socket para evitar eventos fantasmas
        socket?.let { s ->
            s.off(Socket.EVENT_CONNECT)
            s.off(Socket.EVENT_DISCONNECT)
            s.off(Socket.EVENT_CONNECT_ERROR)
            s.off("audio-chunk")
            s.off("error")
            s.off("stream-started")
            s.off("stream-stopped")
            s.off("stream-stats")
            
            // Desconectar o socket
            if (s.connected()) {
                s.disconnect()
            }
        }
        socket = null
        
        // Limpar o Oboe player mas não destruir completamente
        oboePlayer?.stop()
        oboePlayer?.destroy()
        oboePlayer = null
        
        // Resetar contadores
        chunksReceived.set(0)
        chunkIntervals.clear()
        activeSenderCount.postValue(0)
        activeListenerCount.postValue(0)
        
        // Atualizar estado
        connectionState.postValue(ConnectionState.IDLE)
        
        Log.d(TAG, "✅ Desconexão completa")
    }
    
    fun stopPlayback() {
        Log.d(TAG, "🛑 Parando playback...")
        
        // Usar disconnect para limpar tudo
        disconnect()
        
        mediaSession.isActive = false
        
        stopForeground(true)
        stopSelf()
    }
    
    fun setVolume(volume: Float) {
        val success = oboePlayer?.setVolume(volume) ?: false
        if (success) {
            Log.d(TAG, "🔊 Volume ajustado para: ${(volume * 100).toInt()}%")
        } else {
            Log.w(TAG, "⚠️ Falha ao ajustar volume")
        }
    }
    
    fun getStreamInfo(): String {
        val bufferSize = oboePlayer?.getBufferSize() ?: 0
        val underruns = oboePlayer?.getUnderrunCount() ?: 0
        val latency = oboePlayer?.getLatencyMillis() ?: 0
        
        return """
        Stream Info:
        - Buffer Size: $bufferSize chunks
        - Underrun Count: $underruns
        - Latency: ${latency}ms
        - Chunks Received: ${chunksReceived.get()}
        """.trimIndent()
    }
    
    private fun requestAudioFocus() {
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setOnAudioFocusChangeListener(this)
                .build()
            audioFocusRequest = request
            audioManager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                this,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
        }
        
        Log.d(TAG, "Audio focus request result: $result")
    }
    
    override fun onAudioFocusChange(focusChange: Int) {
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                Log.d(TAG, "Audio focus gained")
                if (!isPlaying.get() && oboePlayer != null) {
                    resumePlayback()
                }
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                Log.w(TAG, "Audio focus lost")
                pausePlayback()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                Log.w(TAG, "Audio focus lost transient")
                pausePlayback()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                Log.d(TAG, "Audio focus duck")
                // Poderia reduzir volume aqui se implementado
            }
        }
    }
    
    private val mediaSessionCallback = object : MediaSessionCompat.Callback() {
        override fun onPlay() {
            resumePlayback()
        }
        
        override fun onPause() {
            pausePlayback()
        }
        
        override fun onStop() {
            stopPlayback()
        }
    }
    
    private fun updatePlaybackState(state: Int) {
        val playbackState = PlaybackStateCompat.Builder()
            .setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1.0f)
            .setActions(
                PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_STOP
            )
            .build()
        mediaSession.setPlaybackState(playbackState)
    }
    
    private fun canPostNotifications(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == 
                PackageManager.PERMISSION_GRANTED
        } else true
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Reprodução de Áudio",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Serviço para reprodução de áudio em tempo real"
                setSound(null, null)
                enableVibration(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    private fun buildNotification(isPlaying: Boolean): Notification {
        val mediaMetadata = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, "Shiba Sync Stream")
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "Reproduzindo áudio ao vivo")
            .build()
        mediaSession.setMetadata(mediaMetadata)
        
        val playPauseAction = if (isPlaying) {
            NotificationCompat.Action(
                android.R.drawable.ic_media_pause,
                "Pausar",
                androidx.media.session.MediaButtonReceiver.buildMediaButtonPendingIntent(
                    this,
                    PlaybackStateCompat.ACTION_PAUSE
                )
            )
        } else {
            NotificationCompat.Action(
                android.R.drawable.ic_media_play,
                "Reproduzir",
                androidx.media.session.MediaButtonReceiver.buildMediaButtonPendingIntent(
                    this,
                    PlaybackStateCompat.ACTION_PLAY
                )
            )
        }
        
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        // 🎨 Carregar a imagem do Shiba em MÁXIMA resolução para a notificação
        // Imagem original: 3386x1428 pixels (ultra-wide 2.37:1)
        val artworkBitmap = try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true // Primeiro, apenas ler dimensões
            }
            BitmapFactory.decodeResource(resources, R.drawable.shiba_notification_final, options)
            
            val srcW = options.outWidth
            val srcH = options.outHeight
            
            Log.d(TAG, "🖼️ Imagem original: ${srcW}x${srcH}px")
            
            // Para large icon de notificação: 
            // - Android recomenda até 512px para large icon compacta
            // - Mas podemos usar até 1024px para melhor qualidade em telas HD
            val TARGET_HEIGHT = 768 // Altura alvo para qualidade HD (mantém proporção)
            
            // Calcular inSampleSize para reduzir memória mantendo qualidade
            var inSampleSize = 1
            if (srcH > TARGET_HEIGHT) {
                val halfHeight = srcH / 2
                while (halfHeight / inSampleSize >= TARGET_HEIGHT) {
                    inSampleSize *= 2
                }
            }
            
            // Agora carregar o bitmap com qualidade máxima
            val loadOptions = BitmapFactory.Options().apply {
                inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888 // Máxima qualidade de cor
                inSampleSize = inSampleSize // Otimiza memória mantendo qualidade
                inScaled = false // Não aplicar densidade de tela
                inDither = false // Sem dithering (mantém cores puras)
                inPreferQualityOverSpeed = true // Priorizar qualidade
            }
            
            val originalBitmap = BitmapFactory.decodeResource(
                resources, 
                R.drawable.shiba_notification_final, 
                loadOptions
            )
            
            if (originalBitmap != null) {
                val loadedW = originalBitmap.width
                val loadedH = originalBitmap.height
                
                // Calcular dimensões finais mantendo aspect ratio
                val scale = TARGET_HEIGHT.toFloat() / loadedH.toFloat()
                val targetW = (loadedW * scale).toInt()
                val targetH = TARGET_HEIGHT
                
                Log.d(TAG, "🎨 Escalonando de ${loadedW}x${loadedH} para ${targetW}x${targetH}px (ratio: ${String.format("%.2f", targetW.toFloat()/targetH)}:1)")
                
                // Usar createScaledBitmap com filtro de alta qualidade
                val scaledBitmap = android.graphics.Bitmap.createScaledBitmap(
                    originalBitmap, 
                    targetW, 
                    targetH, 
                    true // filtro de alta qualidade (bilinear)
                )
                
                // Liberar bitmap original se for diferente do escalado
                if (scaledBitmap != originalBitmap) {
                    originalBitmap.recycle()
                }
                
                scaledBitmap
            } else {
                Log.e(TAG, "❌ Falha ao carregar bitmap")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Erro ao processar imagem: ${e.message}", e)
            null
        }
        
        // 🎵 Notificação estilo player de música com MediaStyle
        val mediaStyle = MediaNotificationCompat.MediaStyle()
            .setMediaSession(mediaSession.sessionToken)
            .setShowActionsInCompactView(0) // Mostrar botão play/pause na notificação compacta

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Shiba Sync Stream")
            .setContentText("Reproduzindo áudio ao vivo")
            .setSmallIcon(R.drawable.shiba_notification_final)
            .setContentIntent(contentIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setStyle(mediaStyle) // ✅ MediaStyle para controles de música
            .addAction(playPauseAction)

        // Se tivermos artwork, adicionar como large icon (aparece ao lado do título)
        if (artworkBitmap != null) {
            builder.setLargeIcon(artworkBitmap)
        }

        return builder.build()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        stopPlayback()
        serviceScope.cancel()
        mediaSession.release()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(this)
        }
    }
}
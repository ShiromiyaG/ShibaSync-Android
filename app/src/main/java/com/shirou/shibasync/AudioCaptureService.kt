package com.shirou.shibasync

import android.annotation.SuppressLint
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresApi
import io.socket.client.Socket
import kotlinx.coroutines.*

@RequiresApi(Build.VERSION_CODES.Q)
class AudioCaptureService : MediaProjectionService() {

    private val binder = AudioCaptureBinder()
    private var audioRecord: AudioRecord? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var mediaProjection: MediaProjection? = null
    private var captureJob: Job? = null
    private var isPrepared = false // Nova flag de estado

    inner class AudioCaptureBinder : Binder() {
        fun getService(): AudioCaptureService = this@AudioCaptureService
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A única responsabilidade agora é garantir que o serviço rode em primeiro plano.
        startForeground(NOTIFICATION_ID, createNotification())
        try {
            // If an explicit stop-and-release was requested via startService with ACTION_STOP_AND_RELEASE,
            // perform the release immediately.
            if (intent?.action == ACTION_STOP_AND_RELEASE) {
                stopAndRelease()
                return START_NOT_STICKY
            }
        } catch (_: Throwable) {
            // ignore
        }
        return START_STICKY
    }

    companion object {
        const val ACTION_STOP_AND_RELEASE = "com.shirou.shibasync.ACTION_STOP_AND_RELEASE"
    }

    // NOVO MÉTODO: A MainActivity vai chamar este método QUANDO o serviço estiver conectado.
    fun prepareToCapture(resultCode: Int, data: Intent): Boolean {
        if (mediaProjection != null) {
            Log.w("AudioCaptureService", "MediaProjection já existe.")
            return true
        }
        try {
            val mediaProjectionManager = getSystemService(MediaProjectionManager::class.java)
            mediaProjection = mediaProjectionManager?.getMediaProjection(resultCode, data)
            isPrepared = true // Marca como pronto
            Log.d("AudioCaptureService", "Captura preparada com sucesso.")
            return true
        } catch (e: Exception) {
            Log.e("AudioCaptureService", "Falha ao preparar captura", e)
            return false
        }
    }

    @SuppressLint("MissingPermission")
    fun startCapture(socket: Socket?, matchingUid: Int? = null): Boolean {
        if (!isPrepared || mediaProjection == null) {
            Log.e("AudioCaptureService", "Serviço não está pronto, chame prepareToCapture primeiro.")
            return false
        }
        if (captureJob?.isActive == true) {
            Log.w("AudioCaptureService", "Captura já está ativa.")
            return true
        }
        val projection = mediaProjection ?: run {
            Log.e("AudioCaptureService", "MediaProjection é nulo, não é possível iniciar a captura.")
            return false
        }
        if (socket == null) return false

        val configBuilder = AudioPlaybackCaptureConfiguration.Builder(projection)
        // If a specific app UID was provided, capture only that app; otherwise capture media usage.
        if (matchingUid != null) {
            try {
                configBuilder.addMatchingUid(matchingUid)
                Log.d("AudioCaptureService", "🎯 Capturando UID específico: $matchingUid")
            } catch (_: Throwable) {
                // ignore if API behaves unexpectedly
            }
        } else {
            configBuilder.addMatchingUsage(android.media.AudioAttributes.USAGE_MEDIA)
            Log.d("AudioCaptureService", "🎵 Capturando USAGE_MEDIA (apps de mídia)")
            Log.w("AudioCaptureService", "⚠️ IMPORTANTE: Reproduza música/vídeo em outro app para capturar áudio!")
        }
        val config = configBuilder.build()

        // ✅ USAR 48000Hz CONSISTENTE COM PLAYBACK
        var chosenSampleRate = 48000
        var audioFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(chosenSampleRate)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()

        try {
            var minBufferSize = AudioRecord.getMinBufferSize(chosenSampleRate, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE || minBufferSize <= 0) {
                // fallback to 44100 if 48000 unsupported
                chosenSampleRate = 44100
                audioFormat = AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(chosenSampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                    .build()
                minBufferSize = AudioRecord.getMinBufferSize(chosenSampleRate, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
                Log.w("AudioCaptureService", "⚠️ 48kHz não suportado, usando 44.1kHz")
            }

            audioRecord = AudioRecord.Builder()
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(minBufferSize * 2)
                .setAudioPlaybackCaptureConfig(config)
                .build()

            audioRecord?.startRecording()

            captureJob = serviceScope.launch {
                // ✅ CHUNK DE 20ms PRECISO: 48000Hz * 0.02s * 2ch * 2bytes = 3840 bytes
                val bytesPerSample = 2
                val channels = 2
                val targetChunkSize = (chosenSampleRate * 0.02 * channels * bytesPerSample).toInt()
                
                // ✅ USAR BUFFER MENOR PARA CONTROLE PRECISO
                val readBufferSize = targetChunkSize / 4  // 960 bytes para leitura frequente
                val readBuffer = ByteArray(readBufferSize)
                val accBuffer = ByteArray(targetChunkSize)
                var accPos = 0
                
                var chunksEmitted = 0
                var lastEmitTime = System.currentTimeMillis()
                var emptyReads = 0
                var totalReads = 0

                Log.d("AudioCaptureService", "✅ Captura iniciada: ${chosenSampleRate}Hz, chunk=${targetChunkSize}bytes, readBuffer=${readBufferSize}bytes")
                Log.d("AudioCaptureService", "🔌 Socket conectado: ${socket.connected()}")

                while (isActive) {
                    val readResult = audioRecord?.read(readBuffer, 0, readBuffer.size) ?: 0
                    totalReads++
                    
                    if (readResult > 0) {
                        var offset = 0
                        while (offset < readResult) {
                            val toCopy = minOf(readResult - offset, accBuffer.size - accPos)
                            System.arraycopy(readBuffer, offset, accBuffer, accPos, toCopy)
                            accPos += toCopy
                            offset += toCopy

                            if (accPos >= accBuffer.size) {
                                // ✅ EMIT CHUNK E RASTREAR TIMING
                                val now = System.currentTimeMillis()
                                socket.emit("audio-chunk", accBuffer.copyOf())
                                chunksEmitted++
                                
                                // ✅ VERIFICAR AMPLITUDE DO ÁUDIO
                                var maxSample = 0
                                for (i in 0 until accBuffer.size step 2) {
                                    val sample = ((accBuffer[i].toInt() and 0xFF) or 
                                                 ((accBuffer[i+1].toInt() and 0xFF) shl 8)).toShort().toInt()
                                    maxSample = maxOf(maxSample, Math.abs(sample))
                                }
                                
                                // Log a cada 50 chunks
                                if (chunksEmitted % 50 == 0) {
                                    val interval = (now - lastEmitTime) / 50.0
                                    Log.d("AudioCaptureService", "📦 Chunk #$chunksEmitted | Interval: ${String.format("%.1f", interval)}ms | MaxAmp: $maxSample | EmptyReads: $emptyReads/$totalReads")
                                    lastEmitTime = now
                                    emptyReads = 0
                                    totalReads = 0
                                }
                                
                                // Log primeiros chunks com detalhes
                                if (chunksEmitted < 3) {
                                    Log.d("AudioCaptureService", "🎵 Chunk #$chunksEmitted | Size: ${accBuffer.size} | MaxAmp: $maxSample | Socket: ${socket.connected()}")
                                }
                                
                                accPos = 0
                            }
                        }
                    } else {
                        // slight delay to avoid busy loop if read returns 0
                        emptyReads++
                        if (totalReads % 500 == 0) {
                            Log.w("AudioCaptureService", "⚠️ AudioRecord retornando 0 bytes ($emptyReads/$totalReads leituras)")
                        }
                        delay(1) // ✅ REDUZIDO PARA MENOS LATÊNCIA
                    }
                }
                // If there's leftover data when stopping, emit it
                if (accPos > 0) {
                    socket.emit("audio-chunk", accBuffer.copyOfRange(0, accPos))
                }
            }
            Log.d("AudioCaptureService", "Captura de áudio iniciada com sucesso.")
            return true
        } catch (e: Exception) {
            Log.e("AudioCaptureService", "Falha ao iniciar AudioRecord", e)
            return false
        }
    }

    override fun stopCapture() {
        // Stop only the capture-related resources so the service and MediaProjection remain active.
        // This allows the app to stay connected to the server and resume capture quickly.
        captureJob?.cancel()
        captureJob = null
        try {
            audioRecord?.stop()
        } catch (_: Throwable) {
            // ignore
        }
        audioRecord?.release()
        audioRecord = null
        // NOTE: do NOT stop mediaProjection and do NOT call super.stopCapture() here.
        // Keeping mediaProjection and the foreground service running allows quick resume.
        Log.d("AudioCaptureService", "Captura de áudio parada (serviço permanece ativo).")
    }

    override fun onDestroy() {
        super.onDestroy()
        // Ensure any remaining resources are released
        try {
            mediaProjection?.stop()
        } catch (_: Throwable) {
            // ignore
        }
        mediaProjection = null
        serviceScope.cancel()
    }

    /**
     * Stop capture and release MediaProjection immediately.
     * Call this from the UI when you want to fully stop and release resources
     * (for example when the user navigates away).
     */
    fun stopAndRelease() {
        // stop capture if active
        try {
            captureJob?.cancel()
            captureJob = null
            try { audioRecord?.stop() } catch (_: Throwable) {}
            audioRecord?.release()
            audioRecord = null
        } catch (_: Throwable) {}

        // Stop and release MediaProjection
        try {
            mediaProjection?.stop()
        } catch (_: Throwable) {
            // ignore
        }
        mediaProjection = null

        // Stop foreground and the service itself
        try {
            stopForeground(true)
        } catch (_: Throwable) {}
        try {
            stopSelf()
        } catch (_: Throwable) {}
        serviceScope.cancel()
        Log.d("AudioCaptureService", "stopAndRelease: capture and projection stopped and released.")
    }
}

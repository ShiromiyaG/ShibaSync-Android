@file:SuppressLint("NewApi")
package com.shirou.shibasync

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.lerp
import android.widget.Toast
import android.util.Log
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.socket.client.IO
import io.socket.client.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // Apply the Neural-inspired dark theme and background
            AppTheme {
                // 0 = animated (default), 1 = static
                var bgMode by rememberSaveable { mutableStateOf(0) }
                Box(modifier = Modifier.fillMaxSize()) {
                    NeuralBackground(mode = bgMode)
                    SyncAudioApp()

                    // small toggle in top-right to switch background modes (animated/static)
                    Surface(
                        modifier = Modifier
                            .padding(12.dp)
                            .wrapContentSize()
                            .align(Alignment.TopEnd)
                            .clickable { bgMode = (bgMode + 1) % 2 },
                        shape = RoundedCornerShape(12.dp),
                        color = Color.Transparent
                    ) {
                        Text(
                            if (bgMode == 0) "BG: animated" else "BG: static",
                            color = colorResource(id = R.color.aurora_cyan_light),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }
    }
}

// 🌌 Aurora Dark Theme - Cores carregadas do colors.xml
@Composable
private fun getAuroraColors(): AuroraColors {
    return AuroraColors(
        auroraGreen = colorResource(id = R.color.aurora_green_light),
        auroraPurple = colorResource(id = R.color.aurora_purple_light),
        auroraCyan = colorResource(id = R.color.aurora_cyan_light),
        auroraPink = colorResource(id = R.color.aurora_pink_light),
        darkBackground = colorResource(id = R.color.dark_background),
        darkSurface = colorResource(id = R.color.dark_surface),
        iceWhite = colorResource(id = R.color.ice_white),
        // Cores do gradiente
        gradientPurple = colorResource(id = R.color.aurora_gradient_purple),
        gradientBlue = colorResource(id = R.color.aurora_gradient_blue),
        gradientDarkBlue = colorResource(id = R.color.aurora_gradient_dark_blue),
        gradientGreen = colorResource(id = R.color.aurora_gradient_green),
        // Aliases para compatibilidade
        neonViolet = colorResource(id = R.color.aurora_purple)
    )
}

data class AuroraColors(
    val auroraGreen: Color,
    val auroraPurple: Color,
    val auroraCyan: Color,
    val auroraPink: Color,
    val darkBackground: Color,
    val darkSurface: Color,
    val iceWhite: Color,
    val gradientPurple: Color,
    val gradientBlue: Color,
    val gradientDarkBlue: Color,
    val gradientGreen: Color,
    val neonViolet: Color
)

// Aliases globais para compatibilidade (será calculado dentro do Composable)
private lateinit var auroraColorsInstance: AuroraColors

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val auroraColors = getAuroraColors()
    
    val colors = darkColorScheme(
        primary = auroraColors.auroraGreen,
        secondary = auroraColors.auroraPurple,
        tertiary = auroraColors.auroraCyan,
        background = auroraColors.darkBackground,
        surface = auroraColors.darkSurface,
        onBackground = auroraColors.iceWhite,
        onSurface = auroraColors.iceWhite,
        onPrimary = auroraColors.darkBackground,
        onSecondary = auroraColors.darkBackground,
        error = auroraColors.auroraPink
    )
    MaterialTheme(
        colorScheme = colors,
        typography = Typography(),
        content = content
    )
}

@Composable
fun NeuralBackground(mode: Int = 0) {
    // 🌌 Aurora Boreal Escura - Cores carregadas do XML
    val auroraColors = getAuroraColors()
    
    val colorSetA = listOf(
        auroraColors.gradientPurple,
        auroraColors.gradientBlue,
        auroraColors.gradientDarkBlue,
        auroraColors.gradientGreen
    )
    val colorSetB = listOf(
        auroraColors.gradientBlue,
        auroraColors.gradientGreen,
        auroraColors.gradientPurple,
        auroraColors.gradientDarkBlue
    )

    // If static mode requested, show a static blend
    if (mode == 1) {
        Box(modifier = Modifier.fillMaxSize().background(auroraColors.darkBackground))
        return
    }

    val transition = rememberInfiniteTransition()
    val fraction by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 9000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        )
    )
    val angleDeg by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 16000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        )
    )

    val animatedColors = remember(fraction) {
        colorSetA.indices.map { i -> lerp(colorSetA[i], colorSetB[i], fraction) }
    }

    Box(modifier = Modifier
        .fillMaxSize()
        .drawBehind {
            val w = size.width
            val h = size.height
            val rad = angleDeg * (kotlin.math.PI / 180.0)
            val dx = kotlin.math.cos(rad).toFloat()
            val dy = kotlin.math.sin(rad).toFloat()
            val start = Offset(w * (0.5f - dx * 0.5f), h * (0.5f - dy * 0.5f))
            val end = Offset(w * (0.5f + dx * 0.5f), h * (0.5f + dy * 0.5f))
            val brush = Brush.linearGradient(colors = animatedColors, start = start, end = end)
            drawRect(brush = brush, size = size)

            // Overlay muito sutil para unificar as cores
            drawRect(color = Color(0xFF000000).copy(alpha = 0.08f), size = size)
        }
    ) {
        // Halos de Aurora Boreal - um pouco mais brilhantes
        Box(modifier = Modifier
            .offset(x = (-80).dp, y = 200.dp)
            .size(320.dp)
            .background(
                brush = Brush.radialGradient(listOf(auroraColors.auroraPurple.copy(alpha = 0.30f), Color.Transparent), center = Offset.Zero, radius = 240f),
                shape = RoundedCornerShape(200.dp)
            ))

        Box(modifier = Modifier
            .offset(x = 220.dp, y = (-40).dp)
            .size(260.dp)
            .background(
                brush = Brush.radialGradient(listOf(auroraColors.auroraPink.copy(alpha = 0.25f), Color.Transparent), center = Offset.Zero, radius = 200f),
                shape = RoundedCornerShape(200.dp)
            ))

        Box(modifier = Modifier
            .offset(x = 80.dp, y = 120.dp)
            .size(220.dp)
            .background(
                brush = Brush.radialGradient(listOf(auroraColors.auroraCyan.copy(alpha = 0.22f), Color.Transparent), center = Offset.Zero, radius = 180f),
                shape = RoundedCornerShape(200.dp)
            ))
    }
}

@Composable
fun GradientButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, secondary: Boolean = false) {
    val auroraColors = getAuroraColors()
    
    val brush = if (!secondary) Brush.linearGradient(listOf(auroraColors.auroraPurple, auroraColors.auroraPink, auroraColors.neonViolet))
    else Brush.linearGradient(listOf(Color.Transparent, auroraColors.auroraPurple.copy(alpha = 0.08f)))

    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .shadow(8.dp, RoundedCornerShape(50))
            .clickable { onClick() }
            .background(brush = brush),
        color = Color.Transparent,
        tonalElevation = 6.dp
    ) {
        Box(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
            Text(text = text.uppercase(), color = if (secondary) auroraColors.auroraPurple else auroraColors.darkBackground, fontWeight = FontWeight.SemiBold)
        }
    }
}

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun SyncAudioApp() {
    var screen by remember { mutableStateOf("main") }
    var serverIp by remember { mutableStateOf("") }

    // Use a transparent Surface so the animated NeuralBackground behind is visible
    Surface(modifier = Modifier.fillMaxSize(), color = Color.Transparent) {
        AnimatedContent(
            targetState = screen,
            transitionSpec = {
                slideInHorizontally(animationSpec = tween(durationMillis = 600)) { fullWidth -> fullWidth }
                    .togetherWith(slideOutHorizontally(animationSpec = tween(durationMillis = 600)) { fullWidth -> -fullWidth })
            }, label = "ScreenTransition"
        ) { targetScreen ->
            when (targetScreen) {
                "main" -> MainScreen(
                    onRoleSelected = { role, ip ->
                        serverIp = ip
                        screen = role
                    }
                )
                "sender" -> SenderScreen(serverIp = serverIp, onBack = { screen = "main" })
                "receiver" -> ReceiverScreen(serverIp = serverIp, onBack = { screen = "main" })
            }
        }
    }
}

@Composable
fun MainScreen(onRoleSelected: (String, String) -> Unit) {
    var serverIpState by remember { mutableStateOf(TextFieldValue("")) } // Deixar vazio por padrão

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Logo and title area with a glass-like container
        Surface(
            modifier = Modifier
                .wrapContentSize()
                .padding(8.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.06f)
        ) {
            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painter = painterResource(id = R.drawable.shiba_logo), // Use um drawable válido
                    contentDescription = "App Logo",
                    modifier = Modifier.size(64.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                // Gradient-like logo text
                Text(
                    text = "Shiba Sync",
                    style = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.ExtraBold),
                    color = colorResource(id = R.color.aurora_purple_light)
                )
            }
        }

        OutlinedTextField(
            value = serverIpState,
            onValueChange = { serverIpState = it },
            label = { Text("URL do Servidor") },
            placeholder = { Text("Ex: http://192.168.1.10:3000") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(20.dp))
        GradientButton(text = "Ser o Emissor (Transmitir)", onClick = { onRoleSelected("sender", serverIpState.text) }, modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(16.dp))
        GradientButton(text = "Ser o Receptor (Ouvir)", onClick = { onRoleSelected("receiver", serverIpState.text) }, modifier = Modifier.fillMaxWidth(), secondary = true)
    }
}

@Composable
fun SenderScreen(serverIp: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var socket by remember { mutableStateOf<Socket?>(null) }
    var status by remember { mutableStateOf("Aguardando...") }
    var audioCaptureService by remember { mutableStateOf<AudioCaptureService?>(null) }
    // Keep a reference to the ServiceConnection so we can unbind later
    var boundServiceConnection by remember { mutableStateOf<ServiceConnection?>(null) }
    var isCapturing by remember { mutableStateOf(false) }
    var permissionGranted by remember { mutableStateOf(false) }
    var activeSenders by remember { mutableStateOf(0) }
    var activeListeners by remember { mutableStateOf(0) }
    val mediaProjectionManager = remember(context) {
        context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
    }

    val mediaProjectionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            status = "Permissão concedida. Vinculando ao serviço..."
            permissionGranted = true
            val serviceIntent = Intent(context, AudioCaptureService::class.java)
            context.startForegroundService(serviceIntent)
            val serviceConnection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val localService = (service as AudioCaptureService.AudioCaptureBinder).getService()
                        audioCaptureService = localService
                        if (localService.prepareToCapture(result.resultCode, result.data!!)) {
                            status = "Serviço pronto. Capturando áudio da tela."
                        } else {
                            status = "Falha ao preparar o serviço de captura."
                        }
                    }
                }
                override fun onServiceDisconnected(name: ComponentName?) {
                    audioCaptureService = null
                }
            }
            // bind and keep the connection reference so we can unbind later
            boundServiceConnection = serviceConnection
            context.bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
        } else {
            status = "Permissão de captura negada."
        }
    }

    fun requestScreenCapturePermission() {
        val manager = mediaProjectionManager
        if (manager != null) {
            mediaProjectionLauncher.launch(manager.createScreenCaptureIntent())
        } else {
            Toast.makeText(context, "Captura de tela indisponível neste dispositivo.", Toast.LENGTH_LONG).show()
        }
    }

    fun parseStreamStatsPayload(payload: Any?): Pair<Int, Int>? {
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

    DisposableEffect(serverIp) {
        val serverUrl = if (serverIp.startsWith("http")) serverIp else "http://$serverIp:3000"
        try {
            val options = IO.Options().apply { transports = arrayOf("websocket", "polling") }
            val newSocket = IO.socket(serverUrl, options)
            socket = newSocket
            newSocket.on(Socket.EVENT_CONNECT) {
                scope.launch(Dispatchers.Main) {
                    status = "✅ Conectado!"
                    newSocket.emit("start-stream")
                    newSocket.emit("request-stats")
                }
            }
            newSocket.on(Socket.EVENT_DISCONNECT) {
                scope.launch(Dispatchers.Main) {
                    status = "❌ Desconectado"
                    activeSenders = 0
                    activeListeners = 0
                }
            }
            newSocket.on(Socket.EVENT_CONNECT_ERROR) { args ->
                scope.launch(Dispatchers.Main) {
                    status = "❌ Erro: ${args.getOrNull(0)}"
                    activeSenders = 0
                    activeListeners = 0
                }
            }
            newSocket.on("stream-stats") { args ->
                val counts = parseStreamStatsPayload(args.firstOrNull())
                if (counts != null) {
                    scope.launch(Dispatchers.Main) {
                        activeSenders = counts.first
                        activeListeners = counts.second
                    }
                }
            }
            newSocket.connect()
        } catch (e: Exception) {
            Log.e("SenderScreen", "Erro ao conectar socket", e)
            scope.launch(Dispatchers.Main) { status = "❌ Erro ao conectar" }
        }
        onDispose {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                audioCaptureService?.stopCapture()
            }
            context.stopService(Intent(context, AudioCaptureService::class.java))
            socket?.off("stream-stats")
            socket?.off(Socket.EVENT_CONNECT)
            socket?.off(Socket.EVENT_DISCONNECT)
            socket?.off(Socket.EVENT_CONNECT_ERROR)
            socket?.disconnect()
            activeSenders = 0
            activeListeners = 0
        }
    }

    fun startAudioCapture() {
        val service = audioCaptureService ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val success = service.startCapture(socket, matchingUid = null) // captura todos apps
            if (success) {
                isCapturing = true
                status = "Transmitindo"
            } else {
                status = "Falha ao iniciar captura."
            }
        }
    }

    fun stopAudioCapture() {
        isCapturing = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            audioCaptureService?.stopCapture()
        }
        try { socket?.emit("stop-stream") } catch (_: Throwable) {}
        status = "Transmissão parada."
    }

    fun stopAndLeave() {
        Log.d("SenderScreen", "stopAndLeave called; isCapturing=$isCapturing, permissionGranted=$permissionGranted")
        Toast.makeText(context, "Parando transmissão...", Toast.LENGTH_SHORT).show()

        // First, unbind if we have bound the service (so the service won't be kept alive by the binding)
        try {
            boundServiceConnection?.let { conn ->
                try { context.unbindService(conn) } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
        boundServiceConnection = null

        // Ensure capture and projection are fully released regardless of isCapturing state
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                if (audioCaptureService != null) {
                    // we had a bound instance; request it to stop and release
                    audioCaptureService?.stopAndRelease()
                    Log.d("SenderScreen", "Called stopAndRelease on bound service")
                } else {
                    // No bound instance: request the service process to stop-and-release via startService
                    try {
                        val stopIntent = Intent(context, AudioCaptureService::class.java).apply { action = AudioCaptureService.ACTION_STOP_AND_RELEASE }
                        context.startService(stopIntent)
                        Log.d("SenderScreen", "Requested service stop via ACTION_STOP_AND_RELEASE")
                    } catch (_: Throwable) {
                        Log.w("SenderScreen", "Failed to request ACTION_STOP_AND_RELEASE")
                    }
                }
            } else {
                audioCaptureService?.stopCapture()
            }
            Log.d("SenderScreen", "Requested stop on AudioCaptureService")
            Toast.makeText(context, "Captura parada", Toast.LENGTH_SHORT).show()
        } catch (e: Throwable) {
            Log.e("SenderScreen", "Error while stopping capture", e)
        }
        // clear local reference
        audioCaptureService = null
        isCapturing = false

        // Inform server and disconnect socket
        try { socket?.emit("stop-stream") } catch (_: Throwable) {}
        try {
            socket?.off("stream-stats")
            socket?.off(Socket.EVENT_CONNECT)
            socket?.off(Socket.EVENT_DISCONNECT)
            socket?.off(Socket.EVENT_CONNECT_ERROR)
        } catch (_: Throwable) {}
        try { socket?.disconnect() } catch (_: Throwable) {}
        socket = null
        activeSenders = 0
        activeListeners = 0

        // Unbind from the service if we bound to it earlier, then stop the service
        try {
            boundServiceConnection?.let { conn ->
                try { context.unbindService(conn) } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
        boundServiceConnection = null

        // Stop the service as a final measure
        try { context.stopService(Intent(context, AudioCaptureService::class.java)) } catch (_: Throwable) {}

        status = "Transmissão parada. Saindo..."
        Log.d("SenderScreen", "stopAndLeave completed; navigating back")
        onBack()
    }

    // Handle the system Back button to ensure capture is released
    BackHandler(enabled = true) {
        stopAndLeave()
    }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        shape = RoundedCornerShape(20.dp),
        color = Color.Transparent
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("Modo Emissor", style = MaterialTheme.typography.headlineMedium, color = colorResource(id = R.color.aurora_cyan_light))
            Spacer(modifier = Modifier.height(12.dp))
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.02f),
                tonalElevation = 2.dp,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp)
            ) {
                Text(status, modifier = Modifier.padding(12.dp), textAlign = TextAlign.Center, color = colorResource(id = R.color.aurora_purple_light))
            }
            Spacer(modifier = Modifier.height(12.dp))
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.04f),
                tonalElevation = 1.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp)
            ) {
                Text(
                    text = "Emissores ativos: $activeSenders | Ouvintes ativos: $activeListeners",
                    modifier = Modifier.padding(vertical = 8.dp, horizontal = 12.dp),
                    textAlign = TextAlign.Center,
                    color = colorResource(id = R.color.aurora_green_light),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Spacer(modifier = Modifier.height(18.dp))

            if (!permissionGranted) {
                GradientButton(text = "Permitir Captura de Áudio", onClick = {
                    requestScreenCapturePermission()
                }, modifier = Modifier.fillMaxWidth())
            } else {
                if (!isCapturing) {
                    GradientButton(text = "Trocar Fonte de Áudio", onClick = {
                        requestScreenCapturePermission()
                    }, modifier = Modifier.fillMaxWidth(), secondary = true)
                }
                Spacer(modifier = Modifier.height(8.dp))
                if (!isCapturing) {
                    GradientButton(text = "Iniciar Transmissão", onClick = { startAudioCapture() }, modifier = Modifier.fillMaxWidth())
                } else {
                    Button(onClick = { stopAudioCapture() }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                        Text("Parar Transmissão")
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))
            GradientButton(text = "Voltar", onClick = { stopAndLeave() }, modifier = Modifier.fillMaxWidth(), secondary = true)
        }
    }
}

@Composable
fun ReceiverScreen(serverIp: String, onBack: () -> Unit) {
    val context = LocalContext.current
    var audioService by remember { mutableStateOf<AudioPlaybackService?>(null) }
    var isServiceBound by remember { mutableStateOf(false) }
    val connectionState by audioService?.connectionState?.observeAsState(ConnectionState.IDLE) ?: remember { mutableStateOf(ConnectionState.IDLE) }
    val isPlaying by audioService?.isPlayingLive?.observeAsState(false) ?: remember { mutableStateOf(false) }
    var volume by remember { mutableStateOf(1f) } // 1f = 100%
    val senderCount by audioService?.activeSenderCount?.observeAsState(0) ?: remember { mutableStateOf(0) }
    val listenerCount by audioService?.activeListenerCount?.observeAsState(0) ?: remember { mutableStateOf(0) }

    val serviceConnection = remember {
        object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                audioService = (service as AudioPlaybackService.AudioPlaybackBinder).getService()
                isServiceBound = true
                audioService?.connectToServer(serverIp)
                audioService?.setVolume(volume)
            }
            override fun onServiceDisconnected(name: ComponentName?) {
                audioService = null
                isServiceBound = false
            }
        }
    }

    // Permission launcher to request POST_NOTIFICATIONS on Android 13+
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted: Boolean ->
        if (granted) {
            // Start and bind the service once permission is granted
            val intent = Intent(context, AudioPlaybackService::class.java)
            try { context.startForegroundService(intent) } catch (_: Throwable) { try { context.startService(intent) } catch (_: Throwable) {} }
            context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        } else {
            Toast.makeText(context, "Permissão de notificações necessária para o serviço em primeiro plano.", Toast.LENGTH_LONG).show()
        }
    }

    DisposableEffect(Unit) {
        val intent = Intent(context, AudioPlaybackService::class.java)

        // On Android 13+ we need POST_NOTIFICATIONS runtime permission to post the foreground notification.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            if (granted) {
                try { context.startForegroundService(intent) } catch (_: Throwable) { try { context.startService(intent) } catch (_: Throwable) {} }
                context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
            } else {
                // request permission and start service in the launcher callback if granted
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            // Older Android versions don't require runtime notification permission
            try { context.startForegroundService(intent) } catch (_: Throwable) { try { context.startService(intent) } catch (_: Throwable) {} }
            context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }

        onDispose {
            // Desconectar completamente ao sair da tela para evitar reconexões infinitas
            audioService?.disconnect()
            
            // Only unbind if we actually bound successfully
            if (isServiceBound) {
                try { context.unbindService(serviceConnection) } catch (_: Throwable) {}
                isServiceBound = false // avoid double-unbind
            }
        }
    }

    // When the user presses the system Back button, stop playback and navigate back.
    BackHandler {
        audioService?.stopPlayback()
        onBack()
    }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        shape = RoundedCornerShape(20.dp),
        color = Color.Transparent
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("Modo Receptor", style = MaterialTheme.typography.headlineMedium, color = colorResource(id = R.color.aurora_cyan_light))
            Spacer(modifier = Modifier.height(12.dp))
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.04f),
                tonalElevation = 1.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp)
            ) {
                Text(
                    text = "Emissores ativos: $senderCount | Ouvintes ativos: $listenerCount",
                    modifier = Modifier.padding(vertical = 8.dp, horizontal = 12.dp),
                    textAlign = TextAlign.Center,
                    color = colorResource(id = R.color.aurora_green_light),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Spacer(modifier = Modifier.height(12.dp))

            when (connectionState) {
                ConnectionState.CONNECTING -> {
                    Text("Conectando...", textAlign = TextAlign.Center)
                    Spacer(modifier = Modifier.height(16.dp))
                    CircularProgressIndicator(color = colorResource(id = R.color.aurora_purple_light))
                }
                ConnectionState.CONNECTED -> {
                    Text(if (isPlaying) "Reproduzindo..." else "Conectado. Pausado.", textAlign = TextAlign.Center, color = colorResource(id = R.color.aurora_purple_light))
                    Spacer(modifier = Modifier.height(24.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        GradientButton(text = if (isPlaying) "Pausar" else "Reproduzir", onClick = { audioService?.togglePlayback() })
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                    Text("Volume", style = MaterialTheme.typography.labelMedium, color = colorResource(id = R.color.aurora_purple_light))
                    Slider(
                        value = volume,
                        onValueChange = {
                            volume = it
                            audioService?.setVolume(it)
                        },
                        valueRange = 0f..1f,
                        steps = 9,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                    )
                }
                else -> {
                    Text("Desconectado ou falha na conexão.", color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                    Spacer(modifier = Modifier.height(16.dp))
                    GradientButton(text = "Tentar Novamente", onClick = { audioService?.connectToServer(serverIp) }, modifier = Modifier.fillMaxWidth())
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            GradientButton(text = "Voltar", onClick = { audioService?.stopPlayback(); onBack() }, modifier = Modifier.fillMaxWidth(), secondary = true)
        }
    }
}

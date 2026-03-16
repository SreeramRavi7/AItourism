package com.example.glassdescribe

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.camera.startStreamSession
import com.meta.wearable.dat.core.types.*
import com.meta.wearable.dat.core.selectors.*
import com.meta.wearable.dat.core.session.*
import com.meta.wearable.dat.camera.*
import com.meta.wearable.dat.camera.types.*
import com.meta.wearable.dat.mockdevice.MockDeviceKit
import com.meta.wearable.dat.mockdevice.api.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "GlassDescribe"
        private val CAPTURE_KEYWORDS = listOf(
            "describe", "capture", "take photo", "take a photo",
            "snap", "what is this", "what do you see"
        )
        private val COMPARE_KEYWORDS = listOf(
            "compare", "difference", "similar", "same"
        )
    }

    private lateinit var tts: TextToSpeech
    private var ttsReady = false
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListeningInternal = false

    private enum class BackendMode { EMULATOR, ADB_USB, WIFI }
    private val backendMode = BackendMode.ADB_USB
    private val laptopIp = "192.168.1.100"
    private val baseUrl = when (backendMode) {
        BackendMode.EMULATOR -> "http://10.0.2.2:3000"
        BackendMode.ADB_USB -> "http://127.0.0.1:3000"
        BackendMode.WIFI -> "http://$laptopIp:3000"
    }
    private val imagesUrl = "$baseUrl/images"
    private val askUrl = "$baseUrl/ask"
    private val sessionId = "glasses_user1"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private var streamSession: StreamSession? = null
    private var mockDeviceKit: MockDeviceKitInterface? = null
    private var mockDevice: MockRaybanMeta? = null
    private var useMockDevice = true

    private var imageCapture: ImageCapture? = null
    private var cameraProvider: ProcessCameraProvider? = null

    private val _uiState = MutableStateFlow(GlassDescribeState())
    private val uiState = _uiState.asStateFlow()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this, this)
        initializeWearablesSDK()

        setContent {
            val state by uiState.collectAsState()

            val pickMockImageLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument()
            ) { uri -> if (uri != null) setMockCaptureImage(uri) }

            val pickMockVideoLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument()
            ) { uri -> if (uri != null) setMockVideoFeed(uri) }

            val pickImageLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument()
            ) { uri ->
                if (uri != null) {
                    val bitmap = runCatching {
                        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                    }.getOrNull()
                    if (bitmap != null) handleCapturedBitmap(bitmap)
                }
            }

            val cameraPermLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { granted -> if (granted) { setupCameraX(); autoCapture() } }

            LaunchedEffect(state.triggerAutoCapture) {
                if (state.triggerAutoCapture) {
                    _uiState.value = _uiState.value.copy(triggerAutoCapture = false)
                    if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA)
                        == PackageManager.PERMISSION_GRANTED) {
                        if (imageCapture == null) setupCameraX()
                        autoCapture()
                    } else {
                        cameraPermLauncher.launch(Manifest.permission.CAMERA)
                    }
                }
            }

            GlassDescribeUI(
                state = state,
                onRegisterGlasses = { registerWithGlasses() },
                onUnregisterGlasses = { unregisterFromGlasses() },
                onStartStream = { startGlassesStream() },
                onStopStream = { stopGlassesStream() },
                onCapturePhoto = { triggerCapture() },
                onPickImage = { pickImageLauncher.launch(arrayOf("image/*")) },
                onStartListening = { startVoiceListening() },
                onStopListening = { stopVoiceListening() },
                onAskQuestion = { q -> askServer(q) },
                onToggleMock = { toggleMockDevice() },
                onPairMock = { pairAndPrepareMockDevice() },
                onSetMockImage = { pickMockImageLauncher.launch(arrayOf("image/*")) },
                onSetMockVideo = { pickMockVideoLauncher.launch(arrayOf("video/*")) }
            )
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) {
            setupCameraX()
        }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent) }
    override fun onInit(status: Int) {
        ttsReady = (status == TextToSpeech.SUCCESS)
        if (ttsReady) tts.language = Locale.US
    }
    override fun onDestroy() {
        super.onDestroy()
        stopGlassesStream()
        speechRecognizer?.destroy()
        cameraProvider?.unbindAll()
        if (::tts.isInitialized) { tts.stop(); tts.shutdown() }
    }

    private fun setupCameraX() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                imageCapture = capture
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, capture)
                Log.d(TAG, "CameraX ready")
            } catch (e: Exception) { Log.e(TAG, "CameraX setup failed", e) }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun autoCapture() {
        val capture = imageCapture
        if (capture == null) { speakThenListen("Camera not ready."); return }
        updateState { copy(statusText = "Auto-capturing...") }
        capture.takePicture(ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(proxy: ImageProxy) {
                    val bitmap = imageProxyToBitmap(proxy)
                    proxy.close()
                    if (bitmap != null) handleCapturedBitmap(bitmap)
                    else speakThenListen("Failed to process photo.")
                }
                override fun onError(e: ImageCaptureException) {
                    Log.e(TAG, "Auto-capture failed", e)
                    speakThenListen("Capture failed. Try again.")
                }
            })
    }

    private fun imageProxyToBitmap(proxy: ImageProxy): Bitmap? {
        return try {
            val buf = proxy.planes[0].buffer
            val bytes = ByteArray(buf.remaining())
            buf.get(bytes)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) { Log.e(TAG, "Conversion failed", e); null }
    }

    private fun initializeWearablesSDK() {
        try {
            Wearables.initialize(applicationContext)
            updateState { copy(sdkInitialized = true) }
            lifecycleScope.launch {
                Wearables.registrationState.collect { st ->
                    val registered = (st is RegistrationState.Registered)
                    updateState { copy(registrationState = st.toString(), isRegistered = registered) }
                    if (registered) speak("Glasses connected. Say describe to capture.")
                }
            }
            lifecycleScope.launch {
                Wearables.devices.collect { deviceSet ->
                    updateState { copy(connectedDevices = deviceSet.size,
                        deviceInfo = if (deviceSet.isNotEmpty()) deviceSet.joinToString { it.toString() } else "No devices") }
                }
            }
            if (useMockDevice) setupMockDevice()
        } catch (e: Exception) {
            Log.e(TAG, "SDK init failed", e)
            updateState { copy(statusText = "SDK init failed: ${e.message}") }
        }
    }

    private fun setupMockDevice() {
        try {
            mockDeviceKit = MockDeviceKit.getInstance(applicationContext)
            updateState { copy(mockDeviceEnabled = true, statusText = "Mock ready. Tap Pair Mock.") }
        } catch (e: Exception) {
            Log.e(TAG, "Mock init failed", e)
            updateState { copy(statusText = "Mock init error: ${e.message}") }
        }
    }

    private fun pairAndPrepareMockDevice() {
        try {
            if (mockDeviceKit == null) setupMockDevice()
            val kit = mockDeviceKit ?: return
            kit.pairedDevices.forEach { kit.unpairDevice(it) }
            val device = kit.pairRaybanMeta()
            mockDevice = device
            try { device.powerOn() } catch (e: Exception) { Log.d(TAG, "powerOn: ${e.message}") }
            try { device.unfold() } catch (e: Exception) { Log.d(TAG, "unfold: ${e.message}") }
            try { device.don() } catch (e: Exception) { Log.d(TAG, "don: ${e.message}") }
            updateState { copy(statusText = "Mock glasses ready! Start Stream or set mock image.", mockDeviceReady = true) }
            speak("Mock glasses ready.")
        } catch (e: Exception) {
            Log.e(TAG, "Mock pair failed", e)
            updateState { copy(statusText = "Mock pair error: ${e.message}") }
        }
    }

    private fun setMockCaptureImage(uri: Uri) {
        try {
            val device = mockDevice ?: run { updateState { copy(statusText = "Pair mock first!") }; return }
            val cameraKit = device.getCameraKit()
            cameraKit.setCapturedImage(uri)
            updateState { copy(statusText = "Mock image set! Start Stream & Capture.", mockImageSet = true) }
            speak("Mock image set.")
        } catch (e: Exception) {
            Log.e(TAG, "Set mock image failed", e)
            updateState { copy(statusText = "Mock image error: ${e.message}") }
        }
    }

    private fun setMockVideoFeed(uri: Uri) {
        try {
            val device = mockDevice ?: run { updateState { copy(statusText = "Pair mock first!") }; return }
            val cameraKit = device.getCameraKit()
            cameraKit.setCameraFeed(uri)
            updateState { copy(statusText = "Mock video set!") }
            speak("Mock video feed set.")
        } catch (e: Exception) {
            Log.e(TAG, "Set mock video failed", e)
            updateState { copy(statusText = "Mock video error: ${e.message}") }
        }
    }

    private fun toggleMockDevice() {
        useMockDevice = !useMockDevice
        if (useMockDevice) setupMockDevice()
        updateState { copy(mockDeviceEnabled = useMockDevice) }
    }

    private fun registerWithGlasses() {
        try { Wearables.startRegistration(this); updateState { copy(statusText = "Registering...") } }
        catch (e: Exception) { updateState { copy(statusText = "Register error: ${e.message}") } }
    }

    private fun unregisterFromGlasses() {
        try { Wearables.startUnregistration(this); stopGlassesStream(); updateState { copy(statusText = "Unregistered", isRegistered = false) } }
        catch (e: Exception) { Log.e(TAG, "Unregister failed", e) }
    }

    private fun startGlassesStream() {
        lifecycleScope.launch {
            try {
                val session = Wearables.startStreamSession(context = applicationContext, deviceSelector = AutoDeviceSelector())
                streamSession = session
                updateState { copy(isStreaming = true, statusText = "Streaming. Say describe to capture.") }
                speak("Stream started. Say describe to capture.")
                launch { session.state.collect { s ->
                    if (s == StreamSessionState.STOPPED && !useMockDevice) updateState { copy(isStreaming = false, statusText = "Stream stopped") }
                } }
                launch { session.videoStream.collect { } }
                startVoiceListening()
            } catch (e: Exception) {
                Log.e(TAG, "Stream failed", e)
                if (useMockDevice) {
                    updateState { copy(isStreaming = true, statusText = "Mock mode (phone camera). Say describe.") }
                    speak("Mock mode. Say describe to auto-capture with phone camera.")
                    startVoiceListening()
                } else { updateState { copy(statusText = "Stream error: ${e.message}") }; speak("Stream failed.") }
            }
        }
    }

    private fun stopGlassesStream() {
        try { streamSession?.close(); streamSession = null; stopVoiceListening(); updateState { copy(isStreaming = false, statusText = "Stopped") } }
        catch (e: Exception) { Log.e(TAG, "Stop error", e) }
    }

    private fun triggerCapture() {
        val session = streamSession
        if (session != null) {
            updateState { copy(statusText = "Capturing...") }
            speak("Capturing.")
            lifecycleScope.launch {
                session.capturePhoto()
                    .onSuccess { photoData ->
                        updateState { copy(statusText = "Sending to AI...") }
                        val bytes = when (photoData) {
                            is PhotoData.Bitmap -> { val s = ByteArrayOutputStream(); photoData.bitmap.compress(Bitmap.CompressFormat.JPEG, 85, s); s.toByteArray() }
                            is PhotoData.HEIC -> { val buf = photoData.data; val b = ByteArray(buf.remaining()); buf.get(b); b }
                        }
                        sendImageToBackend(bytes)
                    }
                    .onFailure { err ->
                        Log.e(TAG, "Glasses capture failed", err)
                        if (useMockDevice) { runOnUiThread { speak("Using phone camera."); updateState { copy(triggerAutoCapture = true) } } }
                        else speakThenListen("Capture failed.")
                    }
            }
        } else if (useMockDevice) {
            speak("Auto capturing.")
            updateState { copy(triggerAutoCapture = true) }
        } else { speak("Start stream first.") }
    }

    private fun handleCapturedBitmap(bitmap: Bitmap) {
        updateState { copy(statusText = "Sending to AI...") }
        speak("Got it. Analyzing.")
        val stream = ByteArrayOutputStream()
        val scale = minOf(1f, 1024f / bitmap.width.toFloat())
        val resized = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true) else bitmap
        resized.compress(Bitmap.CompressFormat.JPEG, 75, stream)
        sendImageToBackend(stream.toByteArray())
    }

    private fun sendImageToBackend(jpegBytes: ByteArray) {
        uploadImageToServer(jpegBytes) { imageId, captionOrError ->
            if (imageId != null) { updateState { copy(lastImageId = imageId, statusText = captionOrError, imageCount = imageCount + 1) }; speakThenListen(captionOrError) }
            else { updateState { copy(statusText = "Error: $captionOrError") }; speakThenListen("Sorry, could not describe.") }
        }
    }

    private fun startVoiceListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        if (isListeningInternal) return
        isListeningInternal = true
        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(p: Bundle?) { updateState { copy(isListening = true, statusText = "Listening... say describe or ask a question") } }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buf: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) { isListeningInternal = false; updateState { copy(isListening = false) }; if (_uiState.value.isStreaming) startVoiceListening() }
                override fun onResults(results: Bundle?) {
                    isListeningInternal = false; updateState { copy(isListening = false) }
                    val spoken = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.lowercase() ?: ""
                    Log.d(TAG, "Voice: $spoken"); handleVoiceCommand(spoken)
                }
                override fun onPartialResults(p: Bundle?) {}
                override fun onEvent(t: Int, p: Bundle?) {}
            })
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        try { speechRecognizer?.startListening(intent) } catch (e: Exception) { isListeningInternal = false }
    }

    private fun stopVoiceListening() {
        isListeningInternal = false; speechRecognizer?.stopListening(); speechRecognizer?.destroy(); speechRecognizer = null; updateState { copy(isListening = false) }
    }

    private fun handleVoiceCommand(spoken: String) {
        when {
            CAPTURE_KEYWORDS.any { spoken.contains(it) } -> triggerCapture()
            COMPARE_KEYWORDS.any { spoken.contains(it) } -> askServer(spoken)
            spoken.isNotBlank() -> askServer(spoken)
            else -> { if (_uiState.value.isStreaming) startVoiceListening() }
        }
    }

    private fun speak(text: String) {
        if (!ttsReady) return
        val p = Bundle().apply { putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC) }
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, p, "u_${System.currentTimeMillis()}")
    }

    private fun speakThenListen(text: String) {
        if (!ttsReady) return
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(uid: String?) {}
            override fun onDone(uid: String?) { runOnUiThread { if (_uiState.value.isStreaming) startVoiceListening() } }
            override fun onError(uid: String?) { runOnUiThread { if (_uiState.value.isStreaming) startVoiceListening() } }
        })
        val p = Bundle().apply { putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC) }
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, p, "u_${System.currentTimeMillis()}")
    }

    private fun uploadImageToServer(jpegBytes: ByteArray, onDone: (String?, String) -> Unit) {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("sessionId", sessionId)
            .addFormDataPart("image", "glasses_photo.jpg", jpegBytes.toRequestBody("image/jpeg".toMediaType()))
            .build()
        httpClient.newCall(Request.Builder().url(imagesUrl).post(body).build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) { runOnUiThread { onDone(null, "Network error: ${e.message}") } }
            override fun onResponse(call: Call, response: Response) {
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) { runOnUiThread { onDone(null, "HTTP ${response.code}: $raw") }; return }
                val json = runCatching { JSONObject(raw) }.getOrNull()
                val id = json?.optString("imageId", "") ?: ""; val cap = json?.optString("caption", "") ?: ""
                runOnUiThread { if (id.isNotBlank() && cap.isNotBlank()) onDone(id, cap) else onDone(null, "Bad response") }
            }
        })
    }

    private fun askServer(question: String) {
        if (question.isBlank()) return
        updateState { copy(statusText = "Thinking: $question") }; speak("Let me think.")
        val payload = JSONObject().apply { put("sessionId", sessionId); put("question", question); _uiState.value.lastImageId?.let { put("currentImageId", it) } }
        httpClient.newCall(Request.Builder().url(askUrl).post(payload.toString().toRequestBody("application/json".toMediaType())).build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) { runOnUiThread { updateState { copy(statusText = "Network error") }; speakThenListen("Network error.") } }
            override fun onResponse(call: Call, response: Response) {
                val raw = response.body?.string().orEmpty()
                val ans = if (response.isSuccessful) runCatching { JSONObject(raw).optString("answer", "") }.getOrDefault("") else ""
                runOnUiThread { val f = if (ans.isNotBlank()) ans else "No answer"; updateState { copy(statusText = f) }; speakThenListen(f) }
            }
        })
    }

    private fun updateState(update: GlassDescribeState.() -> GlassDescribeState) { _uiState.value = _uiState.value.update() }
}

data class GlassDescribeState(
    val sdkInitialized: Boolean = false, val registrationState: String = "Not registered",
    val isRegistered: Boolean = false, val isStreaming: Boolean = false, val isListening: Boolean = false,
    val connectedDevices: Int = 0, val deviceInfo: String = "No devices",
    val statusText: String = "Initializing...", val lastImageId: String? = null,
    val imageCount: Int = 0, val mockDeviceEnabled: Boolean = false,
    val mockDeviceReady: Boolean = false, val mockImageSet: Boolean = false,
    val triggerAutoCapture: Boolean = false
)

@Composable
private fun GlassDescribeUI(
    state: GlassDescribeState, onRegisterGlasses: () -> Unit, onUnregisterGlasses: () -> Unit,
    onStartStream: () -> Unit, onStopStream: () -> Unit, onCapturePhoto: () -> Unit,
    onPickImage: () -> Unit, onStartListening: () -> Unit, onStopListening: () -> Unit,
    onAskQuestion: (String) -> Unit, onToggleMock: () -> Unit, onPairMock: () -> Unit,
    onSetMockImage: () -> Unit, onSetMockVideo: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scrollState = rememberScrollState()
    var questionText by remember { mutableStateOf("") }
    val micPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) onStartListening() }
    val btPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { if (it.values.all { v -> v }) onRegisterGlasses() }

    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().verticalScroll(scrollState).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("GlassDescribe", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color(0xFF64B5F6), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                Text("AI Visual Assistant for Meta Glasses", fontSize = 14.sp, color = Color.Gray, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                HorizontalDivider(color = Color(0xFF333333))

                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Connection", fontWeight = FontWeight.Bold, color = Color(0xFF64B5F6))
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(10.dp).background(if (state.isRegistered) Color(0xFF4CAF50) else Color(0xFFFF5722), CircleShape))
                            Spacer(Modifier.width(8.dp)); Text(state.registrationState, color = Color.White, fontSize = 13.sp)
                        }
                        Text("Devices: ${state.deviceInfo}", color = Color.Gray, fontSize = 12.sp)
                        Text("Images: ${state.imageCount}", color = Color.Gray, fontSize = 12.sp)
                        if (state.mockDeviceEnabled) Text("Mode: MOCK DEVICE", color = Color(0xFFFFA726), fontSize = 12.sp)
                    }
                }

                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Mock Device Setup", fontWeight = FontWeight.Bold, color = Color(0xFFFFA726))
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = onPairMock, Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFA726))) { Text("1. Pair Mock (PowerOn+Unfold+Don)", fontSize = 12.sp) }
                        Spacer(Modifier.height(6.dp))
                        OutlinedButton(onClick = onSetMockImage, Modifier.fillMaxWidth(), enabled = state.mockDeviceReady) { Text("2. Set Mock Capture Image (optional)", fontSize = 12.sp) }
                        Spacer(Modifier.height(6.dp))
                        OutlinedButton(onClick = onSetMockVideo, Modifier.fillMaxWidth(), enabled = state.mockDeviceReady) { Text("3. Set Mock Video Feed (optional)", fontSize = 12.sp) }
                        Spacer(Modifier.height(4.dp))
                        Text(if (state.mockDeviceReady) "Mock paired. Start Stream to begin." else "Tap Pair Mock to start.", color = Color.Gray, fontSize = 11.sp)
                    }
                }

                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Glasses Control", fontWeight = FontWeight.Bold, color = Color(0xFF64B5F6))
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { val act = context as? Activity ?: return@Button
                                if (ContextCompat.checkSelfPermission(act, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) btPermLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)) else onRegisterGlasses()
                            }, enabled = !state.isRegistered, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))) { Text("Register", fontSize = 12.sp) }
                            OutlinedButton(onClick = onUnregisterGlasses, enabled = state.isRegistered, modifier = Modifier.weight(1f)) { Text("Unregister", fontSize = 12.sp) }
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = onStartStream, enabled = (state.isRegistered || state.mockDeviceReady || state.mockDeviceEnabled) && !state.isStreaming, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2196F3))) { Text("Start Stream", fontSize = 12.sp) }
                            OutlinedButton(onClick = onStopStream, enabled = state.isStreaming, modifier = Modifier.weight(1f)) { Text("Stop Stream", fontSize = 12.sp) }
                        }
                    }
                }

                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Capture & Voice", fontWeight = FontWeight.Bold, color = Color(0xFF64B5F6))
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = onCapturePhoto, enabled = state.isStreaming || state.mockDeviceEnabled, modifier = Modifier.fillMaxWidth().height(56.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE91E63)), shape = RoundedCornerShape(28.dp)) { Text("Capture & Describe", fontSize = 16.sp) }
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = onPickImage, enabled = state.isStreaming || state.mockDeviceEnabled, modifier = Modifier.fillMaxWidth()) { Text("Pick from Gallery", fontSize = 14.sp) }
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { val act = context as? Activity ?: return@Button
                            if (ContextCompat.checkSelfPermission(act, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) micPermLauncher.launch(Manifest.permission.RECORD_AUDIO) else { if (state.isListening) onStopListening() else onStartListening() }
                        }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = if (state.isListening) Color(0xFF4CAF50) else Color(0xFF455A64))) { Text(if (state.isListening) "Listening..." else "Start Voice Commands", fontSize = 14.sp) }
                    }
                }

                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Ask About Photos", fontWeight = FontWeight.Bold, color = Color(0xFF64B5F6))
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(value = questionText, onValueChange = { questionText = it }, label = { Text("Type a question") }, modifier = Modifier.fillMaxWidth(), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color(0xFF64B5F6), unfocusedBorderColor = Color(0xFF444444)))
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { if (questionText.isNotBlank()) { onAskQuestion(questionText); questionText = "" } }, enabled = state.imageCount > 0 && questionText.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Ask") }
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("Compare all", "Last two", "Read text").forEach { a -> AssistChip(onClick = { onAskQuestion(a) }, label = { Text(a, fontSize = 11.sp) }, enabled = state.imageCount > 0) }
                        }
                    }
                }

                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF0D1B2A))) {
                    Column(Modifier.padding(12.dp)) {
                        Text("AI Response", fontWeight = FontWeight.Bold, color = Color(0xFF64B5F6))
                        Spacer(Modifier.height(8.dp))
                        Text(state.statusText, color = Color(0xFFE0E0E0), fontSize = 14.sp, lineHeight = 20.sp)
                    }
                }

                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A2E))) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Voice Commands", fontWeight = FontWeight.Bold, color = Color(0xFFFFA726))
                        Spacer(Modifier.height(4.dp))
                        listOf("\"describe\" — Auto-capture & describe", "\"compare all\" — Compare photos", "\"compare last two\" — Compare recent", "\"read the text\" — Read visible text").forEach { Text(it, color = Color.Gray, fontSize = 12.sp) }
                    }
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}
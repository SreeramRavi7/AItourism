package com.example.glassdescribe

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.concurrent.TimeUnit


class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {

    private lateinit var tts: TextToSpeech
    private var ttsReady: Boolean = false

    // Emulator -> laptop backend:
    private val USE_EMULATOR = true

    private val BACKEND_URL = if (USE_EMULATOR) {
        "http://10.0.2.2:3000/describe"
    } else {
        "http://192.168.1.25:3000/describe" // <-- your laptop IP
    }


    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS) // no overall timeout
        .build()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        tts = TextToSpeech(this, this)

        setContent {
            AppUI(
                backendUrl = BACKEND_URL,
                hasCameraPermission = {
                    ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.CAMERA
                    ) == PackageManager.PERMISSION_GRANTED
                },
                requestSpeak = { text -> speak(text) },
                sendJpegBytes = { bytes, onResultText ->
                    uploadToBackend(bytes, onResultText)
                }
            )
        }
    }

    override fun onInit(status: Int) {
        ttsReady = (status == TextToSpeech.SUCCESS)
        if (ttsReady) {
            tts.language = Locale.US
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }
    }

    private fun speak(text: String) {
        if (!ttsReady) return
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "desc")
    }

    private fun uploadToBackend(jpegBytes: ByteArray, onText: (String) -> Unit) {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "image",
                "photo.jpg",
                jpegBytes.toRequestBody("image/jpeg".toMediaType())
            )
            .build()

        val req = Request.Builder()
            .url(BACKEND_URL)
            .post(body)
            .build()

        httpClient.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) {
                e.printStackTrace()
                runOnUiThread { onText("Network error: ${e.message}") }
            }

            override fun onResponse(call: Call, response: Response) {
                val raw = response.body?.string().orEmpty()

                if (!response.isSuccessful) {
                    runOnUiThread { onText("HTTP ${response.code}: $raw") }
                    return
                }

                val text = try {
                    JSONObject(raw).optString("text", "")
                } catch (_: Exception) {
                    ""
                }

                runOnUiThread {
                    onText(if (text.isNotBlank()) text else "No description received")
                }
            }

        })
    }
}

@Composable
private fun AppUI(
    backendUrl: String,
    hasCameraPermission: () -> Boolean,
    requestSpeak: (String) -> Unit,
    sendJpegBytes: (ByteArray, (String) -> Unit) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var statusText by remember { mutableStateOf("Ready") }

    // 1) Camera capture result
    val takePreviewLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview()
    ) { bitmap: Bitmap? ->
        if (bitmap == null) {
            statusText = "No photo captured"
            return@rememberLauncherForActivityResult
        }

        statusText = "Uploading..."
        val jpegBytes = bitmapToJpeg(bitmap)
        sendJpegBytes(jpegBytes) { text ->
            statusText = text
            requestSpeak(text)
        }
    }

    // 2) Runtime permission request
    val requestCameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            takePreviewLauncher.launch(null)
        } else {
            statusText = "Camera permission denied"
        }
    }

    // 3) Gallery pick
    // 3) File picker (FORCES Files app / Downloads)
    val pickImageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) {
            statusText = "No image selected"
            return@rememberLauncherForActivityResult
        }

        statusText = "Uploading..."

        // Persist permission (safe)
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }

        // ✅ Decode using BitmapFactory (works for Downloads/Documents URIs)
        val bitmap = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                android.graphics.BitmapFactory.decodeStream(input)
            }
        }.getOrNull()

        if (bitmap == null) {
            statusText = "Could not decode file"
            return@rememberLauncherForActivityResult
        }

        val jpegBytes = bitmapToJpeg(bitmap)

        sendJpegBytes(jpegBytes) { text ->
            statusText = text
            requestSpeak(text)
        }
    }



    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Backend: $backendUrl")
        Text("Status: $statusText")

        Button(onClick = {
            if (hasCameraPermission()) {
                takePreviewLauncher.launch(null)
            } else {
                requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }) {
            Text("Take Photo & Describe")
        }

        Button(onClick = {
            pickImageLauncher.launch(arrayOf("image/*"))
        }) {
            Text("Pick Image & Describe")
        }
    }
}

private fun bitmapToJpeg(bitmap: Bitmap): ByteArray {
    val maxWidth = 1024

    val scale = minOf(
        1f,
        maxWidth.toFloat() / bitmap.width.toFloat()
    )

    val resizedBitmap = if (scale < 1f) {
        Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt(),
            (bitmap.height * scale).toInt(),
            true
        )
    } else {
        bitmap
    }

    val out = ByteArrayOutputStream()
    resizedBitmap.compress(Bitmap.CompressFormat.JPEG, 75, out)
    return out.toByteArray()
}


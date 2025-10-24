package com.vibe.secondbrain

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.MotionEvent
import android.widget.Button
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.UUID

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {

    // 🔧 Your Replit backend (NO trailing slash)
    private val BASE = "https://create-replit-borisaiassistant.replit.app"

    private lateinit var status: TextView
    private lateinit var transcript: TextView
    private lateinit var btnTalk: Button
    private lateinit var btnPhoto: Button
    private lateinit var btnVideo: Button
    private lateinit var btnNewChat: Button   // 🆕 Added new chat button

    private lateinit var speech: SpeechRecognizer
    private lateinit var tts: TextToSpeech
    private val client = OkHttpClient()
    private var ttsReady = false

    // 🧠 Keep a session id so /chat can ask follow-ups and remember context
    private lateinit var prefs: SharedPreferences
    private lateinit var sessionId: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.status)
        transcript = findViewById(R.id.transcript)
        btnTalk = findViewById(R.id.btnPush)
        btnPhoto = findViewById(R.id.btnPhoto)
        btnVideo = findViewById(R.id.btnVideo)
        btnNewChat = findViewById(R.id.btnNewChat)  // 🆕 Find new chat button

        ensurePermissions()

        // Init TTS + STT
        tts = TextToSpeech(this, this)
        speech = SpeechRecognizer.createSpeechRecognizer(this)
        speech.setRecognitionListener(recListener)

        // 🔐 load or create a persistent session id
        prefs = getSharedPreferences("vibe", MODE_PRIVATE)
        sessionId = prefs.getString("session", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("session", it).apply()
        }

        // 🧹 Handle New Chat button tap
        btnNewChat.setOnClickListener {
            sessionId = UUID.randomUUID().toString()
            prefs.edit().putString("session", sessionId).apply()
            status.text = "Started a new chat."
            speak("Started a new chat.")
        }

        // 🎙️ Push-to-talk UX
        btnTalk.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> { if (tts.isSpeaking) tts.stop(); startListening() }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> speech.stopListening()
            }
            true
        }

        // 📸 Gallery pickers
        btnPhoto.setOnClickListener {
            val i = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            pickImage.launch(i)
        }
        btnVideo.setOnClickListener {
            val i = Intent(Intent.ACTION_PICK, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            pickVideo.launch(i)
        }
    }

    // ---------- TTS ----------
    override fun onInit(code: Int) {
        ttsReady = code == TextToSpeech.SUCCESS
        if (ttsReady) tts.language = Locale.US
    }
    private fun speak(text: String) {
        if (ttsReady) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "utt")
    }

    // ---------- STT ----------
    private val recListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) { status.text = "Listening..." }
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() { status.text = "Processing..." }
        override fun onError(error: Int) { status.text = "Mic error: $error" }
        override fun onResults(results: Bundle) {
            val text = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (!text.isNullOrBlank()) {
                transcript.text = text
                handleUtterance(text)
            } else status.text = "Heard nothing, try again."
        }
        override fun onPartialResults(partialResults: Bundle) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun startListening() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        speech.startListening(i)
    }

    // ---------- Conversational routing to /chat ----------
    private fun handleUtterance(text: String) {
        sendChatMessage(text)
    }

    private fun sendChatMessage(userText: String) {
        Thread {
            try {
                val body = JSONObject().apply {
                    put("session", sessionId)    // keep conversation context
                    put("message", userText)     // backend expects "message"
                }

                val req = Request.Builder()
                    .url("$BASE/chat")
                    .addHeader("Content-Type", "application/json")
                    .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()

                val resp = client.newCall(req).execute()
                val code = resp.code
                val txt = resp.body?.string() ?: ""

                var reply = "..."
                var saved = ""
                try {
                    val j = JSONObject(txt)
                    reply = j.optString("reply", txt)
                    saved = j.optString("saved", "")
                    val newSession = j.optString("session", sessionId)
                    if (newSession.isNotBlank() && newSession != sessionId) {
                        sessionId = newSession
                        prefs.edit().putString("session", sessionId).apply()
                    }
                } catch (_: Exception) {
                    reply = if (code in 200..299) txt else "Chat failed ($code)"
                }

                runOnUiThread {
                    if (code in 200..299) {
                        val savedInfo = if (saved.isNotBlank()) " (saved: $saved)" else ""
                        status.text = "🤖 $reply$savedInfo"
                        speak(reply)
                    } else {
                        status.text = "❌ Chat failed ($code): $txt"
                        speak("Request failed.")
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    status.text = "Network error: ${e.message}"
                    speak("Network error.")
                }
            }
        }.start()
    }

    // ---------- Media pick & upload ----------
    private val pickImage =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            if (res.resultCode == Activity.RESULT_OK) res.data?.data?.let { uploadMedia(it, false) }
        }
    private val pickVideo =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            if (res.resultCode == Activity.RESULT_OK) res.data?.data?.let { uploadMedia(it, true) }
        }

    private fun copyToCache(uri: Uri): File? = try {
        val mime = contentResolver.getType(uri) ?: ""
        val ext = when {
            mime.contains("jpeg") -> ".jpg"
            mime.contains("png")  -> ".png"
            mime.contains("webp") -> ".webp"
            mime.contains("mp4")  -> ".mp4"
            mime.contains("quicktime") -> ".mov"
            mime.contains("webm") -> ".webm"
            else -> ""
        }
        val f = File(cacheDir, "upload_${System.currentTimeMillis()}$ext")
        contentResolver.openInputStream(uri).use { inp -> FileOutputStream(f).use { out -> inp?.copyTo(out) } }
        f
    } catch (_: Exception) { null }

    private fun uploadMedia(uri: Uri, isVideo: Boolean) {
        Thread {
            val file = copyToCache(uri)
            if (file == null) {
                runOnUiThread { status.text = "Failed to read media"; speak("Failed to read media.") }
                return@Thread
            }
            try {
                val url = "$BASE/upload"
                val mediaType = if (isVideo) "video/mp4" else "image/jpeg"
                val rb: RequestBody = file.asRequestBody(mediaType.toMediaType())
                val form = MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("file", file.name, rb)
                    .addFormDataPart("kind", "Journal")
                    .addFormDataPart("caption", if (isVideo) "Video from Android" else "Photo from Android")
                    .build()
                val req = Request.Builder().url(url).post(form).build()
                val resp = client.newCall(req).execute()
                val ok = resp.isSuccessful
                runOnUiThread {
                    status.text = if (ok) "✅ Media uploaded" else "❌ Upload failed"
                    speak(if (ok) "Media uploaded." else "Upload failed.")
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = "Upload error: ${e.message}"; speak("Upload error.") }
            } finally {
                file.delete()
            }
        }.start()
    }

    // ---------- Permissions ----------
    private fun ensurePermissions() {
        val toAsk = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            toAsk += Manifest.permission.RECORD_AUDIO
        if (android.os.Build.VERSION.SDK_INT <= 32 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
            toAsk += Manifest.permission.READ_EXTERNAL_STORAGE
        if (toAsk.isNotEmpty()) ActivityCompat.requestPermissions(this, toAsk.toTypedArray(), 101)
    }
}

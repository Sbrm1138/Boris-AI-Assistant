package com.vibe.secondbrain

import android.Manifest
import android.app.Activity
import android.content.Intent
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
import java.util.regex.Pattern

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {

    // 🔧 Set to your Replit URL (NO trailing slash)
    private val BASE = "https://create-replit-borisaiassistant.replit.app"

    private lateinit var status: TextView
    private lateinit var transcript: TextView
    private lateinit var btnTalk: Button
    private lateinit var btnPhoto: Button
    private lateinit var btnVideo: Button

    private lateinit var speech: SpeechRecognizer
    private lateinit var tts: TextToSpeech
    private val client = OkHttpClient()
    private var ttsReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.status)
        transcript = findViewById(R.id.transcript)
        btnTalk = findViewById(R.id.btnPush)
        btnPhoto = findViewById(R.id.btnPhoto)
        btnVideo = findViewById(R.id.btnVideo)

        ensurePermissions()

        tts = TextToSpeech(this, this)
        speech = SpeechRecognizer.createSpeechRecognizer(this)
        speech.setRecognitionListener(recListener)

        btnTalk.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> { if (tts.isSpeaking) tts.stop(); startListening() }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> speech.stopListening()
            }
            true
        }

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
    override fun onInit(code: Int) { ttsReady = code == TextToSpeech.SUCCESS; if (ttsReady) tts.language = Locale.US }
    private fun speak(text: String) { if (ttsReady) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "utt") }

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
            if (!text.isNullOrBlank()) { transcript.text = text; handleUtterance(text) }
            else status.text = "Heard nothing, try again."
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

    // ---------- Intent routing to your backend contract ----------
    private fun handleUtterance(text: String) {
        val t = text.lowercase(Locale.getDefault()).trim()

        val exp = Regex("spent\\s+(\\d+[\\.]?\\d*)\\s+(?:on\\s+)?(\\w+)(?:\\s+note\\s+(.+))?")
        val habit = Regex("^habit\\s+([\\w\\- ]+)\\s+(yes|no|\\d+)")
        val noteTitled = Regex("^note\\s+([^:]+):\\s+(.+)$")
        val noteSimple = Regex("^note\\s+(.+)$")
        val jrnl = Regex("^(jrnl|journal)\\s+(.+)$")

        when {
            exp.containsMatchIn(t) -> {
                val m = exp.find(t)!!
                val amt = m.groupValues[1].toDoubleOrNull() ?: 0.0
                val cat = m.groupValues[2]
                val n = (m.groupValues.getOrNull(3) ?: "").trim()
                sendJson("$BASE/expense", JSONObject().apply {
                    put("amount", amt)
                    put("category", cat.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() })
                    if (n.isNotBlank()) put("note", n)
                }, "Logged expense.", "Failed to log expense.")
            }
            habit.containsMatchIn(t) -> {
                val m = habit.find(t)!!
                val name = m.groupValues[1].trim().replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                val raw = m.groupValues[2].trim().lowercase(Locale.getDefault())
                val status = when {
                    raw in listOf("yes","y","true","done","completed","complete") -> "completed"
                    raw in listOf("no","n","false","skip","skipped") -> "skipped"
                    Pattern.compile("\\d+").matcher(raw).find() -> "${Regex("(\\d+)").find(raw)?.groupValues?.get(1) ?: raw}min"
                    else -> "completed"
                }
                sendJson("$BASE/habit", JSONObject().apply {
                    put("habit", name); put("status", status)
                }, "Habit logged.", "Failed to log habit.")
            }
            noteTitled.containsMatchIn(t) -> {
                val m = noteTitled.find(t)!!
                val title = m.groupValues[1].trim()
                val content = m.groupValues[2].trim()
                sendJson("$BASE/note", JSONObject().apply { put("title", title); put("content", content) },
                    "Note added.", "Failed to add note.")
            }
            noteSimple.containsMatchIn(t) -> {
                val m = noteSimple.find(t)!!
                val content = m.groupValues[1].trim()
                sendJson("$BASE/note", JSONObject().apply { put("title", "Quick Note"); put("content", content) },
                    "Note added.", "Failed to add note.")
            }
            jrnl.containsMatchIn(t) -> {
                val m = jrnl.find(t)!!
                sendJson("$BASE/journal", JSONObject().put("note", m.groupValues[2].trim()),
                    "Journal saved.", "Failed to save journal.")
            }
            else -> {
                sendJson("$BASE/journal", JSONObject().put("note", text.trim()),
                    "Journal saved.", "Failed to save journal.")
            }
        }
    }

    private fun sendJson(url: String, body: JSONObject, okMsg: String, failMsg: String) {
        Thread {
            try {
                val req = Request.Builder()
                    .url(url)
                    .addHeader("Content-Type","application/json")
                    .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()
                val resp = client.newCall(req).execute()
                val ok = resp.isSuccessful
                runOnUiThread {
                    status.text = if (ok) "✅ $okMsg" else "❌ $failMsg"
                    speak(if (ok) okMsg else failMsg)
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = "Network error: ${e.message}"; speak("Network error.") }
            }
        }.start()
    }

    // ---------- Media pick & upload ----------
    private val pickImage = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) res.data?.data?.let { uploadMedia(it, false) }
    }
    private val pickVideo = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
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

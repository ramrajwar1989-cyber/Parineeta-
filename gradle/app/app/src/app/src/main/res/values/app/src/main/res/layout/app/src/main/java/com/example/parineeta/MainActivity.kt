package com.example.parineeta

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView
    private lateinit var confirmBox: LinearLayout
    private lateinit var confirmText: TextView
    private lateinit var keyInput: EditText
    private lateinit var textInput: EditText
    private lateinit var micBtn: Button

    private var tts: TextToSpeech? = null
    private var recognizer: SpeechRecognizer? = null
    private var pending: (() -> Unit)? = null
    private val history = mutableListOf<Pair<String, String>>()
    private val prefs by lazy { getSharedPreferences("parineeta", MODE_PRIVATE) }

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        logView = findViewById(R.id.logView)
        scroll = findViewById(R.id.scroll)
        confirmBox = findViewById(R.id.confirmBox)
        confirmText = findViewById(R.id.confirmText)
        keyInput = findViewById(R.id.keyInput)
        textInput = findViewById(R.id.textInput)
        micBtn = findViewById(R.id.micBtn)

        keyInput.setText(prefs.getString("key", ""))

        findViewById<Button>(R.id.saveKey).setOnClickListener {
            prefs.edit().putString("key", keyInput.text.toString().trim()).apply()
            toast("Key save ho gayi")
        }
        findViewById<Button>(R.id.sendBtn).setOnClickListener {
            val t = textInput.text.toString()
            textInput.setText("")
            handleText(t)
        }
        findViewById<Button>(R.id.yesBtn).setOnClickListener {
            val action = pending
            pending = null
            confirmBox.visibility = View.GONE
            action?.invoke()
        }
        findViewById<Button>(R.id.noBtn).setOnClickListener {
            pending = null
            confirmBox.visibility = View.GONE
            say("Theek hai, ruk gayi.")
        }
        micBtn.setOnClickListener { startListening() }

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale("hi", "IN")
            }
        }

        permLauncher.launch(
            arrayOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.READ_CONTACTS,
                Manifest.permission.CALL_PHONE
            )
        )

        log("Parineeta: Namaste! Mic dabakar bolein, jaise 'Rahul ko call karo'.")
    }

    override fun onDestroy() {
        recognizer?.destroy()
        tts?.shutdown()
        super.onDestroy()
    }

    // ---------- Bolna aur sunna ----------

    private fun startListening() {
        if (!granted(Manifest.permission.RECORD_AUDIO)) {
            permLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            toast("Is phone mein speech recognition nahi hai. Likh kar bhejiye.")
            return
        }
        recognizer?.destroy()
        val r = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                micBtn.text = "Sun rahi hoon..."
            }

            override fun onResults(results: Bundle?) {
                micBtn.text = MIC_IDLE
                val t = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                if (!t.isNullOrBlank()) handleText(t)
            }

            override fun onError(error: Int) {
                micBtn.text = MIC_IDLE
                toast("Sunai nahi diya, dobara bolein")
            }

            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
        r.startListening(i)
    }

    private fun say(text: String) {
        if (text.isBlank()) return
        log("Parineeta: $text")
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "parineeta")
    }

    private fun log(line: String) {
        logView.append(line + "\n\n")
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun granted(perm: String): Boolean =
        ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED

    // ---------- Groq se samajhna ----------

    private fun handleText(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        log("Aap: $t")
        val key = prefs.getString("key", "").orEmpty()
        if (key.isBlank()) {
            say("Pehle upar Groq key daal kar Save dabaiye.")
            return
        }
        Thread {
            try {
                val cmd = askGroq(t, key)
                history.add("user" to t)
                history.add("assistant" to cmd.toString())
                while (history.size > 8) history.removeAt(0)
                runOnUiThread { execute(cmd) }
            } catch (e: Exception) {
                runOnUiThread { say("Kuch gadbad hui: ${e.message}") }
            }
        }.start()
    }

    private fun askGroq(userText: String, key: String): JSONObject {
        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
        for ((role, content) in history.toList()) {
            messages.put(JSONObject().put("role", role).put("content", content))
        }
        messages.put(JSONObject().put("role", "user").put("content", userText))

        val body = JSONObject()
            .put("model", "llama-3.3-70b-versatile")
            .put("temperature", 0.3)
            .put("response_format", JSONObject().put("type", "json_object"))
            .put("messages", messages)

        val conn = URL("https://api.groq.com/openai/v1/chat/completions")
            .openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        conn.doOutput = true
        conn.setRequestProperty("Authorization", "Bearer $key")
        conn.setRequestProperty("Content-Type", "application/json")
        conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream.bufferedReader().use { it.readText() }
        if (code !in 200..299) throw Exception("Groq error $code")

        val content = JSONObject(text)
            .getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .getString("content")
        return JSONObject(content)
    }

    // ---------- Kaam karna ----------

    private fun execute(cmd: JSONObject) {
        val spoken = cmd.optString("reply")
        val message = cmd.optString("message")
        when (cmd.optString("action")) {
            "call" -> {
                val c = findContact(cmd)
                if (c == null) {
                    say("Is naam ka number nahi mila. Contacts check kijiye ya number bolkar dekhiye.")
                    return
                }
                ask("${c.first} (${c.second}) ko call karun?") { call(c.second) }
            }

            "whatsapp" -> {
                val c = findContact(cmd)
                if (c == null) {
                    say("Is naam ka number nahi mila. Contacts check kijiye ya number bolkar dekhiye.")
                    return
                }
                ask("${c.first} ko WhatsApp par ye likh kar khol dun?\n\n$message") {
                    whatsapp(c.second, message)
                }
            }

            "instagram" -> {
                val user = cmd.optString("username").trim().removePrefix("@")
                ask("Message copy karke Instagram khol dun?\n\n$message") {
                    instagram(user, message)
                }
            }

            "open_app" -> {
                val app = cmd.optString("app")
                val intent = findApp(app)
                if (intent == null) {
                    say("$app naam ka app nahi mila.")
                } else {
                    say(spoken.ifBlank { "$app khol rahi hoon." })
                    startActivity(intent)
                }
            }

            else -> say(spoken.ifBlank { "Maine samjha nahi, dobara boliye." })
        }
    }

    private fun ask(prompt: String, action: () -> Unit) {
        pending = action
        confirmText.text = prompt
        confirmBox.visibility = View.VISIBLE
        log("Parineeta: $prompt")
        tts?.speak(prompt.lineSequence().first(), TextToSpeech.QUEUE_FLUSH, null, "parineeta")
    }

    private fun findContact(cmd: JSONObject): Pair<String, String>? {
        val spokenNumber = cmd.optString("number").filter { it.isDigit() }
        if (spokenNumber.length >= 7) return Pair(spokenNumber, spokenNumber)

        if (!granted(Manifest.permission.READ_CONTACTS)) {
            permLauncher.launch(arrayOf(Manifest.permission.READ_CONTACTS))
            return null
        }
        val names = listOf(cmd.optString("name"), cmd.optString("name_alt"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        for (n in names) {
            contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?",
                arrayOf("%$n%"),
                null
            )?.use { c ->
                if (c.moveToFirst()) return Pair(c.getString(0), c.getString(1))
            }
        }
        return null
    }

    private fun call(number: String) {
        val uri = Uri.parse("tel:" + Uri.encode(number))
        val action =
            if (granted(Manifest.permission.CALL_PHONE)) Intent.ACTION_CALL else Intent.ACTION_DIAL
        startActivity(Intent(action, uri))
    }

    private fun waNumber(raw: String): String {
        var d = raw.filter { it.isDigit() }
        if (d.length == 11 && d.startsWith("0")) d = d.substring(1)
        if (d.length == 10) d = "91$d"
        return d
    }

    private fun whatsapp(number: String, message: String) {
        val uri = Uri.parse("https://wa.me/${waNumber(number)}?text=${Uri.encode(message)}")
        val view = Intent(Intent.ACTION_VIEW, uri)
        try {
            startActivity(Intent(view).setPackage("com.whatsapp"))
            say("WhatsApp khul gaya. Bhejne ke liye send dabaiye.")
        } catch (e: ActivityNotFoundException) {
            try {
                startActivity(view)
            } catch (e2: ActivityNotFoundException) {
                say("WhatsApp nahi mila.")
            }
        }
    }

    private fun instagram(username: String, message: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("message", message))
        val intent: Intent? = if (username.isNotBlank()) {
            Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://instagram.com/_u/" + Uri.encode(username))
            ).setPackage("com.instagram.android")
        } else {
            packageManager.getLaunchIntentForPackage("com.instagram.android")
        }
        try {
            if (intent == null) throw ActivityNotFoundException()
            startActivity(intent)
            say("Instagram khul gaya. Message copy ho chuka hai, chat mein paste karke bhej dijiye.")
        } catch (e: ActivityNotFoundException) {
            say("Instagram app nahi mila.")
        }
    }

    private fun findApp(query: String): Intent? {
        val q = query.lowercase().trim()
        if (q.isEmpty()) return null
        val pm = packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(main, 0)
        fun label(i: Int) = apps[i].loadLabel(pm).toString().lowercase()
        val exact = apps.indices.firstOrNull { label(it) == q }
        val loose = apps.indices.firstOrNull {
            val l = label(it)
            l.contains(q) || (l.length >= 3 && q.contains(l))
        }
        val idx = exact ?: loose ?: return null
        return pm.getLaunchIntentForPackage(apps[idx].activityInfo.packageName)
    }

    companion object {
        private const val MIC_IDLE = "Bolo (mic dabaiye)"

        private val SYSTEM_PROMPT = """
Tum Parineeta ho, ek friendly, pyaari AI voice assistant jo user ke phone par kaam karwati hai. User Hindi, Hinglish ya English mein bolta hai. Hamesha SIRF ek JSON object lautao, aur kuch nahi, in keys ke saath:
{"action": "call" | "whatsapp" | "instagram" | "open_app" | "chat",
 "name": "contact ka naam jaisa bola gaya",
 "name_alt": "wahi naam doosri lipi mein (Devanagari bola to Roman, Roman bola to Devanagari)",
 "number": "agar user ne phone number bola to sirf ank, warna khali",
 "username": "Instagram username agar bola ho, warna khali",
 "message": "bhejne wala message, user ke shabdon mein aur usi bhasha mein",
 "app": "app ka naam English mein, jaisa phone par dikhta hai, jaise YouTube, Camera, Settings",
 "reply": "ek chhota friendly jawab user ki bhasha mein, jo bol kar sunaya jayega"}
Niyam: kisi ko call karne ko kahe to action call. WhatsApp par message bhejne ko kahe to action whatsapp. Instagram par message bhejne ko kahe to action instagram. Koi app kholne ko kahe to action open_app. Baaki sab baat-cheet ke liye action chat, aur reply mein jawab do. Jo keys kaam ki na ho unhe khali string rakho.
""".trimIndent()
    }
}

package sk.nas.shareuploader

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

/** Receives Gallery/Chrome/other app shares, uploads over SMB, then offers Telegram. */
class MainActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private val pending = mutableListOf<IncomingItem>()
    private val completed = mutableListOf<SmbUploader.UploadedFile>()
    private var uploading = false
    private var lastProgressAt = 0L

    private lateinit var destination: TextView
    private lateinit var incoming: TextView
    private lateinit var status: TextView
    private lateinit var uploaded: TextView
    private lateinit var progress: ProgressBar
    private lateinit var btnChoose: Button
    private lateinit var btnSettings: ImageButton
    private lateinit var btnUpload: Button
    private lateinit var btnTelegram: Button
    private lateinit var btnCopy: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        destination = findViewById(R.id.txtDestination)
        incoming = findViewById(R.id.txtIncoming)
        status = findViewById(R.id.txtStatus)
        uploaded = findViewById(R.id.txtUploaded)
        progress = findViewById(R.id.progressUpload)
        btnChoose = findViewById(R.id.btnChoose)
        btnSettings = findViewById(R.id.btnSettings)
        btnUpload = findViewById(R.id.btnUpload)
        btnTelegram = findViewById(R.id.btnTelegram)
        btnCopy = findViewById(R.id.btnCopy)

        btnSettings.setOnClickListener { if (!uploading) launchSettings() }
        findViewById<Button>(R.id.btnOpenFolder).setOnClickListener {
            if (!uploading) startActivity(Intent(this, FolderActivity::class.java))
        }
        btnChoose.setOnClickListener { if (!uploading) openPicker() }
        btnUpload.setOnClickListener { beginUpload() }
        btnTelegram.setOnClickListener { sendToTelegram() }
        btnCopy.setOnClickListener { copyAddresses() }
        render()
        if (savedInstanceState == null) receiveShare(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        receiveShare(intent)
    }

    private fun receiveShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND && intent?.action != Intent.ACTION_SEND_MULTIPLE) return
        if (uploading) {
            Toast.makeText(this, "Finish the current upload before sharing more files.", Toast.LENGTH_LONG).show()
            return
        }
        val parsed = IncomingShare.fromIntent(this, intent)
        if (parsed.isEmpty()) {
            status.text = "Nothing readable was shared. Try a file, image or text."
            return
        }
        replaceQueue(parsed)
    }

    private fun replaceQueue(items: List<IncomingItem>) {
        pending.clear()
        pending.addAll(items)
        completed.clear()
        progress.isIndeterminate = false
        progress.progress = 0
        status.text = "Received ${items.size} item(s)."
        render()
        val settings = try { SettingsStore(this).load() } catch (_: Exception) { NasSettings() }
        if (settings.isReady()) beginUpload() else launchSettings()
    }

    private fun launchSettings() {
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(this, SettingsActivity::class.java), REQUEST_SETTINGS)
    }

    private fun openPicker() {
        val picker = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(picker, REQUEST_FILES)
        } catch (_: ActivityNotFoundException) {
            status.text = "No document picker is available on this device."
        }
    }

    @Deprecated("Uses the stable Android Activity result API without requiring AndroidX")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_SETTINGS) {
            render()
            if (resultCode == RESULT_OK && pending.isNotEmpty()) beginUpload()
        } else if (requestCode == REQUEST_FILES && resultCode == RESULT_OK && data != null) {
            val uris = linkedSetOf<Uri>()
            data.data?.let { uris.add(it) }
            data.clipData?.let { clip ->
                for (n in 0 until clip.itemCount) clip.getItemAt(n).uri?.let { uris.add(it) }
            }
            if (uris.isNotEmpty()) replaceQueue(IncomingShare.fromPickedUris(this, uris))
        }
    }

    private fun beginUpload() {
        if (uploading || pending.isEmpty()) return
        val cfg = try { SettingsStore(this).load() } catch (e: Exception) {
            status.text = SmbUploader.readableError(e)
            launchSettings()
            return
        }
        cfg.validate()?.let { status.text = it; launchSettings(); return }
        val batch = pending.toList()
        uploading = true
        lastProgressAt = 0
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        status.text = "Connecting to ${cfg.host}…"
        progress.isIndeterminate = true
        render()
        executor.execute {
            val outcome = SmbUploader(applicationContext).upload(cfg, batch) { name, index, total, bytes, expected ->
                val now = SystemClock.elapsedRealtime()
                if (now - lastProgressAt >= 120L || expected == bytes) {
                    lastProgressAt = now
                    runOnUiThread {
                        val readable = if (expected != null && expected > 0) {
                            "${bytes / 1024} / ${expected / 1024} KiB"
                        } else "${bytes / 1024} KiB"
                        status.text = "Uploading $index/$total: $name ($readable)"
                        progress.isIndeterminate = expected == null || expected <= 0L
                        if (!progress.isIndeterminate) progress.progress =
                            ((bytes * 100.0 / expected!!).toInt()).coerceIn(0, 100)
                    }
                }
            }
            runOnUiThread {
                uploading = false
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                completed.addAll(outcome.uploaded)
                repeat(outcome.uploaded.size) { pending.removeAt(0) }
                progress.isIndeterminate = false
                progress.progress = if (outcome.error == null) 100 else 0
                status.text = if (outcome.error == null) {
                    "Upload complete: ${completed.size} item(s) saved to NAS."
                } else {
                    "Upload stopped: ${outcome.error}\n${completed.size} item(s) saved. Tap Upload / retry for remaining items."
                }
                render()
                if (outcome.error == null && cfg.offerTelegram && completed.isNotEmpty()) sendToTelegram()
            }
        }
    }

    private fun addressMessage(): String = buildString {
        append(if (completed.size == 1) "NAS file:\n" else "NAS files:\n")
        for (file in completed) {
            if (completed.size > 1) append(file.original).append("\n")
            append(file.shareAddress()).append("\n")
        }
    }.trimEnd()

    private fun sendToTelegram() {
        if (completed.isEmpty()) return
        val msg = addressMessage()
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, msg)
        }
        // Android/Telegram does not provide an official API to force a personal Saved Messages chat.
        // Telegram opens to let the user select Saved Messages as the recipient.
        for (pkg in listOf("org.telegram.messenger", "org.thunderdog.challegram", "org.telegram.messenger.web")) {
            try {
                startActivity(Intent(shareIntent).setPackage(pkg))
                return
            } catch (_: ActivityNotFoundException) { }
        }
        startActivity(Intent.createChooser(shareIntent, "Share NAS addresses"))
    }

    private fun copyAddresses() {
        if (completed.isEmpty()) return
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("NAS addresses", addressMessage()))
        Toast.makeText(this, "NAS addresses copied", Toast.LENGTH_SHORT).show()
    }

    private fun render() {
        val settings = try { SettingsStore(this).load() } catch (_: Exception) { NasSettings() }
        destination.text = if (settings.host.isBlank()) "NAS not configured — open Settings" else
            "Destination: ${settings.uncDirectory()}"
        incoming.text = if (pending.isEmpty()) {
            "Open Gallery, Chrome or another app, tap Share, then choose Send to NAS."
        } else {
            pending.take(8).joinToString("\n") { "• ${it.displayName}" } +
                if (pending.size > 8) "\n… and ${pending.size - 8} more" else ""
        }
        uploaded.text = if (completed.isEmpty()) "" else {
            "Uploaded:\n" + completed.takeLast(20).joinToString("\n") { it.shareAddress() } +
                if (completed.size > 20) "\n… and ${completed.size - 20} earlier" else ""
        }
        btnSettings.isEnabled = !uploading
        btnChoose.isEnabled = !uploading
        btnUpload.visibility = if (!uploading && pending.isNotEmpty()) View.VISIBLE else View.GONE
        btnTelegram.visibility = if (!uploading && completed.isNotEmpty()) View.VISIBLE else View.GONE
        btnCopy.visibility = if (!uploading && completed.isNotEmpty()) View.VISIBLE else View.GONE
    }

    override fun onDestroy() {
        // The worker may finish an in-progress transfer. Uploads are tied to this
        // activity, so the user should leave the upload screen open until completion.
        if (!uploading) executor.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val REQUEST_SETTINGS = 200
        private const val REQUEST_FILES = 201
    }
}

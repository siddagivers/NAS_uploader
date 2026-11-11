package sk.nas.shareuploader

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.webkit.MimeTypeMap
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

/** Browse only the configured destination and open downloaded copies through Android. */
class FolderActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val segments = mutableListOf<String>()
    private var entries = emptyList<SmbUploader.Entry>()
    private var busy = false
    private lateinit var status: TextView
    private lateinit var list: ListView
    private lateinit var up: Button
    private lateinit var refresh: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        segments.addAll(savedInstanceState?.getStringArrayList("segments").orEmpty())
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
        }
        status = TextView(this).apply { textSize = 16f }
        up = Button(this).apply { text = "Up one folder"; setOnClickListener {
            if (!busy && segments.isNotEmpty()) { segments.removeAt(segments.lastIndex); load() }
        } }
        refresh = Button(this).apply { text = "Refresh"; setOnClickListener { if (!busy) load() } }
        list = ListView(this)
        layout.addView(status)
        layout.addView(up)
        layout.addView(refresh)
        layout.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(layout)
        list.setOnItemClickListener { _, _, position, _ ->
            if (!busy) {
                val entry = entries[position]
                if (entry.directory) { segments.add(entry.name); load() } else open(entry)
            }
        }
        load()
    }

    private fun setBusy(value: Boolean) {
        busy = value
        list.isEnabled = !value
        up.isEnabled = !value && segments.isNotEmpty()
        refresh.isEnabled = !value
    }

    private fun load() {
        setBusy(true)
        status.text = "Loading NAS folder…"
        val path = segments.toList()
        worker.execute {
            val result = runCatching {
                val cfg = SettingsStore(applicationContext).load()
                Pair(cfg.uncDirectory() + if (path.isEmpty()) "" else "\\" + path.joinToString("\\"),
                    SmbUploader(applicationContext).listFolder(cfg, path))
            }
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                entries = result.getOrNull()?.second.orEmpty()
                list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1,
                    entries.map { if (it.directory) "📁 ${it.name}/" else "${it.name}  (${it.size / 1024} KiB)" })
                status.text = result.fold({ "${it.first}\n${it.second.size} item(s)" },
                    { "Cannot list folder: ${SmbUploader.readableError(it)}. Check NAS settings and connection." })
                setBusy(false)
            }
        }
    }

    private fun open(entry: SmbUploader.Entry) {
        setBusy(true)
        status.text = "Downloading ${entry.name}…"
        val path = segments.toList()
        worker.execute {
            val result = runCatching {
                val cfg = SettingsStore(applicationContext).load()
                val directory = File(cacheDir, "opened/${UUID.randomUUID()}").apply { check(mkdirs()) }
                val file = File(directory, FileNaming.sanitize(entry.name))
                try { SmbUploader(applicationContext).download(cfg, path, entry.name, file) }
                catch (e: Exception) { file.delete(); directory.delete(); throw e }
                file
            }
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                setBusy(false)
                result.fold({ file ->
                    val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
                    val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase())
                        ?: "application/octet-stream"
                    try {
                        startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, mime)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }, "Open ${entry.name}"))
                        status.text = "Downloaded ${entry.name}. Choose an app to open it."
                    } catch (_: ActivityNotFoundException) {
                        status.text = "No installed app can open this file type."
                    }
                }, { status.text = "Cannot open file: ${SmbUploader.readableError(it)}" })
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putStringArrayList("segments", ArrayList(segments))
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }
}

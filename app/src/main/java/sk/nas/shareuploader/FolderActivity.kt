package sk.nas.shareuploader

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.core.content.FileProvider
import java.io.File
import java.security.MessageDigest
import java.util.Collections
import java.util.UUID
import java.util.concurrent.Executors

/** SMB browser. View modes share the same listing and use bounded thumbnail downloads. */
class FolderActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val thumbnailWorkers = Executors.newFixedThreadPool(2)
    private val segments = mutableListOf<String>()
    private var entries = emptyList<SmbUploader.Entry>()
    private var busy = false
    private var gridMode = true
    private var folderIdentity = ""
    private lateinit var status: TextView
    private lateinit var list: ListView
    private lateinit var grid: GridView
    private lateinit var up: Button
    private lateinit var refresh: Button
    private lateinit var toggle: Button
    private var pendingApk: File? = null
    private val inFlight = Collections.synchronizedSet(mutableSetOf<String>())
    private val unavailable = Collections.synchronizedSet(mutableSetOf<String>())
    private val thumbnails = object : LruCache<String, Bitmap>(16 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = maxOf(1, value.byteCount / 1024)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        segments.addAll(savedInstanceState?.getStringArrayList("segments").orEmpty())
        pendingApk = savedInstanceState?.getString("pendingApkPath")?.let(::File)
        gridMode = savedInstanceState?.getBoolean("gridMode")
            ?: getSharedPreferences("browser", MODE_PRIVATE).getBoolean("gridMode", true)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(Color.rgb(242, 246, 250))
        }
        val heading = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        heading.addView(TextView(this).apply {
            text = "NAS files"
            textSize = 24f
            setTextColor(Color.rgb(23, 58, 89))
            setTypeface(null, android.graphics.Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        toggle = Button(this).apply { setOnClickListener { changeView() } }
        heading.addView(toggle)
        root.addView(heading)

        status = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.rgb(64, 83, 103))
            setPadding(0, dp(4), 0, dp(9))
            maxLines = 3
        }
        root.addView(status)
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        up = Button(this).apply {
            text = "Up"
            setOnClickListener {
                if (!busy && segments.isNotEmpty()) {
                    segments.removeAt(segments.lastIndex)
                    load()
                }
            }
        }
        refresh = Button(this).apply {
            text = "Refresh"
            setOnClickListener { if (!busy) load() }
        }
        actions.addView(up, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        actions.addView(refresh, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(actions)

        grid = GridView(this).apply {
            numColumns = GridView.AUTO_FIT
            columnWidth = dp(130)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            horizontalSpacing = dp(8)
            verticalSpacing = dp(10)
            setPadding(0, dp(8), 0, 0)
            clipToPadding = false
            setOnItemClickListener { _, _, position, _ -> select(entries[position]) }
        }
        list = ListView(this).apply {
            dividerHeight = dp(1)
            setOnItemClickListener { _, _, position, _ -> select(entries[position]) }
        }
        root.addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        updateViewMode()
        load()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun changeView() {
        gridMode = !gridMode
        getSharedPreferences("browser", MODE_PRIVATE).edit().putBoolean("gridMode", gridMode).apply()
        updateViewMode()
    }

    private fun updateViewMode() {
        toggle.text = if (gridMode) "☰ List" else "▦ Icons"
        grid.visibility = if (gridMode) View.VISIBLE else View.GONE
        list.visibility = if (gridMode) View.GONE else View.VISIBLE
        grid.adapter = FileAdapter(true)
        list.adapter = FileAdapter(false)
    }

    private fun select(entry: SmbUploader.Entry) {
        if (busy) return
        if (entry.directory) {
            segments.add(entry.name)
            load()
        } else open(entry)
    }

    private fun setBusy(value: Boolean) {
        busy = value
        list.isEnabled = !value
        grid.isEnabled = !value
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
                folderIdentity = result.getOrNull()?.first.orEmpty()
                unavailable.clear()
                updateViewMode()
                status.text = result.fold(
                    { it.first + "\n" + it.second.size + " item(s)" },
                    { "Cannot list folder: " + SmbUploader.readableError(it) + ". Check NAS settings and connection." }
                )
                setBusy(false)
            }
        }
    }

    private inner class FileAdapter(private val tile: Boolean) : BaseAdapter() {
        override fun getCount() = entries.size
        override fun getItem(position: Int): Any = entries[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, recycled: View?, parent: ViewGroup): View {
            val entry = entries[position]
            val box = LinearLayout(this@FolderActivity).apply {
                orientation = if (tile) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
                gravity = if (tile) Gravity.CENTER_HORIZONTAL else Gravity.CENTER_VERTICAL
                setPadding(dp(5), dp(5), dp(5), dp(5))
                if (tile) setBackgroundColor(Color.WHITE)
            }
            val picture = FrameLayout(this@FolderActivity).apply {
                setBackgroundColor(Color.rgb(226, 235, 244))
            }
            val icon = TextView(this@FolderActivity).apply {
                text = symbol(entry)
                textSize = if (tile) 38f else 30f
                gravity = Gravity.CENTER
            }
            val image = ImageView(this@FolderActivity).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                visibility = View.GONE
                contentDescription = "Preview of " + entry.name
            }
            picture.addView(icon, FrameLayout.LayoutParams(-1, -1))
            picture.addView(image, FrameLayout.LayoutParams(-1, -1))
            box.addView(picture, if (tile) LinearLayout.LayoutParams(-1, dp(115))
                else LinearLayout.LayoutParams(dp(64), dp(64)))
            val words = LinearLayout(this@FolderActivity).apply {
                orientation = LinearLayout.VERTICAL
                if (!tile) setPadding(dp(12), 0, 0, 0)
            }
            words.addView(TextView(this@FolderActivity).apply {
                text = entry.name
                maxLines = if (tile) 2 else 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                textSize = if (tile) 12f else 15f
                setTextColor(Color.rgb(29, 54, 77))
            })
            words.addView(TextView(this@FolderActivity).apply {
                text = if (entry.directory) "Folder" else readableSize(entry.size)
                textSize = 11f
                setTextColor(Color.rgb(105, 121, 137))
            })
            box.addView(words, if (tile) LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(6)
            } else LinearLayout.LayoutParams(0, -2, 1f))
            box.minimumHeight = dp(if (tile) 175 else 78)
            if (!entry.directory && previewKind(entry.name) != null) {
                requestThumbnail(entry, image, icon)
            }
            return box
        }
    }

    private fun readableSize(bytes: Long): String = when {
        bytes < 1024L -> bytes.toString() + " B"
        bytes < 1048576L -> (bytes / 1024L).toString() + " KB"
        bytes < 1073741824L -> (bytes / 1048576L).toString() + " MB"
        else -> String.format(java.util.Locale.ROOT, "%.1f GB", bytes / 1073741824.0)
    }

    private fun previewKind(name: String): String? {
        return when (name.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)) {
            "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif" -> "image"
            "mp4", "m4v", "mov", "3gp", "mkv", "webm", "avi" -> "video"
            "pdf" -> "pdf"
            else -> null
        }
    }

    private fun symbol(entry: SmbUploader.Entry): String {
        if (entry.directory) return "📁"
        return when (previewKind(entry.name)) {
            "image" -> "🖼️"
            "video" -> "🎬"
            "pdf" -> "📕"
            else -> when (entry.name.substringAfterLast('.', "").lowercase()) {
                "txt", "md", "log" -> "📝"
                "zip", "7z", "rar", "tar", "gz" -> "🗜️"
                "mp3", "wav", "flac", "ogg" -> "🎵"
                "doc", "docx", "odt" -> "📄"
                "xls", "xlsx", "csv" -> "📊"
                else -> "📄"
            }
        }
    }

    private fun thumbnailKey(entry: SmbUploader.Entry): String {
        val identifier = folderIdentity + "/" + entry.name + "/" + entry.size
        return MessageDigest.getInstance("SHA-256")
            .digest(identifier.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun requestThumbnail(entry: SmbUploader.Entry, image: ImageView, placeholder: TextView) {
        val key = thumbnailKey(entry)
        image.tag = key
        fun show(bitmap: Bitmap) {
            if (image.tag == key) {
                image.setImageBitmap(bitmap)
                image.visibility = View.VISIBLE
                placeholder.visibility = View.GONE
            }
        }
        thumbnails.get(key)?.let { show(it); return }
        if (unavailable.contains(key)) return
        val cachedFile = File(cacheDir, "nas-thumbnails/" + key + ".jpg")
        if (!inFlight.add(key)) return
        val path = segments.toList()
        val kind = previewKind(entry.name) ?: return
        thumbnailWorkers.execute {
            try {
                var bitmap = if (cachedFile.isFile) decodeSampled(cachedFile, 320) else null
                if (bitmap == null) {
                    val limit = when (kind) {
                        "video" -> 48L * 1024L * 1024L
                        "pdf" -> 16L * 1024L * 1024L
                        else -> 24L * 1024L * 1024L
                    }
                    if (entry.size > limit) {
                        unavailable.add(key)
                        return@execute
                    }
                    val temporary = File(cacheDir, "nas-thumbnails/" + key + ".download")
                    temporary.parentFile?.mkdirs()
                    try {
                        val settings = SettingsStore(applicationContext).load()
                        SmbUploader(applicationContext).downloadSmallFile(
                            settings, path, entry.name, temporary, limit)
                        bitmap = when (kind) {
                            "image" -> decodeSampled(temporary, 320)
                            "video" -> videoFrame(temporary)
                            "pdf" -> pdfCover(temporary)
                            else -> null
                        }
                        bitmap?.let { generated ->
                            cachedFile.outputStream().use { stream ->
                                generated.compress(Bitmap.CompressFormat.JPEG, 82, stream)
                            }
                        }
                    } finally {
                        temporary.delete()
                    }
                }
                if (bitmap == null) unavailable.add(key)
                else {
                    thumbnails.put(key, bitmap)
                    val ready = bitmap
                    runOnUiThread { if (!isDestroyed && !isFinishing) show(ready) }
                }
            } catch (_: Exception) {
                unavailable.add(key)
            } finally {
                inFlight.remove(key)
            }
        }
    }

    private fun decodeSampled(file: File, maxSize: Int): Bitmap? {
        val info = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, info)
        if (info.outWidth < 1 || info.outHeight < 1) return null
        var sample = 1
        while (info.outWidth / sample > maxSize * 2 || info.outHeight / sample > maxSize * 2) sample *= 2
        return BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        })
    }

    private fun videoFrame(file: File): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val frame = retriever.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            frame?.let {
                val width = it.width.coerceAtLeast(1)
                val height = it.height.coerceAtLeast(1)
                val scale = 320.0 / maxOf(width, height)
                if (scale >= 1.0) it else Bitmap.createScaledBitmap(
                    it, maxOf(1, (width * scale).toInt()), maxOf(1, (height * scale).toInt()), true)
            }
        } finally {
            retriever.release()
        }
    }

    private fun pdfCover(file: File): Bitmap? {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { pdf ->
                if (pdf.pageCount < 1) return null
                pdf.openPage(0).use { page ->
                    val scale = 320f / maxOf(page.width, page.height)
                    val result = Bitmap.createBitmap(
                        maxOf(1, (page.width * scale).toInt()),
                        maxOf(1, (page.height * scale).toInt()), Bitmap.Config.ARGB_8888)
                    result.eraseColor(Color.WHITE)
                    page.render(result, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    return result
                }
            }
        }
    }

    private fun open(entry: SmbUploader.Entry) {
        setBusy(true)
        status.text = "Downloading " + entry.name + "…"
        val path = segments.toList()
        worker.execute {
            val result = runCatching {
                val cfg = SettingsStore(applicationContext).load()
                val directory = File(cacheDir, "opened/" + UUID.randomUUID()).apply { check(mkdirs()) }
                val file = File(directory, FileNaming.sanitize(entry.name))
                try { SmbUploader(applicationContext).download(cfg, path, entry.name, file) }
                catch (e: Exception) { file.delete(); directory.delete(); throw e }
                file
            }
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                setBusy(false)
                result.fold({ file ->
                    if (file.extension.equals("apk", ignoreCase = true)) {
                        installApk(file)
                    } else {
                        openWithApp(file, entry.name)
                    }
                }, { status.text = "Cannot open file: " + SmbUploader.readableError(it) })
            }
        }
    }

    private fun openWithApp(file: File, name: String) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
            val mime = MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(file.extension.lowercase(java.util.Locale.ROOT))
                ?: "application/octet-stream"
            val view = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                clipData = ClipData.newRawUri(file.name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(view, "Open $name"))
            status.text = "Downloaded $name. Choose an app to open it."
        } catch (_: ActivityNotFoundException) {
            status.text = "No installed app can open this file type."
        } catch (error: Exception) {
            status.text = "Cannot open $name: " + SmbUploader.readableError(error)
        }
    }

    /** APK files need a dedicated install intent and permission for this source. */
    private fun installApk(file: File) {
        if (!file.isFile) {
            status.text = "Downloaded APK is no longer available. Reopen it from the NAS."
            pendingApk = null
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !packageManager.canRequestPackageInstalls()) {
            pendingApk = file
            AlertDialog.Builder(this)
                .setTitle("Allow APK installation")
                .setMessage(
                    "Android requires separate permission to install APKs from Send to NAS. " +
                    "Open settings, enable Allow from this source, then return here. " +
                    "Telegram has its own independent permission."
                )
                .setPositiveButton("Open settings") { _, _ ->
                    try {
                        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                            data = Uri.parse("package:$packageName")
                        }
                        @Suppress("DEPRECATION")
                        startActivityForResult(intent, REQUEST_INSTALL_SOURCE)
                        status.text = "Allow APK installation from Send to NAS, then return."
                    } catch (_: ActivityNotFoundException) {
                        pendingApk = null
                        status.text = "Open Android Settings > Apps > Special app access > " +
                            "Install unknown apps > Send to NAS, then try opening the APK again."
                    } catch (error: SecurityException) {
                        pendingApk = null
                        status.text = "Cannot open installation permissions: " +
                            SmbUploader.readableError(error)
                    }
                }
                .setNegativeButton("Cancel") { _, _ -> pendingApk = null }
                .show()
            return
        }
        pendingApk = null
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
            val install = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                data = uri
                clipData = ClipData.newRawUri(file.name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                startActivity(install)
                status.text = "Opened Android installer for ${file.name}. Confirm installation there."
            } catch (_: ActivityNotFoundException) {
                // Older/custom Android builds may only handle the APK MIME ACTION_VIEW.
                val view = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    clipData = ClipData.newRawUri(file.name, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(view)
                status.text = "Opened Android installer for ${file.name}. Confirm installation there."
            }
        } catch (_: ActivityNotFoundException) {
            status.text = "No Android package installer is available for APK files."
        } catch (error: SecurityException) {
            status.text = "Android blocked installation: " + SmbUploader.readableError(error) +
                ". Check permission under Install unknown apps > Send to NAS."
        } catch (error: Exception) {
            status.text = "Could not start installer: " + SmbUploader.readableError(error)
        }
    }

    @Deprecated("System settings return via the Activity result callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_INSTALL_SOURCE) return
        val file = pendingApk
        pendingApk = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !packageManager.canRequestPackageInstalls()) {
            status.text = "Installation is still blocked. Enable Allow from this source " +
                "for Send to NAS, then open the APK again."
        } else if (file?.isFile == true) {
            installApk(file)
        } else {
            status.text = "Please reopen the APK from your NAS."
        }
    }

    companion object {
        private const val REQUEST_INSTALL_SOURCE = 410
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("pendingApkPath", pendingApk?.absolutePath)
        outState.putStringArrayList("segments", ArrayList(segments))
        outState.putBoolean("gridMode", gridMode)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        worker.shutdown()
        thumbnailWorkers.shutdownNow()
        super.onDestroy()
    }
}

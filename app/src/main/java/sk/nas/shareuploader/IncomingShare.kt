package sk.nas.shareuploader

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap

sealed class IncomingItem {
    abstract val displayName: String
    data class UriFile(val uri: Uri, override val displayName: String, val sizeBytes: Long?) : IncomingItem()
    data class SharedText(val content: String, override val displayName: String) : IncomingItem()
}

object IncomingShare {
    fun fromIntent(context: Context, intent: Intent): List<IncomingItem> {
        val uris = linkedSetOf<Uri>()
        if (intent.action == Intent.ACTION_SEND || intent.action == Intent.ACTION_SEND_MULTIPLE) {
            val single: Uri? = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                (intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)
            }
            single?.let { uris.add(it) }
            val multi: ArrayList<Uri>? = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
            }
            multi?.let { uris.addAll(it) }
            intent.clipData?.let { clip ->
                for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { uris.add(it) }
            }
        }
        val items = mutableListOf<IncomingItem>()
        for (uri in uris) {
            val (name, size) = describeUri(context, uri, intent.type)
            items.add(IncomingItem.UriFile(uri, name, size))
        }

        // Browsers usually send a link through EXTRA_TEXT; messages send their body.
        // Stream plus caption -> save both the file(s) and the caption as a .txt file.
        val sharedText = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.takeIf { it.isNotBlank() }
        val htmlText = intent.getStringExtra(Intent.EXTRA_HTML_TEXT)?.takeIf { it.isNotBlank() }
        val text = sharedText ?: htmlText?.let {
            android.text.Html.fromHtml(it, android.text.Html.FROM_HTML_MODE_LEGACY).toString()
        } ?: intent.clipData?.let { clip ->
            if (uris.isEmpty() && clip.itemCount == 1) clip.getItemAt(0).text?.toString() else null
        }
        if (!text.isNullOrBlank()) {
            val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)?.trim().orEmpty()
            val body = if (subject.isEmpty() || text.trim() == subject) text else "$subject\n\n$text"
            val isLink = Regex("(?i)^\\s*https?://").containsMatchIn(text)
            items.add(IncomingItem.SharedText(body, if (isLink) "shared_link.txt" else "shared_text.txt"))
        }
        return items
    }

    fun fromPickedUris(context: Context, uris: Collection<Uri>): List<IncomingItem> =
        uris.distinct().map { uri ->
            val (name, size) = describeUri(context, uri, context.contentResolver.getType(uri))
            IncomingItem.UriFile(uri, name, size)
        }

    private fun describeUri(context: Context, uri: Uri, mime: String?): Pair<String, Long?> {
        var name: String? = null
        var size: Long? = null
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val s = c.getColumnIndex(OpenableColumns.SIZE)
                    if (n >= 0 && !c.isNull(n)) name = c.getString(n)
                    if (s >= 0 && !c.isNull(s)) size = c.getLong(s).takeIf { it >= 0L }
                }
            }
        } catch (_: Exception) { /* Some content providers do not expose metadata. */ }
        val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "bin"
        return Pair(FileNaming.sanitize(name ?: "shared.$extension"), size)
    }
}

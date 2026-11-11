package sk.nas.shareuploader

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** Utilities that do not depend on Android, usable in unit tests. */
object FileNaming {
    private val illegal = Regex("[<>:\"/\\\\|?*\\x00-\\x1F]")
    private val shareOrFolderIllegal = Regex("[<>:\"/\\\\|?*\\x00-\\x1F]")

    fun sanitize(raw: String): String {
        var name = raw.substringAfterLast('/').substringAfterLast('\\')
            .replace(illegal, "_").trim().trimEnd('.', ' ')
        if (name.isEmpty() || name == "." || name == "..") name = "file"
        if (name.length > 120) {
            val ext = name.substringAfterLast('.', "").take(12)
            name = name.take(120 - ext.length - if (ext.isEmpty()) 0 else 1) +
                if (ext.isEmpty()) "" else ".$ext"
        }
        return name
    }

    fun isValidSegment(value: String): Boolean =
        value.isNotBlank() && value.trim() == value && value != "." && value != ".." &&
            !shareOrFolderIllegal.containsMatchIn(value) && !value.endsWith('.')

    fun validFolder(value: String): Boolean = value.isBlank() ||
        value.replace('/', '\\').split('\\').all { isValidSegment(it) }

    fun uniqueName(original: String): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        return "${stamp}_${UUID.randomUUID().toString().take(6)}_${sanitize(original)}"
    }

    fun link(baseUrl: String, fileName: String, encoder: (String) -> String): String? =
        baseUrl.takeIf { it.isNotBlank() }?.trimEnd('/')?.plus("/")?.plus(encoder(fileName))
}

package sk.nas.shareuploader

import android.content.Context
import android.net.Uri
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.EnumSet
import java.util.concurrent.TimeUnit

/** All methods are blocking: call only from a worker thread. */
class SmbUploader(private val context: Context) {
    data class Entry(val name: String, val directory: Boolean, val size: Long)

    private fun folderPath(settings: NasSettings, segments: List<String>): String {
        require(segments.all { FileNaming.isValidSegment(it) }) { "Invalid folder path" }
        return (listOf(settings.folder.replace('/', '\\').trim('\\')) + segments)
            .filter { it.isNotBlank() }.joinToString("\\")
    }

    fun listFolder(settings: NasSettings, segments: List<String>): List<Entry> = withShare(settings) { share ->
        share.list(folderPath(settings, segments)).filter { it.fileName != "." && it.fileName != ".." }
            .map { Entry(it.fileName,
                it.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value != 0L, it.endOfFile) }
            .sortedWith(compareBy<Entry> { !it.directory }.thenBy { it.name.lowercase() })
    }

    fun download(settings: NasSettings, segments: List<String>, name: String, output: java.io.File) {
        require(FileNaming.isValidSegment(name)) { "Invalid file name" }
        withShare(settings) { share ->
            val path = folderPath(settings, segments + name)
            share.openFile(path, EnumSet.of(AccessMask.GENERIC_READ), null,
                EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ), SMB2CreateDisposition.FILE_OPEN,
                EnumSet.noneOf(SMB2CreateOptions::class.java)).use { remote ->
                remote.inputStream.use { source -> output.outputStream().use { source.copyTo(it) } }
            }
        }
    }

    /** Download a bounded source file for a local thumbnail. Avoids transferring huge videos. */
    fun downloadSmallFile(
        settings: NasSettings,
        segments: List<String>,
        name: String,
        output: java.io.File,
        maximumBytes: Long
    ) {
        require(FileNaming.isValidSegment(name)) { "Invalid file name" }
        require(maximumBytes > 0L) { "Invalid thumbnail size limit" }
        try {
            withShare(settings) { share ->
                val path = folderPath(settings, segments + name)
                share.openFile(path, EnumSet.of(AccessMask.GENERIC_READ), null,
                    EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ), SMB2CreateDisposition.FILE_OPEN,
                    EnumSet.noneOf(SMB2CreateOptions::class.java)).use { remote ->
                    remote.inputStream.use { input ->
                        output.outputStream().use { destination ->
                            val buffer = ByteArray(64 * 1024)
                            var written = 0L
                            while (true) {
                                val count = input.read(buffer)
                                if (count == -1) break
                                if (count == 0) continue
                                if (written > maximumBytes - count) {
                                    throw IOException("File is too large for a thumbnail")
                                }
                                destination.write(buffer, 0, count)
                                written += count
                            }
                        }
                    }
                }
            }
        } catch (error: Exception) {
            output.delete()
            throw error
        }
    }

    data class UploadedFile(val original: String, val savedAs: String, val uncPath: String, val url: String?) {
        fun shareAddress(): String = url ?: uncPath
    }
    data class Outcome(val uploaded: List<UploadedFile>, val error: String? = null)

    fun testConnection(settings: NasSettings): String = withShare(settings) { share ->
        if (settings.folder.isBlank()) {
            "Connected and authenticated to SMB share '${settings.share}'."
        } else if (share.folderExists(settings.folder.replace('/', '\\'))) {
            "Connected. Destination folder exists: ${settings.uncDirectory()}"
        } else {
            "Connected to share, but folder '${settings.folder}' does not exist. It will be created when you upload."
        }
    }

    fun upload(
        settings: NasSettings,
        items: List<IncomingItem>,
        onProgress: (name: String, index: Int, total: Int, written: Long, expected: Long?) -> Unit
    ): Outcome {
        val successes = mutableListOf<UploadedFile>()
        return try {
            withShare(settings) { share ->
                val dir = settings.folder.replace('/', '\\').trim('\\')
                createFolders(share, dir)
                for ((index, item) in items.withIndex()) {
                    val name = FileNaming.uniqueName(item.displayName)
                    val remotePath = if (dir.isBlank()) name else "$dir\\$name"
                    try {
                        val bytes: ByteArray? = when (item) {
                            is IncomingItem.SharedText -> item.content.toByteArray(Charsets.UTF_8)
                            is IncomingItem.UriFile -> null
                        }
                        val expected = bytes?.size?.toLong() ?: (item as IncomingItem.UriFile).sizeBytes
                        val input = when (item) {
                            is IncomingItem.SharedText -> ByteArrayInputStream(bytes!!)
                            is IncomingItem.UriFile -> context.contentResolver.openInputStream(item.uri)
                                ?: throw IOException("Cannot read selected file: ${item.displayName}")
                        }
                        input.use { source ->
                            share.openFile(
                                remotePath,
                                EnumSet.of(AccessMask.GENERIC_WRITE),
                                EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                                EnumSet.noneOf(SMB2ShareAccess::class.java),
                                SMB2CreateDisposition.FILE_CREATE,
                                EnumSet.noneOf(SMB2CreateOptions::class.java)
                            ).use { target ->
                                target.outputStream.use { dest ->
                                    val buffer = ByteArray(64 * 1024)
                                    var written = 0L
                                    onProgress(item.displayName, index + 1, items.size, 0L, expected)
                                    while (true) {
                                        val count = source.read(buffer)
                                        if (count == -1) break
                                        if (count == 0) continue
                                        dest.write(buffer, 0, count)
                                        written += count
                                        onProgress(item.displayName, index + 1, items.size, written, expected)
                                    }
                                    dest.flush()
                                }
                            }
                        }
                        val unc = settings.uncDirectory().trimEnd('\\') + "\\" + name
                        val url = FileNaming.link(settings.baseUrl, name) { Uri.encode(it) }
                        successes.add(UploadedFile(item.displayName, name, unc, url))
                    } catch (error: Exception) {
                        // If a transfer failed, do not leave an incomplete file in the target folder.
                        try { if (share.fileExists(remotePath)) share.rm(remotePath) } catch (_: Exception) { }
                        throw IOException("Failed '${item.displayName}': ${readableError(error)}", error)
                    }
                }
            }
            Outcome(successes)
        } catch (e: Exception) {
            Outcome(successes, readableError(e))
        }
    }

    private fun createFolders(share: DiskShare, dir: String) {
        if (dir.isBlank()) return
        var current = ""
        for (part in dir.split('\\')) {
            require(FileNaming.isValidSegment(part)) { "Invalid folder component." }
            current = if (current.isBlank()) part else "$current\\$part"
            if (!share.folderExists(current)) share.mkdir(current)
        }
    }

    private fun <T> withShare(settings: NasSettings, block: (DiskShare) -> T): T {
        settings.validate()?.let { throw IllegalArgumentException(it) }
        val config = SmbConfig.builder()
            .withTimeout(90, TimeUnit.SECONDS)
            .withSoTimeout(120, TimeUnit.SECONDS)
            .build()
        SMBClient(config).use { client ->
            client.connect(settings.host).use { connection ->
                val auth = AuthenticationContext(settings.username, settings.password.toCharArray(),
                    settings.domain.ifBlank { null })
                connection.authenticate(auth).use { session ->
                    val rawShare = session.connectShare(settings.share)
                    require(rawShare is DiskShare) { "The SMB share is not a disk share." }
                    rawShare.use { disk -> return block(disk) }
                }
            }
        }
    }

    companion object {
        fun readableError(error: Throwable): String {
            val msg = error.message?.trim().orEmpty()
            return if (msg.isBlank()) error.javaClass.simpleName else msg.take(350)
        }
    }
}

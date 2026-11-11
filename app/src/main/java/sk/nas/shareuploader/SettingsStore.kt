package sk.nas.shareuploader

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Password is encrypted by a non-exportable key held in Android Keystore. */
data class NasSettings(
    val host: String = "",
    val username: String = "",
    val password: String = "",
    val domain: String = "",
    val share: String = "hdd",
    val folder: String = "copy",
    val baseUrl: String = "",
    val offerTelegram: Boolean = true
) {
    fun isReady() = host.isNotBlank() && username.isNotBlank() && password.isNotEmpty() && share.isNotBlank()

    fun uncDirectory(): String = buildString {
        append("\\\\").append(host).append("\\").append(share)
        if (folder.isNotBlank()) append("\\").append(folder.replace('/', '\\').trim('\\'))
    }

    fun validate(): String? {
        if (host.isBlank()) return "Enter NAS IP or hostname."
        if (host.contains('/') || host.contains('\\') || host.contains(':') || host.contains(' ')) return "Enter a bare NAS hostname/IP, without smb:// or a port."
        if (username.isBlank()) return "Enter the NAS username."
        if (password.isBlank()) return "Enter the NAS password."
        if (!FileNaming.isValidSegment(share)) return "Invalid SMB share name."
        if (!FileNaming.validFolder(folder)) return "Folder must contain ordinary directory names (no .. or special characters)."
        if (baseUrl.isNotBlank()) {
            val uri = android.net.Uri.parse(baseUrl.trim())
            if (uri.scheme !in listOf("http", "https") || uri.host.isNullOrBlank() ||
                uri.query != null || uri.fragment != null || uri.userInfo != null) {
                return "HTTP link base must be a http(s) URL without query, fragment or credentials."
            }
        }
        return null
    }
}

class SettingsStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("nas_settings_v1", Context.MODE_PRIVATE)

    fun load(): NasSettings = NasSettings(
        host = prefs.getString("host", "") ?: "",
        username = prefs.getString("username", "") ?: "",
        password = decryptPassword(prefs.getString("secret", "") ?: ""),
        domain = prefs.getString("domain", "") ?: "",
        share = prefs.getString("share", "hdd") ?: "hdd",
        folder = prefs.getString("folder", "copy") ?: "copy",
        baseUrl = prefs.getString("baseUrl", "") ?: "",
        offerTelegram = prefs.getBoolean("offerTelegram", true)
    )

    fun save(value: NasSettings) {
        require(value.validate() == null) { value.validate() ?: "Invalid settings" }
        // Perform encryption before changing any preferences: no half-saved settings.
        val secret = encryptPassword(value.password)
        check(prefs.edit()
            .putString("host", value.host.trim())
            .putString("username", value.username.trim())
            .putString("secret", secret)
            .putString("domain", value.domain.trim())
            .putString("share", value.share.trim())
            .putString("folder", value.folder.replace('/', '\\').trim('\\'))
            .putString("baseUrl", value.baseUrl.trim().trimEnd('/'))
            .putBoolean("offerTelegram", value.offerTelegram)
            .commit()) { "Could not store NAS settings." }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val alias = "SendToNAS_Password_AES_GCM_v1"
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build())
        }.generateKey()
    }

    private fun encryptPassword(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
    }

    private fun decryptPassword(value: String): String {
        if (value.isBlank()) return ""
        return try {
            val bytes = Base64.decode(value, Base64.NO_WRAP)
            require(bytes.size > 12)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
        } catch (e: Exception) {
            // A Keystore key may become unavailable after certain restore operations.
            // Ask for the password again rather than silently using an empty credential.
            throw IllegalStateException("Cannot decrypt saved NAS password. Clear app storage and enter settings again.", e)
        }
    }
}

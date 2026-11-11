package sk.nas.shareuploader

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView

class SettingsActivity : Activity() {
    private lateinit var host: EditText
    private lateinit var username: EditText
    private lateinit var password: EditText
    private lateinit var domain: EditText
    private lateinit var share: EditText
    private lateinit var folder: EditText
    private lateinit var baseUrl: EditText
    private lateinit var offerTelegram: CheckBox
    private lateinit var testText: TextView
    private lateinit var testProgress: ProgressBar
    private lateinit var testButton: Button
    private lateinit var saveButton: Button
    private lateinit var original: NasSettings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        host = findViewById(R.id.edtHost)
        username = findViewById(R.id.edtUsername)
        password = findViewById(R.id.edtPassword)
        domain = findViewById(R.id.edtDomain)
        share = findViewById(R.id.edtShare)
        folder = findViewById(R.id.edtFolder)
        baseUrl = findViewById(R.id.edtBaseUrl)
        offerTelegram = findViewById(R.id.checkTelegram)
        testText = findViewById(R.id.txtTest)
        testProgress = findViewById(R.id.testProgress)
        testButton = findViewById(R.id.btnTest)
        saveButton = findViewById(R.id.btnSave)

        original = try { SettingsStore(this).load() } catch (e: Exception) {
            testText.text = SmbUploader.readableError(e)
            NasSettings()
        }
        host.setText(original.host)
        username.setText(original.username)
        domain.setText(original.domain)
        share.setText(original.share)
        folder.setText(original.folder)
        baseUrl.setText(original.baseUrl)
        offerTelegram.isChecked = original.offerTelegram
        saveButton.setOnClickListener { save() }
        testButton.setOnClickListener { test() }
    }

    private fun fromFields(): NasSettings = NasSettings(
        host = host.text.toString().trim(),
        username = username.text.toString().trim(),
        password = password.text.toString().takeIf { it.isNotEmpty() } ?: original.password,
        domain = domain.text.toString().trim(),
        share = share.text.toString().trim(),
        folder = folder.text.toString().trim().replace('/', '\\').trim('\\'),
        baseUrl = baseUrl.text.toString().trim(),
        offerTelegram = offerTelegram.isChecked
    )

    private fun save() {
        val value = fromFields()
        value.validate()?.let { testText.text = it; return }
        try {
            SettingsStore(this).save(value)
            setResult(RESULT_OK)
            finish()
        } catch (e: Exception) {
            testText.text = SmbUploader.readableError(e)
        }
    }

    private fun test() {
        val value = fromFields()
        value.validate()?.let { testText.text = it; return }
        testButton.isEnabled = false
        saveButton.isEnabled = false
        testText.text = "Connecting to NAS…"
        testProgress.visibility = View.VISIBLE
        Thread {
            val message = try { SmbUploader(this).testConnection(value) }
                catch (e: Exception) { "Connection failed: ${SmbUploader.readableError(e)}" }
            runOnUiThread {
                testProgress.visibility = View.GONE
                testButton.isEnabled = true
                saveButton.isEnabled = true
                testText.text = message
            }
        }.start()
    }
}

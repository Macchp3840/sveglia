package it.sveglia

import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.IntentCompat
import androidx.core.os.LocaleListCompat

/** Nome del suono scelto: titolo della suoneria o nome del file; null = predefinito del telefono. */
fun Context.soundTitle(uri: String?): String {
    val u = uri?.let(Uri::parse) ?: return getString(R.string.sound_default)
    val title = try {
        if (u.authority == MediaStore.AUTHORITY) {
            RingtoneManager.getRingtone(this, u)?.getTitle(this)
        } else {
            contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
        }
    } catch (_: Exception) {
        null
    }
    return title ?: getString(R.string.sound_default)
}

/** Impostazioni dell'app: lingua, suono della sveglia e suono crescente. */
class SettingsActivity : AppCompatActivity() {
    private lateinit var prefs: AlarmPrefs
    private lateinit var soundName: TextView
    private lateinit var rampMinutes: EditText

    private val pickRingtone = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            val uri = IntentCompat.getParcelableExtra(data, RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
            // "Predefinito" vale come nessuna scelta: segue la suoneria del telefono anche se la cambi.
            setSound(uri?.takeUnless { RingtoneManager.isDefault(it) })
        }
    }

    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            // Senza permesso persistente il file non si riaprirebbe all'ora della sveglia, dopo un riavvio.
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            setSound(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        padForSystemBars()
        prefs = AlarmPrefs(this)
        soundName = findViewById(R.id.soundName)
        rampMinutes = findViewById(R.id.rampMinutes)

        val languages = findViewById<RadioGroup>(R.id.languageGroup)
        val current = AppCompatDelegate.getApplicationLocales().toLanguageTags()
        languages.check(
            when {
                current.startsWith("it") -> R.id.langIt
                current.startsWith("en") -> R.id.langEn
                else -> R.id.langSystem
            },
        )
        languages.setOnCheckedChangeListener { _, id ->
            val tag = when (id) {
                R.id.langIt -> "it"
                R.id.langEn -> "en"
                else -> ""
            }
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
        }

        findViewById<Button>(R.id.pickRingtone).setOnClickListener {
            pickRingtone.launch(
                Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                    .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALL)
                    .putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, getString(R.string.sound))
                    .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                    .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                    .putExtra(RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI, Settings.System.DEFAULT_ALARM_ALERT_URI)
                    .putExtra(
                        RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
                        prefs.soundUri?.let(Uri::parse) ?: Settings.System.DEFAULT_ALARM_ALERT_URI,
                    ),
            )
        }
        findViewById<Button>(R.id.pickFile).setOnClickListener { pickFile.launch(arrayOf("audio/*")) }

        findViewById<TextView>(R.id.signature).apply {
            text = getString(R.string.signature, packageManager.getPackageInfo(packageName, 0).versionName)
            setOnClickListener {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Macchp3840/sveglia")))
                } catch (_: Exception) {
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        rampMinutes.setText(prefs.rampMinutes.toString())
        renderSound()
    }

    override fun onPause() {
        super.onPause()
        val ramp = (rampMinutes.text.toString().toIntOrNull() ?: prefs.rampMinutes).coerceIn(0, AlarmScheduler.MAX_RAMP_MINUTES)
        if (ramp != prefs.rampMinutes) {
            prefs.rampMinutes = ramp
            AlarmScheduler.scheduleFromPrefs(this) // l'allarme parte prima o dopo
        }
    }

    private fun setSound(uri: Uri?) {
        prefs.soundUri = uri?.toString()
        renderSound()
    }

    private fun renderSound() {
        soundName.text = soundTitle(prefs.soundUri)
    }
}

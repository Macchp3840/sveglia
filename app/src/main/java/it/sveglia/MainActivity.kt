package it.sveglia

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.snackbar.Snackbar
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import java.util.Calendar

/** Disegna sotto le barre di sistema (obbligatorio da Android 15) e lascia spazio a barre e tastiera. */
fun AppCompatActivity.padForSystemBars() {
    WindowCompat.setDecorFitsSystemWindows(window, false)
    ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { v, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
        v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
        insets
    }
}

class MainActivity : AppCompatActivity() {
    private lateinit var prefs: AlarmPrefs

    private var hour = 7
    private var minute = 0
    private var barcodeValue: String? = null

    private lateinit var hourWheel: WheelPicker
    private lateinit var minuteWheel: WheelPicker
    private lateinit var enabledSwitch: MaterialSwitch
    private lateinit var statusText: TextView
    private lateinit var warningBox: View
    private lateinit var warningText: TextView
    private lateinit var fixButton: Button
    private lateinit var saveButton: Button
    private val dayChecks = mutableListOf<CheckBox>()
    private lateinit var mathCheck: CheckBox
    private lateinit var mathCount: EditText
    private lateinit var shakeCheck: CheckBox
    private lateinit var shakeCount: EditText
    private lateinit var barcodeCheck: CheckBox
    private lateinit var barcodeLabel: TextView
    private lateinit var wakeCheck: CheckBox
    private lateinit var wakeDelay: EditText

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { updateWarnings() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        padForSystemBars()
        prefs = AlarmPrefs(this)

        hourWheel = findViewById(R.id.hourWheel)
        minuteWheel = findViewById(R.id.minuteWheel)
        enabledSwitch = findViewById(R.id.enabledSwitch)
        statusText = findViewById(R.id.statusText)
        warningBox = findViewById(R.id.warningBox)
        warningText = findViewById(R.id.warningText)
        fixButton = findViewById(R.id.fixButton)
        saveButton = findViewById(R.id.saveButton)
        mathCheck = findViewById(R.id.mathCheck)
        mathCount = findViewById(R.id.mathCount)
        shakeCheck = findViewById(R.id.shakeCheck)
        shakeCount = findViewById(R.id.shakeCount)
        barcodeCheck = findViewById(R.id.barcodeCheck)
        barcodeLabel = findViewById(R.id.barcodeLabel)
        wakeCheck = findViewById(R.id.wakeCheck)
        wakeDelay = findViewById(R.id.wakeDelay)

        hourWheel.count = 24
        hourWheel.label = "Ore"
        hourWheel.onValueChanged = { hour = it; renderStatus() }
        minuteWheel.count = 60
        minuteWheel.label = "Minuti"
        minuteWheel.onValueChanged = { minute = it; renderStatus() }

        val daysRow = findViewById<LinearLayout>(R.id.daysRow)
        val gap = (6 * resources.displayMetrics.density).toInt()
        listOf("L" to "Lunedì", "M" to "Martedì", "M" to "Mercoledì", "G" to "Giovedì", "V" to "Venerdì", "S" to "Sabato", "D" to "Domenica")
            .forEachIndexed { i, (label, name) ->
                val cb = CheckBox(this, null, 0, R.style.Board_Day).apply {
                    text = label
                    contentDescription = name
                    setOnCheckedChangeListener { _, _ -> renderStatus() }
                }
                daysRow.addView(cb, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (i > 0) marginStart = gap
                })
                dayChecks += cb
            }

        if (sensorsMissingAccelerometer()) {
            shakeCheck.isEnabled = false
            shakeCheck.text = "Scuoti (sensore assente)"
        }

        loadFromPrefs()

        enabledSwitch.setOnCheckedChangeListener { _, _ -> renderStatus() }
        bindRow(mathCheck, R.id.mathRow, mathCount)
        bindRow(shakeCheck, R.id.shakeRow, shakeCount)
        bindRow(barcodeCheck, R.id.barcodeRow, null)
        bindRow(wakeCheck, R.id.wakeRow, wakeDelay)

        findViewById<Button>(R.id.barcodeButton).setOnClickListener { registerBarcode() }
        saveButton.setOnClickListener { save() }
        findViewById<Button>(R.id.testButton).setOnClickListener {
            if (save()) {
                AlarmScheduler.ringIn(this, 10_000)
                say("Suonerà tra 10 secondi: blocca pure lo schermo")
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onResume() {
        super.onResume()
        // Mentre suona, l'unica schermata disponibile è quella delle missioni.
        if (AlarmService.mode != AlarmService.Mode.IDLE) {
            startActivity(AlarmActivity.intent(this))
            return
        }
        updateWarnings()
        renderStatus()
    }

    /** Missione spenta = riga in penombra, contatore non modificabile. */
    private fun bindRow(check: CheckBox, rowId: Int, count: EditText?) {
        val row = findViewById<View>(rowId)
        val apply = { on: Boolean ->
            row.alpha = if (on) 1f else 0.45f
            count?.isEnabled = on
        }
        apply(check.isChecked)
        check.setOnCheckedChangeListener { _, on -> apply(on) }
    }

    private fun sensorsMissingAccelerometer(): Boolean =
        getSystemService(SensorManager::class.java).getDefaultSensor(Sensor.TYPE_ACCELEROMETER) == null

    private fun loadFromPrefs() {
        hour = prefs.hour
        minute = prefs.minute
        barcodeValue = prefs.barcodeValue
        enabledSwitch.isChecked = prefs.enabled
        dayChecks.forEachIndexed { i, cb -> cb.isChecked = prefs.days and (1 shl i) != 0 }
        mathCheck.isChecked = prefs.mathEnabled
        mathCount.setText(prefs.mathCount.toString())
        shakeCheck.isChecked = prefs.shakeEnabled && shakeCheck.isEnabled
        shakeCount.setText(prefs.shakeCount.toString())
        barcodeCheck.isChecked = prefs.barcodeEnabled
        wakeCheck.isChecked = prefs.wakeCheckEnabled
        wakeDelay.setText(prefs.wakeCheckDelayMin.toString())
        hourWheel.value = hour
        minuteWheel.value = minute
        renderBarcode()
    }

    private fun renderBarcode() {
        barcodeLabel.text = barcodeValue?.let { "Registrato: ${it.take(24)}" }
            ?: "Es. il dentifricio o un QR attaccato in bagno"
    }

    private fun uiDays(): Int = dayChecks.foldIndexed(0) { i, acc, cb -> if (cb.isChecked) acc or (1 shl i) else acc }

    /** Cosa succederà con le impostazioni sullo schermo, anche prima di salvare. */
    private fun renderStatus() {
        if (!enabledSwitch.isChecked) {
            statusText.text = "Spenta · accendila in alto"
            statusText.setTextColor(ContextCompat.getColor(this, R.color.ink_dim))
            return
        }
        val next = AlarmScheduler.nextTrigger(hour, minute, uiDays())
        val cal = Calendar.getInstance().apply { timeInMillis = next }
        val day = listOf("dom", "lun", "mar", "mer", "gio", "ven", "sab")[cal.get(Calendar.DAY_OF_WEEK) - 1]
        val mins = ((next - System.currentTimeMillis() + 59_999) / 60_000).toInt()
        val wait = if (mins >= 60) "${mins / 60} h ${mins % 60}" else "$mins min"
        val unsaved = hour != prefs.hour || minute != prefs.minute || uiDays() != prefs.days || !prefs.enabled
        statusText.text = "Suona $day %02d:%02d · tra $wait".format(hour, minute) +
            if (unsaved) " · premi Salva" else ""
        statusText.setTextColor(ContextCompat.getColor(this, if (unsaved) R.color.signal else R.color.ink))
    }

    private fun registerBarcode() {
        GmsBarcodeScanning.getClient(this).startScan()
            .addOnSuccessListener { barcode ->
                val value = barcode.rawValue
                if (value.isNullOrEmpty()) {
                    say("Codice vuoto, riprova")
                } else {
                    barcodeValue = value
                    barcodeCheck.isChecked = true
                    renderBarcode()
                    say("Codice registrato: ricordati di premere Salva")
                }
            }
            .addOnFailureListener { say("Scansione non riuscita: ${it.message}") }
    }

    private fun say(message: String) {
        Snackbar.make(saveButton, message, Snackbar.LENGTH_LONG).setAnchorView(saveButton).show()
    }

    private fun readInt(field: EditText, min: Int, max: Int, fallback: Int): Int =
        (field.text.toString().toIntOrNull() ?: fallback).coerceIn(min, max)

    /** Salva e ripianifica. Restituisce false se la configurazione non è valida. */
    private fun save(): Boolean {
        if (!mathCheck.isChecked && !shakeCheck.isChecked && !barcodeCheck.isChecked) {
            say("Scegli almeno una missione")
            return false
        }
        if (barcodeCheck.isChecked && barcodeValue == null) {
            say("Registra prima un codice da scansionare")
            return false
        }
        prefs.hour = hour
        prefs.minute = minute
        prefs.enabled = enabledSwitch.isChecked
        prefs.days = uiDays()
        prefs.mathEnabled = mathCheck.isChecked
        prefs.mathCount = readInt(mathCount, 1, 20, 3)
        prefs.shakeEnabled = shakeCheck.isChecked
        prefs.shakeCount = readInt(shakeCount, 10, 300, 40)
        prefs.barcodeEnabled = barcodeCheck.isChecked
        prefs.barcodeValue = barcodeValue
        prefs.wakeCheckEnabled = wakeCheck.isChecked
        prefs.wakeCheckDelayMin = readInt(wakeDelay, 1, 30, 5)
        loadFromPrefs()
        currentFocus?.clearFocus()

        AlarmScheduler.scheduleFromPrefs(this)
        renderStatus()
        updateWarnings()
        say("Salvato")
        return true
    }

    /** Mostra il primo permesso mancante che impedirebbe alla sveglia di funzionare bene. */
    private fun updateWarnings() {
        val problem: Pair<String, Intent>? = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED ->
                "Senza notifiche la sveglia non può comparire sullo schermo." to appSettingsIntent()

            !AlarmScheduler.canScheduleExact(this) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                "Serve il permesso per sveglie esatte, altrimenti potrebbe suonare in ritardo." to
                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName"))

            Build.VERSION.SDK_INT >= 34 &&
                !getSystemService(NotificationManager::class.java).canUseFullScreenIntent() ->
                "Serve il permesso per mostrare la sveglia a schermo intero." to
                    Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName"))

            else -> null
        }
        if (problem == null) {
            warningBox.visibility = View.GONE
        } else {
            warningText.text = problem.first
            warningBox.visibility = View.VISIBLE
            fixButton.setOnClickListener {
                try {
                    startActivity(problem.second)
                } catch (_: Exception) {
                    startActivity(appSettingsIntent())
                }
            }
        }
    }

    private fun appSettingsIntent() =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
}

package it.sveglia

import android.Manifest
import android.app.NotificationManager
import android.content.Context
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

/** Giorno e attesa della prossima sveglia con questi orari, es. ("lun", "8 h 12"). */
fun Context.nextDeparture(hour: Int, minute: Int, days: Int): Pair<String, String> {
    val next = AlarmScheduler.nextTrigger(hour, minute, days)
    val cal = Calendar.getInstance().apply { timeInMillis = next }
    val day = resources.getStringArray(R.array.week_short)[cal.get(Calendar.DAY_OF_WEEK) - 1]
    val mins = ((next - System.currentTimeMillis() + 59_999) / 60_000).toInt()
    val wait = if (mins >= 60) getString(R.string.wait_hours, mins / 60, mins % 60) else getString(R.string.wait_minutes, mins)
    return day to wait
}

/** Mostra nel riquadro warningBox il primo permesso mancante che impedirebbe alla sveglia di funzionare bene. */
fun AppCompatActivity.showPermissionWarning() {
    val problem: Pair<String, Intent>? = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED ->
            getString(R.string.warn_notifications) to appSettingsIntent()

        !AlarmScheduler.canScheduleExact(this) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            getString(R.string.warn_exact) to
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName"))

        Build.VERSION.SDK_INT >= 34 &&
            !getSystemService(NotificationManager::class.java).canUseFullScreenIntent() ->
            getString(R.string.warn_full_screen) to
                Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName"))

        else -> null
    }
    if (problem == null) {
        findViewById<View>(R.id.warningBox).visibility = View.GONE
    } else {
        findViewById<TextView>(R.id.warningText).text = problem.first
        findViewById<View>(R.id.warningBox).visibility = View.VISIBLE
        findViewById<Button>(R.id.fixButton).setOnClickListener {
            try {
                startActivity(problem.second)
            } catch (_: Exception) {
                startActivity(appSettingsIntent())
            }
        }
    }
}

private fun AppCompatActivity.appSettingsIntent() =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))

class MainActivity : AppCompatActivity() {
    private lateinit var prefs: AlarmPrefs

    private var hour = 7
    private var minute = 0
    private var barcodeValue: String? = null

    private lateinit var hourWheel: WheelPicker
    private lateinit var minuteWheel: WheelPicker
    private lateinit var enabledSwitch: MaterialSwitch
    private lateinit var statusText: TextView
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        padForSystemBars()
        prefs = AlarmPrefs(this)

        hourWheel = findViewById(R.id.hourWheel)
        minuteWheel = findViewById(R.id.minuteWheel)
        enabledSwitch = findViewById(R.id.enabledSwitch)
        statusText = findViewById(R.id.statusText)
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
        hourWheel.label = getString(R.string.hours)
        hourWheel.onValueChanged = { hour = it; renderStatus() }
        minuteWheel.count = 60
        minuteWheel.label = getString(R.string.minutes)
        minuteWheel.onValueChanged = { minute = it; renderStatus() }

        val daysRow = findViewById<LinearLayout>(R.id.daysRow)
        val gap = (6 * resources.displayMetrics.density).toInt()
        resources.getStringArray(R.array.day_letters).zip(resources.getStringArray(R.array.day_names))
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
            shakeCheck.setText(R.string.shake_no_sensor)
        }

        loadFromPrefs()

        enabledSwitch.setOnCheckedChangeListener { _, _ -> renderStatus() }
        bindRow(mathCheck, R.id.mathRow, mathCount)
        bindRow(shakeCheck, R.id.shakeRow, shakeCount)
        bindRow(barcodeCheck, R.id.barcodeRow, null)
        bindRow(wakeCheck, R.id.wakeRow, wakeDelay)

        findViewById<Button>(R.id.barcodeButton).setOnClickListener { registerBarcode() }
        saveButton.setOnClickListener { if (save()) finish() }
        findViewById<Button>(R.id.testButton).setOnClickListener {
            if (save()) {
                AlarmScheduler.ringIn(this, 10_000)
                say(getString(R.string.test_scheduled))
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Mentre suona, l'unica schermata disponibile è quella delle missioni.
        if (AlarmService.mode != AlarmService.Mode.IDLE) {
            startActivity(AlarmActivity.intent(this))
            return
        }
        showPermissionWarning()
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
        barcodeLabel.text = barcodeValue?.let { getString(R.string.barcode_registered, it.take(24)) }
            ?: getString(R.string.barcode_example)
    }

    private fun uiDays(): Int = dayChecks.foldIndexed(0) { i, acc, cb -> if (cb.isChecked) acc or (1 shl i) else acc }

    /** Cosa succederà con le impostazioni sullo schermo, anche prima di salvare. */
    private fun renderStatus() {
        if (!enabledSwitch.isChecked) {
            statusText.setText(R.string.status_off)
            statusText.setTextColor(ContextCompat.getColor(this, R.color.ink_dim))
            return
        }
        val (day, wait) = nextDeparture(hour, minute, uiDays())
        val unsaved = hour != prefs.hour || minute != prefs.minute || uiDays() != prefs.days || !prefs.enabled
        val text = getString(R.string.status_rings, day, hour, minute, wait)
        statusText.text = if (unsaved) getString(R.string.status_unsaved, text) else text
        statusText.setTextColor(ContextCompat.getColor(this, if (unsaved) R.color.signal else R.color.ink))
    }

    private fun registerBarcode() {
        GmsBarcodeScanning.getClient(this).startScan()
            .addOnSuccessListener { barcode ->
                val value = barcode.rawValue
                if (value.isNullOrEmpty()) {
                    say(getString(R.string.code_empty))
                } else {
                    barcodeValue = value
                    barcodeCheck.isChecked = true
                    renderBarcode()
                    say(getString(R.string.code_saved))
                }
            }
            .addOnFailureListener { say(getString(R.string.scan_failed_reason, it.message)) }
    }

    private fun say(message: String) {
        Snackbar.make(saveButton, message, Snackbar.LENGTH_LONG).setAnchorView(saveButton).show()
    }

    private fun readInt(field: EditText, min: Int, max: Int, fallback: Int): Int =
        (field.text.toString().toIntOrNull() ?: fallback).coerceIn(min, max)

    /** Salva e ripianifica. Restituisce false se la configurazione non è valida. */
    private fun save(): Boolean {
        if (!mathCheck.isChecked && !shakeCheck.isChecked && !barcodeCheck.isChecked) {
            say(getString(R.string.need_mission))
            return false
        }
        if (barcodeCheck.isChecked && barcodeValue == null) {
            say(getString(R.string.need_code))
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
        showPermissionWarning()
        return true
    }
}

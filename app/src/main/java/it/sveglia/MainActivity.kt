package it.sveglia

import android.Manifest
import android.app.NotificationManager
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateFormat
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import java.util.Date

class MainActivity : AppCompatActivity() {
    private lateinit var prefs: AlarmPrefs

    private var hour = 7
    private var minute = 0
    private var barcodeValue: String? = null

    private lateinit var timeButton: Button
    private lateinit var enabledSwitch: SwitchCompat
    private lateinit var statusText: TextView
    private lateinit var warningText: TextView
    private lateinit var fixButton: Button
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
        prefs = AlarmPrefs(this)

        timeButton = findViewById(R.id.timeButton)
        enabledSwitch = findViewById(R.id.enabledSwitch)
        statusText = findViewById(R.id.statusText)
        warningText = findViewById(R.id.warningText)
        fixButton = findViewById(R.id.fixButton)
        mathCheck = findViewById(R.id.mathCheck)
        mathCount = findViewById(R.id.mathCount)
        shakeCheck = findViewById(R.id.shakeCheck)
        shakeCount = findViewById(R.id.shakeCount)
        barcodeCheck = findViewById(R.id.barcodeCheck)
        barcodeLabel = findViewById(R.id.barcodeLabel)
        wakeCheck = findViewById(R.id.wakeCheck)
        wakeDelay = findViewById(R.id.wakeDelay)

        val daysRow = findViewById<LinearLayout>(R.id.daysRow)
        listOf("L", "M", "M", "G", "V", "S", "D").forEach { label ->
            val cb = CheckBox(this).apply { text = label }
            daysRow.addView(cb, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            dayChecks += cb
        }

        if (sensorsMissingAccelerometer()) {
            shakeCheck.isEnabled = false
            shakeCheck.text = "Scuoti il telefono (sensore assente)"
        }

        loadFromPrefs()

        timeButton.setOnClickListener {
            TimePickerDialog(this, { _, h, m ->
                hour = h
                minute = m
                renderTime()
            }, hour, minute, DateFormat.is24HourFormat(this)).show()
        }
        findViewById<Button>(R.id.barcodeButton).setOnClickListener { registerBarcode() }
        findViewById<Button>(R.id.saveButton).setOnClickListener { save() }
        findViewById<Button>(R.id.testButton).setOnClickListener {
            if (save()) {
                AlarmScheduler.ringIn(this, 10_000)
                Toast.makeText(this, "Suonerà tra 10 secondi: blocca pure lo schermo", Toast.LENGTH_LONG).show()
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
        renderTime()
        renderBarcode()
    }

    private fun renderTime() {
        timeButton.text = String.format("%02d:%02d", hour, minute)
    }

    private fun renderBarcode() {
        barcodeLabel.text = barcodeValue?.let { "Registrato: ${it.take(30)}" }
            ?: "Nessun codice. Usa ad es. il dentifricio o un QR attaccato in bagno."
    }

    private fun renderStatus() {
        statusText.text = if (prefs.enabled) {
            val next = AlarmScheduler.nextTrigger(prefs.hour, prefs.minute, prefs.days)
            val fmt = DateFormat.getMediumDateFormat(this).format(Date(next)) + " " + DateFormat.getTimeFormat(this).format(Date(next))
            "Prossima sveglia: $fmt"
        } else {
            "Sveglia disattivata"
        }
    }

    private fun registerBarcode() {
        GmsBarcodeScanning.getClient(this).startScan()
            .addOnSuccessListener { barcode ->
                val value = barcode.rawValue
                if (value.isNullOrEmpty()) {
                    Toast.makeText(this, "Codice vuoto, riprova", Toast.LENGTH_SHORT).show()
                } else {
                    barcodeValue = value
                    barcodeCheck.isChecked = true
                    renderBarcode()
                    Toast.makeText(this, "Codice registrato: ricordati di premere Salva", Toast.LENGTH_SHORT).show()
                }
            }
            .addOnFailureListener {
                Toast.makeText(this, "Scansione non riuscita: ${it.message}", Toast.LENGTH_LONG).show()
            }
    }

    private fun readInt(field: EditText, min: Int, max: Int, fallback: Int): Int =
        (field.text.toString().toIntOrNull() ?: fallback).coerceIn(min, max)

    /** Salva e ripianifica. Restituisce false se la configurazione non è valida. */
    private fun save(): Boolean {
        if (!mathCheck.isChecked && !shakeCheck.isChecked && !barcodeCheck.isChecked) {
            Toast.makeText(this, "Scegli almeno una missione", Toast.LENGTH_LONG).show()
            return false
        }
        if (barcodeCheck.isChecked && barcodeValue == null) {
            Toast.makeText(this, "Registra prima un codice da scansionare", Toast.LENGTH_LONG).show()
            return false
        }
        prefs.hour = hour
        prefs.minute = minute
        prefs.enabled = enabledSwitch.isChecked
        prefs.days = dayChecks.foldIndexed(0) { i, acc, cb -> if (cb.isChecked) acc or (1 shl i) else acc }
        prefs.mathEnabled = mathCheck.isChecked
        prefs.mathCount = readInt(mathCount, 1, 20, 3)
        prefs.shakeEnabled = shakeCheck.isChecked
        prefs.shakeCount = readInt(shakeCount, 10, 300, 40)
        prefs.barcodeEnabled = barcodeCheck.isChecked
        prefs.barcodeValue = barcodeValue
        prefs.wakeCheckEnabled = wakeCheck.isChecked
        prefs.wakeCheckDelayMin = readInt(wakeDelay, 1, 30, 5)
        loadFromPrefs()

        AlarmScheduler.scheduleFromPrefs(this)
        renderStatus()
        updateWarnings()
        Toast.makeText(this, "Salvato", Toast.LENGTH_SHORT).show()
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
            warningText.visibility = View.GONE
            fixButton.visibility = View.GONE
        } else {
            warningText.text = problem.first
            warningText.visibility = View.VISIBLE
            fixButton.visibility = View.VISIBLE
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

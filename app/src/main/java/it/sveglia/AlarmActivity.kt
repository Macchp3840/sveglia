package it.sveglia

import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlin.math.sqrt

class AlarmActivity : AppCompatActivity(), SensorEventListener {
    companion object {
        private const val SHAKE_THRESHOLD_G = 2.0f
        private const val SHAKE_DEBOUNCE_MS = 350L

        fun intent(context: Context): Intent =
            Intent(context, AlarmActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }

    private lateinit var prefs: AlarmPrefs
    private lateinit var sensors: SensorManager
    private var accelerometer: Sensor? = null
    private var listeningShake = false
    private var lastShake = 0L
    private var shownMode: AlarmService.Mode? = null
    private var finishing = false

    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private lateinit var mathPanel: View
    private lateinit var mathProblem: TextView
    private lateinit var mathAnswer: EditText
    private lateinit var shakePanel: View
    private lateinit var shakeCounter: TextView
    private lateinit var barcodePanel: View
    private lateinit var checkPanel: View
    private lateinit var checkCountdown: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            if (AlarmService.mode != shownMode) render()
            if (shownMode == AlarmService.Mode.CHECKING) updateCountdown()
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_alarm)

        prefs = AlarmPrefs(this)
        sensors = getSystemService(SensorManager::class.java)
        accelerometer = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        title = findViewById(R.id.title)
        subtitle = findViewById(R.id.subtitle)
        mathPanel = findViewById(R.id.mathPanel)
        mathProblem = findViewById(R.id.mathProblem)
        mathAnswer = findViewById(R.id.mathAnswer)
        shakePanel = findViewById(R.id.shakePanel)
        shakeCounter = findViewById(R.id.shakeCounter)
        barcodePanel = findViewById(R.id.barcodePanel)
        checkPanel = findViewById(R.id.checkPanel)
        checkCountdown = findViewById(R.id.checkCountdown)

        findViewById<Button>(R.id.mathSubmit).setOnClickListener { submitMath() }
        mathAnswer.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submitMath()
                true
            } else {
                false
            }
        }
        findViewById<Button>(R.id.barcodeScan).setOnClickListener { scanBarcode() }
        findViewById<Button>(R.id.barcodeLost).setOnClickListener {
            MissionState.barcodeFallback = true
            MissionState.progress = 0
            MissionState.problemText = null
            render()
        }
        findViewById<Button>(R.id.checkConfirm).setOnClickListener {
            AlarmService.send(this, AlarmService.ACTION_AWAKE_CONFIRMED)
            finish()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                Toast.makeText(this@AlarmActivity, "Niente scorciatoie: completa le missioni", Toast.LENGTH_SHORT).show()
            }
        })
    }

    override fun onResume() {
        super.onResume()
        render()
        handler.post(tick)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
        stopShake()
    }

    private fun render() {
        if (finishing) return
        shownMode = AlarmService.mode
        mathPanel.visibility = View.GONE
        shakePanel.visibility = View.GONE
        barcodePanel.visibility = View.GONE
        checkPanel.visibility = View.GONE
        stopShake()

        when (AlarmService.mode) {
            AlarmService.Mode.IDLE -> finish()
            AlarmService.Mode.CHECKING -> {
                title.text = "Sei davvero sveglio?"
                subtitle.text = "Se non confermi, la sveglia ricomincia da capo"
                checkPanel.visibility = View.VISIBLE
                updateCountdown()
            }
            AlarmService.Mode.RINGING -> renderMission()
        }
    }

    private fun renderMission() {
        val missions = MissionState.missions(prefs, accelerometer != null)
        if (MissionState.index >= missions.size) {
            allDone()
            return
        }
        val mission = missions[MissionState.index]
        title.text = "Missione ${MissionState.index + 1} di ${missions.size}"
        when (mission.type) {
            MissionType.MATH -> {
                if (MissionState.problemText == null) MissionState.newProblem()
                subtitle.text = "Risolvi ${mission.count} calcoli (${MissionState.progress}/${mission.count})"
                mathProblem.text = "${MissionState.problemText} = ?"
                mathAnswer.text.clear()
                mathPanel.visibility = View.VISIBLE
                mathAnswer.requestFocus()
            }
            MissionType.SHAKE -> {
                subtitle.text = "Scuoti forte il telefono"
                shakeCounter.text = "${MissionState.progress} / ${mission.count}"
                shakePanel.visibility = View.VISIBLE
                startShake()
            }
            MissionType.BARCODE -> {
                subtitle.text = "Alzati e vai a scansionare il codice che hai registrato"
                barcodePanel.visibility = View.VISIBLE
            }
        }
    }

    private fun currentMission(): Mission? =
        MissionState.missions(prefs, accelerometer != null).getOrNull(MissionState.index)

    private fun advance(mission: Mission) {
        MissionState.progress++
        if (MissionState.progress >= mission.count) {
            MissionState.index++
            MissionState.progress = 0
            MissionState.problemText = null
        }
        render()
    }

    private fun submitMath() {
        val mission = currentMission() ?: return
        if (mission.type != MissionType.MATH) return
        val answer = mathAnswer.text.toString().trim().toIntOrNull()
        if (answer == MissionState.problemAnswer) {
            MissionState.problemText = null
            advance(mission)
        } else {
            Toast.makeText(this, "Sbagliato! Nuovo calcolo", Toast.LENGTH_SHORT).show()
            MissionState.newProblem()
            render()
        }
    }

    private fun scanBarcode() {
        GmsBarcodeScanning.getClient(this).startScan()
            .addOnSuccessListener { barcode ->
                val mission = currentMission()
                if (mission?.type == MissionType.BARCODE && barcode.rawValue == prefs.barcodeValue) {
                    advance(mission)
                } else {
                    Toast.makeText(this, "Codice sbagliato", Toast.LENGTH_SHORT).show()
                }
            }
            .addOnFailureListener {
                Toast.makeText(this, "Scansione non riuscita: ${it.message}", Toast.LENGTH_LONG).show()
            }
    }

    private fun startShake() {
        val sensor = accelerometer ?: return
        if (!listeningShake) {
            sensors.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
            listeningShake = true
        }
    }

    private fun stopShake() {
        if (listeningShake) {
            sensors.unregisterListener(this)
            listeningShake = false
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        val (x, y, z) = Triple(event.values[0], event.values[1], event.values[2])
        val g = sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH
        val now = SystemClock.elapsedRealtime()
        if (g > SHAKE_THRESHOLD_G && now - lastShake > SHAKE_DEBOUNCE_MS) {
            lastShake = now
            val mission = currentMission() ?: return
            if (mission.type == MissionType.SHAKE) advance(mission)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun updateCountdown() {
        val left = ((AlarmService.checkDeadline - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
        checkCountdown.text = "$left s"
    }

    private fun allDone() {
        finishing = true
        AlarmService.send(this, AlarmService.ACTION_MISSIONS_DONE)
        title.text = "Buongiorno! ☀️"
        subtitle.text = if (prefs.wakeCheckEnabled) {
            "Tra ${prefs.wakeCheckDelayMin} minuti controllerò che tu sia ancora in piedi"
        } else {
            "Missioni completate"
        }
        handler.postDelayed({ finish() }, 2_500)
    }
}

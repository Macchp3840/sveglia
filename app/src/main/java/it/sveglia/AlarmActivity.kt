package it.sveglia

import android.animation.ValueAnimator
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
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
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

    private lateinit var header: TextView
    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private lateinit var progressRow: LinearLayout
    private lateinit var statusTag: TextView
    private lateinit var mathPanel: View
    private lateinit var mathProblem: LinearLayout
    private lateinit var mathAnswer: EditText
    private lateinit var shakePanel: View
    private lateinit var shakeCounter: LinearLayout
    private lateinit var shakeTotal: TextView
    private lateinit var barcodePanel: View
    private lateinit var checkPanel: View
    private lateinit var checkCountdown: LinearLayout

    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            if (AlarmService.mode != shownMode) render()
            if (shownMode == AlarmService.Mode.CHECKING) updateCountdown()
            handler.postDelayed(this, 500)
        }
    }
    private val hideTag = Runnable { statusTag.visibility = View.INVISIBLE }

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
        padForSystemBars()

        prefs = AlarmPrefs(this)
        sensors = getSystemService(SensorManager::class.java)
        accelerometer = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        header = findViewById(R.id.headerText)
        title = findViewById(R.id.title)
        subtitle = findViewById(R.id.subtitle)
        progressRow = findViewById(R.id.progressRow)
        statusTag = findViewById(R.id.statusTag)
        mathPanel = findViewById(R.id.mathPanel)
        mathProblem = findViewById(R.id.mathProblem)
        mathAnswer = findViewById(R.id.mathAnswer)
        shakePanel = findViewById(R.id.shakePanel)
        shakeCounter = findViewById(R.id.shakeCounter)
        shakeTotal = findViewById(R.id.shakeTotal)
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
            override fun handleOnBackPressed() = flag("Niente scorciatoie: completa le missioni")
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
        // La tastiera serve solo ai calcoli: renderMission() la riapre mettendo il focus sul campo.
        WindowCompat.getInsetsController(window, mathAnswer).hide(WindowInsetsCompat.Type.ime())

        when (AlarmService.mode) {
            AlarmService.Mode.IDLE -> finish()
            AlarmService.Mode.CHECKING -> {
                header.text = "Controllo"
                title.text = "Anti-ricaduta"
                subtitle.text = "Sei davvero sveglio? Se non confermi, la sveglia ricomincia da capo."
                progressRow.visibility = View.GONE
                checkPanel.visibility = View.VISIBLE
                updateCountdown()
            }
            AlarmService.Mode.RINGING -> renderMission()
        }
    }

    private fun renderMission() {
        val missions = MissionState.missions(prefs, accelerometer != null)
        if (MissionState.index >= missions.size) {
            allDone(missions.size)
            return
        }
        val mission = missions[MissionState.index]
        header.text = "In partenza"
        title.text = "Missione ${MissionState.index + 1} di ${missions.size}"
        renderProgress(missions.size, MissionState.index)
        when (mission.type) {
            MissionType.MATH -> {
                if (MissionState.problemText == null) MissionState.newProblem()
                subtitle.text = "Risolvi il calcolo · ${MissionState.progress + 1} di ${mission.count}"
                showFlaps(mathProblem, "${MissionState.problemText} =", 44f)
                mathProblem.contentDescription = "${MissionState.problemText?.replace("×", "per")} uguale"
                mathAnswer.text.clear()
                mathPanel.visibility = View.VISIBLE
                mathAnswer.requestFocus()
                WindowCompat.getInsetsController(window, mathAnswer).show(WindowInsetsCompat.Type.ime())
            }
            MissionType.SHAKE -> {
                subtitle.text = "Scuoti forte il telefono"
                showFlaps(shakeCounter, MissionState.progress.toString(), 88f)
                shakeCounter.contentDescription = "${MissionState.progress} scosse"
                shakeTotal.text = "Su ${mission.count}"
                shakePanel.visibility = View.VISIBLE
                startShake()
            }
            MissionType.BARCODE -> {
                subtitle.text = "Alzati e vai a scansionare il codice che hai registrato"
                barcodePanel.visibility = View.VISIBLE
            }
        }
    }

    /** Una barra per missione: gialla se fatta, chiara quella in corso, grigia quelle dopo. */
    private fun renderProgress(total: Int, current: Int) {
        progressRow.visibility = View.VISIBLE
        if (progressRow.childCount != total) {
            progressRow.removeAllViews()
            val gap = (4 * resources.displayMetrics.density).toInt()
            repeat(total) { i ->
                progressRow.addView(View(this), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                    if (i > 0) marginStart = gap
                })
            }
        }
        for (i in 0 until total) {
            val color = when {
                i < current -> R.color.signal
                i == current -> R.color.ink
                else -> R.color.tile
            }
            progressRow.getChildAt(i).setBackgroundColor(ContextCompat.getColor(this, color))
        }
    }

    /**
     * Tabellone: ogni cifra su una tessera, gli operatori in giallo. Le tessere cambiate
     * si ribaltano dall'alto come le palette Solari.
     */
    private fun showFlaps(container: LinearLayout, text: String, sizeSp: Float) {
        val old = container.tag as? String
        if (old == text) return
        container.tag = text
        container.removeAllViews()
        val gap = (4 * resources.displayMetrics.density).toInt()
        // Con caratteri di sistema grandi le tessere si restringono per stare in larghezza: un numero tagliato non si può risolvere.
        val chars = text.filter { it != ' ' }
        val units = chars.sumOf { if (it.isDigit()) 0.82 else 0.7 }.toFloat()
        val room = (resources.displayMetrics.widthPixels - 40 * resources.displayMetrics.density - gap * chars.length) / units
        val px = minOf(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sizeSp, resources.displayMetrics), room)
        var index = 0
        for ((i, ch) in text.withIndex()) {
            if (ch == ' ') continue
            val digit = ch.isDigit()
            val view = if (digit) {
                TextView(this, null, 0, R.style.Board_Flap).apply { setText(ch.toString()) }
            } else {
                TextView(this).apply {
                    setText(ch.toString())
                    setTextColor(ContextCompat.getColor(context, R.color.signal))
                    fontVariationSettings = "'wght' 800"
                    gravity = Gravity.CENTER
                }
            }
            view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            view.setTextSize(TypedValue.COMPLEX_UNIT_PX, px)
            val params = if (digit) {
                LinearLayout.LayoutParams((px * 0.82f).toInt(), (px * 1.3f).toInt())
            } else {
                LinearLayout.LayoutParams((px * 0.7f).toInt(), (px * 1.3f).toInt())
            }
            if (container.childCount > 0) params.marginStart = gap
            container.addView(view, params)
            val changed = old?.getOrNull(i) != ch
            if (digit && changed && ValueAnimator.areAnimatorsEnabled()) {
                view.pivotY = 0f
                view.scaleY = 0f
                view.animate().scaleY(1f).setStartDelay(index * 35L).setDuration(170).start()
            }
            index++
        }
    }

    private fun flag(message: String) {
        statusTag.text = message
        statusTag.visibility = View.VISIBLE
        handler.removeCallbacks(hideTag)
        handler.postDelayed(hideTag, 2_500)
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
            flag("Sbagliato · nuovo calcolo")
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
                    flag("Codice sbagliato")
                }
            }
            .addOnFailureListener { flag("Scansione non riuscita") }
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
        showFlaps(checkCountdown, "%02d".format(left), 88f)
        checkCountdown.contentDescription = "$left secondi"
    }

    private fun allDone(total: Int) {
        finishing = true
        AlarmService.send(this, AlarmService.ACTION_MISSIONS_DONE)
        mathPanel.visibility = View.GONE
        shakePanel.visibility = View.GONE
        barcodePanel.visibility = View.GONE
        renderProgress(total, total)
        header.text = "Arrivato"
        title.text = "Buongiorno"
        subtitle.text = if (prefs.wakeCheckEnabled) {
            "Tra ${prefs.wakeCheckDelayMin} minuti controllo che tu sia ancora in piedi."
        } else {
            "Missioni completate."
        }
        handler.postDelayed({ finish() }, 2_500)
    }
}

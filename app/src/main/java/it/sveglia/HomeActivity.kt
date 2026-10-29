package it.sveglia

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/** Schermata d'apertura: la prossima partenza sul tabellone, e da qui modifica o impostazioni. */
class HomeActivity : AppCompatActivity() {
    private lateinit var prefs: AlarmPrefs
    private lateinit var time: LinearLayout
    private lateinit var status: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 30_000)
        }
    }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { showPermissionWarning() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)
        padForSystemBars()
        prefs = AlarmPrefs(this)
        time = findViewById(R.id.homeTime)
        status = findViewById(R.id.homeStatus)

        findViewById<Button>(R.id.editButton).setOnClickListener { startActivity(Intent(this, MainActivity::class.java)) }
        findViewById<Button>(R.id.settingsButton).setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }

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
        showPermissionWarning()
        handler.post(tick)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
    }

    private fun render() {
        time.showFlaps("%02d:%02d".format(prefs.hour, prefs.minute), 88f)
        time.contentDescription = "%02d:%02d".format(prefs.hour, prefs.minute)
        time.alpha = if (prefs.enabled) 1f else 0.45f
        status.text = if (prefs.enabled) {
            val (day, wait) = nextDeparture(prefs.hour, prefs.minute, prefs.days)
            getString(R.string.home_status, day, wait)
        } else {
            getString(R.string.home_off)
        }
        status.setTextColor(ContextCompat.getColor(this, if (prefs.enabled) R.color.ink else R.color.ink_dim))

        findViewById<TextView>(R.id.detailDays).text = daysText(prefs.days)
        findViewById<TextView>(R.id.detailMissions).text = listOfNotNull(
            getString(R.string.detail_math, prefs.mathCount).takeIf { prefs.mathEnabled },
            getString(R.string.detail_shake, prefs.shakeCount).takeIf { prefs.shakeEnabled },
            getString(R.string.detail_code).takeIf { prefs.barcodeEnabled },
        ).joinToString(" · ")
        findViewById<TextView>(R.id.detailCheck).text =
            if (prefs.wakeCheckEnabled) getString(R.string.check_after, prefs.wakeCheckDelayMin) else getString(R.string.check_off)
        val sound = soundTitle(prefs.soundUri)
        findViewById<TextView>(R.id.detailSound).text =
            if (prefs.rampMinutes > 0) getString(R.string.sound_ramp, sound, prefs.rampMinutes) else sound
    }

    /** Bit 0 = lunedì ... bit 6 = domenica, come in AlarmPrefs. */
    private fun daysText(days: Int): String = when (days) {
        0 -> getString(R.string.days_once)
        0b1111111 -> getString(R.string.days_every)
        0b0011111 -> getString(R.string.days_weekdays)
        0b1100000 -> getString(R.string.days_weekend)
        else -> {
            val names = resources.getStringArray(R.array.week_short) // parte da domenica
            (0..6).filter { days and (1 shl it) != 0 }.joinToString(" ") { names[(it + 1) % 7] }
        }
    }
}

package it.sveglia

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Fa suonare la sveglia in foreground: suono al massimo (riportato al massimo se lo abbassi),
 * vibrazione e notifica a schermo intero. Si ferma solo a missioni completate.
 */
class AlarmService : Service() {
    enum class Mode { IDLE, RINGING, CHECKING }

    companion object {
        const val ACTION_RING = "it.sveglia.service.RING"
        const val ACTION_CHECK = "it.sveglia.service.CHECK"
        const val ACTION_MISSIONS_DONE = "it.sveglia.service.MISSIONS_DONE"
        const val ACTION_AWAKE_CONFIRMED = "it.sveglia.service.AWAKE_CONFIRMED"

        const val CHECK_TIMEOUT_MS = 60_000L
        private const val CHANNEL_ID = "alarm"
        private const val NOTIFICATION_ID = 1

        @Volatile
        var mode = Mode.IDLE
            private set

        @Volatile
        var checkDeadline = 0L
            private set

        /** Per gli allarmi in arrivo (anche con l'app in background). */
        fun start(context: Context, action: String) {
            ContextCompat.startForegroundService(context, Intent(context, AlarmService::class.java).setAction(action))
        }

        /** Per i comandi inviati dall'interfaccia, che è in primo piano. */
        fun send(context: Context, action: String) {
            context.startService(Intent(context, AlarmService::class.java).setAction(action))
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var audio: AudioManager
    private var player: MediaPlayer? = null
    private var tone: ToneGenerator? = null
    private var vibrator: Vibrator? = null
    private var savedVolume = -1

    private val enforceVolume = object : Runnable {
        override fun run() {
            if (mode != Mode.RINGING) return
            try {
                val max = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)
                if (audio.getStreamVolume(AudioManager.STREAM_ALARM) < max) {
                    audio.setStreamVolume(AudioManager.STREAM_ALARM, max, 0)
                }
            } catch (_: SecurityException) {
            }
            handler.postDelayed(this, 1_000)
        }
    }

    private val escalate = Runnable { if (mode == Mode.CHECKING) startRinging() }

    override fun onCreate() {
        super.onCreate()
        audio = getSystemService(AudioManager::class.java)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_RING -> startRinging()
            ACTION_CHECK -> if (mode == Mode.RINGING) goForeground() else startCheck()
            ACTION_MISSIONS_DONE -> finishRinging()
            ACTION_AWAKE_CONFIRMED -> if (mode == Mode.CHECKING) stopEverything() else if (mode == Mode.IDLE) stopSelf()
            null -> {
                // Riavviato dal sistema dopo essere stato ucciso: se suonava, riprende.
                if (AlarmPrefs(this).pendingRing) {
                    try {
                        startRinging()
                    } catch (_: Exception) {
                        AlarmScheduler.ringIn(this, 3_000)
                        stopSelf()
                    }
                } else {
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    private fun startRinging() {
        val wasRinging = mode == Mode.RINGING
        mode = Mode.RINGING
        AlarmPrefs(this).pendingRing = true
        handler.removeCallbacks(escalate)
        goForeground()
        if (!wasRinging) {
            MissionState.reset()
            stopTone()
            if (savedVolume < 0) savedVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM)
            startSound()
            startVibration()
            handler.post(enforceVolume)
        }
        launchUi()
    }

    private fun startCheck() {
        mode = Mode.CHECKING
        checkDeadline = System.currentTimeMillis() + CHECK_TIMEOUT_MS
        goForeground()
        startToneLoop(ToneGenerator.TONE_PROP_BEEP2, volume = 70, everyMs = 2_000)
        handler.postDelayed(escalate, CHECK_TIMEOUT_MS)
        launchUi()
    }

    private fun finishRinging() {
        if (mode != Mode.RINGING) {
            if (mode == Mode.IDLE) stopSelf()
            return
        }
        val prefs = AlarmPrefs(this)
        prefs.pendingRing = false
        if (prefs.wakeCheckEnabled) AlarmScheduler.scheduleWakeCheck(this, prefs.wakeCheckDelayMin)
        stopEverything()
    }

    private fun stopEverything() {
        mode = Mode.IDLE
        handler.removeCallbacksAndMessages(null)
        releaseMedia()
        if (savedVolume >= 0) {
            try {
                audio.setStreamVolume(AudioManager.STREAM_ALARM, savedVolume, 0)
            } catch (_: SecurityException) {
            }
            savedVolume = -1
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        releaseMedia()
        super.onDestroy()
    }

    // --- Suono e vibrazione ---

    private fun startSound() {
        if (player != null) return
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val candidates = listOf(RingtoneManager.TYPE_ALARM, RingtoneManager.TYPE_RINGTONE, RingtoneManager.TYPE_NOTIFICATION)
            .mapNotNull { RingtoneManager.getActualDefaultRingtoneUri(this, it) }
        for (uri in candidates) {
            val mp = MediaPlayer()
            try {
                mp.setAudioAttributes(attrs)
                mp.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK)
                mp.setDataSource(this, uri)
                mp.isLooping = true
                mp.prepare()
                mp.start()
                player = mp
                return
            } catch (_: Exception) {
                mp.release()
            }
        }
        // Nessuna suoneria utilizzabile: tono di sistema.
        startToneLoop(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, volume = 100, everyMs = 1_000)
    }

    private val toneLoop = object : Runnable {
        var toneType = 0
        var everyMs = 1_000L
        override fun run() {
            tone?.startTone(toneType, (everyMs * 0.8).toInt())
            handler.postDelayed(this, everyMs)
        }
    }

    private fun startToneLoop(toneType: Int, volume: Int, everyMs: Long) {
        stopTone()
        tone = try {
            ToneGenerator(AudioManager.STREAM_ALARM, volume)
        } catch (_: RuntimeException) {
            null
        }
        toneLoop.toneType = toneType
        toneLoop.everyMs = everyMs
        handler.post(toneLoop)
    }

    private fun stopTone() {
        handler.removeCallbacks(toneLoop)
        tone?.release()
        tone = null
    }

    private fun startVibration() {
        val vib = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Vibrator::class.java)
        }
        if (vib == null || !vib.hasVibrator()) return
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
        @Suppress("DEPRECATION")
        vib.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 400), 0), attrs)
        vibrator = vib
    }

    private fun releaseMedia() {
        player?.run {
            try {
                stop()
            } catch (_: IllegalStateException) {
            }
            release()
        }
        player = null
        stopTone()
        vibrator?.cancel()
        vibrator = null
    }

    // --- Notifica e interfaccia ---

    private fun goForeground() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(CHANNEL_ID, "Sveglia", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Sveglia che suona"
                setSound(null, null) // il suono lo gestisce il servizio
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setBypassDnd(true)
            }
            nm.createNotificationChannel(channel)
        }
        val openUi = PendingIntent.getActivity(
            this, 0, AlarmActivity.intent(this),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val title = if (mode == Mode.CHECKING) "Sei sveglio? Conferma entro 60 secondi" else "Sveglia! Completa le missioni"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_alarm)
            .setContentTitle(title)
            .setContentText("Tocca per aprire")
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setSilent(true)
            .setContentIntent(openUi)
            .setFullScreenIntent(openUi, true)
            .build()
    }

    private fun launchUi() {
        // Può essere bloccato dalle restrizioni sull'avvio da background: in quel caso
        // ci pensa la notifica a schermo intero.
        try {
            startActivity(AlarmActivity.intent(this))
        } catch (_: Exception) {
        }
    }
}

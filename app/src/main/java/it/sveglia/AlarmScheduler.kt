package it.sveglia

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Calendar

object AlarmScheduler {
    private const val REQ_MAIN = 100
    private const val REQ_WAKE_CHECK = 101
    private const val REQ_RING_NOW = 102
    const val MAX_RAMP_MINUTES = 5
    const val EXTRA_AT = "at"

    /** Prossimo istante (ms) in cui la sveglia deve suonare. */
    fun nextTrigger(hour: Int, minute: Int, days: Int, now: Long = System.currentTimeMillis()): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (days == 0) {
            if (cal.timeInMillis <= now) cal.add(Calendar.DAY_OF_YEAR, 1)
            return cal.timeInMillis
        }
        repeat(8) {
            if (cal.timeInMillis > now && isDayEnabled(days, cal.get(Calendar.DAY_OF_WEEK))) {
                return cal.timeInMillis
            }
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return cal.timeInMillis
    }

    /** Calendar.MONDAY(2)..SUNDAY(1) -> bit 0..6 */
    fun isDayEnabled(days: Int, calendarDayOfWeek: Int): Boolean =
        days and (1 shl ((calendarDayOfWeek + 5) % 7)) != 0

    fun canScheduleExact(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    /** Pianifica la sveglia secondo le impostazioni; restituisce l'istante o null se disattivata. */
    fun scheduleFromPrefs(context: Context): Long? {
        val prefs = AlarmPrefs(context)
        val checkAt = prefs.wakeCheckAt
        if (checkAt > 0) setAlarm(context, maxOf(checkAt, System.currentTimeMillis() + 5_000), wakeCheckOperation(context))

        if (!prefs.enabled) {
            context.getSystemService(AlarmManager::class.java).cancel(mainOperation(context))
            return null
        }
        // Una sveglia già partita non va ripianificata: durante il suono crescente la sua ora è ancora davanti.
        // Solo se recente, così un orologio spostato indietro non fa saltare un giorno.
        val now = System.currentTimeMillis()
        val fired = prefs.firedAt.takeIf { it - now <= MAX_RAMP_MINUTES * 60_000L } ?: 0L
        val time = nextTrigger(prefs.hour, prefs.minute, prefs.days, maxOf(now, fired))
        // shortcut: il sistema mostra come "prossima sveglia" l'inizio del suono crescente, non l'ora impostata.
        setAlarm(context, time - prefs.rampMinutes * 60_000L, mainOperation(context, time))
        return time
    }

    fun scheduleWakeCheck(context: Context, delayMinutes: Int) {
        val time = System.currentTimeMillis() + delayMinutes * 60_000L
        AlarmPrefs(context).wakeCheckAt = time
        setAlarm(context, time, wakeCheckOperation(context))
    }

    fun cancelWakeCheck(context: Context) {
        AlarmPrefs(context).wakeCheckAt = 0L
        context.getSystemService(AlarmManager::class.java).cancel(wakeCheckOperation(context))
    }

    /** Fa suonare la sveglia tra [delayMs] millisecondi (prova, o ripresa dopo un riavvio). */
    fun ringIn(context: Context, delayMs: Long) {
        setAlarm(context, System.currentTimeMillis() + delayMs, operation(context, AlarmReceiver.ACTION_RING_NOW, REQ_RING_NOW))
    }

    private fun setAlarm(context: Context, time: Long, operation: PendingIntent) {
        val am = context.getSystemService(AlarmManager::class.java)
        val show = PendingIntent.getActivity(
            context, 0, Intent(context, HomeActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        try {
            am.setAlarmClock(AlarmManager.AlarmClockInfo(time, show), operation)
        } catch (e: SecurityException) {
            // Senza permesso per gli allarmi esatti: meglio un allarme impreciso che nessuno.
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, operation)
        }
    }

    /** [at]: ora impostata, arriva al receiver per far salire il volume fin lì (per cancellare non conta). */
    private fun mainOperation(context: Context, at: Long = 0L) = operation(context, AlarmReceiver.ACTION_FIRE, REQ_MAIN, at)

    private fun wakeCheckOperation(context: Context) = operation(context, AlarmReceiver.ACTION_WAKE_CHECK, REQ_WAKE_CHECK)

    private fun operation(context: Context, action: String, requestCode: Int, at: Long = 0L): PendingIntent =
        PendingIntent.getBroadcast(
            context, requestCode,
            Intent(context, AlarmReceiver::class.java).setAction(action).putExtra(EXTRA_AT, at),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}

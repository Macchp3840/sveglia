package it.sveglia

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AlarmReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_FIRE = "it.sveglia.action.FIRE"
        const val ACTION_WAKE_CHECK = "it.sveglia.action.WAKE_CHECK"
        const val ACTION_RING_NOW = "it.sveglia.action.RING_NOW"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_FIRE -> {
                val prefs = AlarmPrefs(context)
                if (prefs.days == 0) prefs.enabled = false
                AlarmScheduler.cancelWakeCheck(context)
                AlarmScheduler.scheduleFromPrefs(context)
                AlarmService.start(context, AlarmService.ACTION_RING)
            }
            ACTION_RING_NOW -> AlarmService.start(context, AlarmService.ACTION_RING)
            ACTION_WAKE_CHECK -> {
                AlarmPrefs(context).wakeCheckAt = 0L
                AlarmService.start(context, AlarmService.ACTION_CHECK)
            }
        }
    }
}

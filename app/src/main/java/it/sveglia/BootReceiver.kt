package it.sveglia

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Ripianifica dopo riavvio/aggiornamento e fa ripartire una sveglia interrotta spegnendo il telefono. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AlarmScheduler.scheduleFromPrefs(context)
        val bootLike = intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        if (bootLike && AlarmPrefs(context).pendingRing) {
            // Un servizio mediaPlayback non può partire da BOOT_COMPLETED (Android 15):
            // passiamo da un allarme esatto, che invece lo consente.
            AlarmScheduler.ringIn(context, 3_000)
        }
    }
}

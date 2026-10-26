package it.sveglia

import android.content.Context

/** Impostazioni della sveglia, salvate in SharedPreferences. */
class AlarmPrefs(context: Context) {
    private val sp = context.getSharedPreferences("sveglia", Context.MODE_PRIVATE)

    var hour: Int
        get() = sp.getInt("hour", 7)
        set(v) = sp.edit().putInt("hour", v).apply()
    var minute: Int
        get() = sp.getInt("minute", 0)
        set(v) = sp.edit().putInt("minute", v).apply()
    var enabled: Boolean
        get() = sp.getBoolean("enabled", false)
        set(v) = sp.edit().putBoolean("enabled", v).apply()

    /** Bit 0 = lunedì ... bit 6 = domenica. 0 = una volta sola. */
    var days: Int
        get() = sp.getInt("days", 0)
        set(v) = sp.edit().putInt("days", v).apply()

    var mathEnabled: Boolean
        get() = sp.getBoolean("math_enabled", true)
        set(v) = sp.edit().putBoolean("math_enabled", v).apply()
    var mathCount: Int
        get() = sp.getInt("math_count", 3)
        set(v) = sp.edit().putInt("math_count", v).apply()

    var shakeEnabled: Boolean
        get() = sp.getBoolean("shake_enabled", false)
        set(v) = sp.edit().putBoolean("shake_enabled", v).apply()
    var shakeCount: Int
        get() = sp.getInt("shake_count", 40)
        set(v) = sp.edit().putInt("shake_count", v).apply()

    var barcodeEnabled: Boolean
        get() = sp.getBoolean("barcode_enabled", false)
        set(v) = sp.edit().putBoolean("barcode_enabled", v).apply()
    var barcodeValue: String?
        get() = sp.getString("barcode_value", null)
        set(v) = sp.edit().putString("barcode_value", v).apply()

    var wakeCheckEnabled: Boolean
        get() = sp.getBoolean("wake_check_enabled", true)
        set(v) = sp.edit().putBoolean("wake_check_enabled", v).apply()
    var wakeCheckDelayMin: Int
        get() = sp.getInt("wake_check_delay", 5)
        set(v) = sp.edit().putInt("wake_check_delay", v).apply()

    /** true finché la sveglia suona e le missioni non sono completate (sopravvive al riavvio). */
    var pendingRing: Boolean
        get() = sp.getBoolean("pending_ring", false)
        set(v) {
            sp.edit().putBoolean("pending_ring", v).commit()
        }

    /** Istante del controllo "sei sveglio?" in attesa, 0 se nessuno. */
    var wakeCheckAt: Long
        get() = sp.getLong("wake_check_at", 0L)
        set(v) {
            sp.edit().putLong("wake_check_at", v).commit()
        }
}

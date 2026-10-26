package it.sveglia

import kotlin.random.Random

enum class MissionType { MATH, SHAKE, BARCODE }

data class Mission(val type: MissionType, val count: Int)

/** Avanzamento delle missioni: vive nel processo, così ruotare o riaprire la schermata non lo azzera. */
object MissionState {
    const val EMERGENCY_MATH_COUNT = 8

    var index = 0
    var progress = 0
    var barcodeFallback = false
    var problemText: String? = null
    var problemAnswer = 0

    fun reset() {
        index = 0
        progress = 0
        barcodeFallback = false
        problemText = null
    }

    fun newProblem() {
        val a = Random.nextInt(12, 50)
        val b = Random.nextInt(3, 10)
        val c = Random.nextInt(11, 100)
        problemText = "$a × $b + $c"
        problemAnswer = a * b + c
    }

    fun missions(prefs: AlarmPrefs, hasAccelerometer: Boolean): List<Mission> {
        val list = mutableListOf<Mission>()
        if (prefs.mathEnabled) list += Mission(MissionType.MATH, prefs.mathCount)
        if (prefs.shakeEnabled && hasAccelerometer) list += Mission(MissionType.SHAKE, prefs.shakeCount)
        if (prefs.barcodeEnabled && prefs.barcodeValue != null) {
            list += if (barcodeFallback) Mission(MissionType.MATH, EMERGENCY_MATH_COUNT) else Mission(MissionType.BARCODE, 1)
        }
        if (list.isEmpty()) list += Mission(MissionType.MATH, 3)
        return list
    }
}

package com.rutger.soundboard

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

private const val TAG = "Battery"

/** Below this the app warns about the soundboard's battery. */
const val LOW_BATTERY_PERCENT = 15

/**
 * A jump up of this many points between two samples means the battery was
 * charged. The firmware can't report charging yet, and normal reading noise is
 * only a few %.
 */
private const val CHARGE_JUMP_PERCENT = 10

/** The estimate needs at least this much data, the readings are ±5-10 %. */
private const val MIN_ESTIMATE_SPAN_MS = 60 * 60 * 1000L
private const val MIN_ESTIMATE_DROP_PERCENT = 3

/** Safety cap on the history size; a session never gets near this in practice. */
private const val MAX_SAMPLES = 5_000

private const val HOUR_MS = 60 * 60 * 1000.0

/** Charging state byte of Battery (raw). Only "unknown" exists until TP4056 CHRG is wired. */
fun chargingLabel(state: Int): String = when (state) {
    0 -> "unknown"
    else -> "unknown ($state)"
}

/** The latest battery values from the ESP32. [millivolts] is null if Battery (raw) is missing. */
data class BatteryReading(
    val percent: Int,
    val millivolts: Int?,
    val charging: Int,
)

data class BatterySample(val timeMs: Long, val percent: Int, val millivolts: Int?)

/**
 * Everything recorded since the last charge the app detected (or since it
 * started tracking). [startMs] is null while nothing has been recorded.
 */
data class BatterySession(
    val startMs: Long? = null,
    val samples: List<BatterySample> = emptyList(),
    val presses: Int = 0,
) {
    /** Add [sample]; a jump of [CHARGE_JUMP_PERCENT] or more starts a new session. */
    fun withSample(sample: BatterySample): BatterySession {
        val last = samples.lastOrNull()
        if (startMs == null || (last != null && sample.percent - last.percent >= CHARGE_JUMP_PERCENT)) {
            return BatterySession(startMs = sample.timeMs, samples = listOf(sample))
        }
        return copy(samples = (samples + sample).takeLast(MAX_SAMPLES))
    }

    fun withPress(): BatterySession = copy(presses = presses + 1)
}

/**
 * Derived numbers for the nerd stats. Fields depending on the drain rate are
 * null until there is enough data, the mAh ones also need a configured capacity.
 */
data class BatteryStats(
    /** Fitted drain, % per hour. */
    val drainPercentPerHour: Double?,
    val mahUsed: Double?,
    val averageMa: Double?,
    val emptyAtMs: Long?,
)

/**
 * Least-squares fit of percent over time across the whole session, so a single
 * noisy reading doesn't swing the estimate.
 *
 * - average current (mA) = drain (%/h) x capacity / 100
 * - time remaining = remaining % / drain (%/h)
 */
fun computeBatteryStats(session: BatterySession, capacityMah: Int?): BatteryStats {
    val none = BatteryStats(null, null, null, null)
    val samples = session.samples
    val start = session.startMs ?: return none
    val last = samples.lastOrNull() ?: return none
    val first = samples.first()
    if (last.timeMs - first.timeMs < MIN_ESTIMATE_SPAN_MS) return none
    if (samples.maxOf { it.percent } - samples.minOf { it.percent } < MIN_ESTIMATE_DROP_PERCENT) return none

    val xs = samples.map { (it.timeMs - first.timeMs) / HOUR_MS }
    val ys = samples.map { it.percent.toDouble() }
    val mx = xs.average()
    val my = ys.average()
    var sxy = 0.0
    var sxx = 0.0
    for (i in xs.indices) {
        sxy += (xs[i] - mx) * (ys[i] - my)
        sxx += (xs[i] - mx) * (xs[i] - mx)
    }
    if (sxx == 0.0) return none
    val drain = -sxy / sxx
    // Flat or rising: noise or a slow charge, nothing sensible to estimate.
    if (drain <= 0.0) return none

    val emptyAtMs = last.timeMs + (last.percent / drain * HOUR_MS).toLong()
    val hours = (last.timeMs - start) / HOUR_MS
    return BatteryStats(
        drainPercentPerHour = drain,
        mahUsed = capacityMah?.let { drain * hours * it / 100.0 },
        averageMa = capacityMah?.let { drain * it / 100.0 },
        emptyAtMs = emptyAtMs,
    )
}

/** Persists the [BatterySession] in a small JSON file, so it survives app and service restarts. */
class BatteryHistory(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "battery_history.json"))

    fun load(): BatterySession = try {
        val json = JSONObject(String(file.readFully(), Charsets.UTF_8))
        val arr = json.getJSONArray("samples")
        val samples = (0 until arr.length()).map { i ->
            val s = arr.getJSONArray(i)
            BatterySample(
                timeMs = s.getLong(0),
                percent = s.getInt(1),
                millivolts = if (s.isNull(2)) null else s.getInt(2),
            )
        }
        BatterySession(
            startMs = if (json.isNull("startMs")) null else json.getLong("startMs"),
            samples = samples,
            presses = json.optInt("presses", 0),
        )
    } catch (e: java.io.FileNotFoundException) {
        BatterySession()
    } catch (e: Exception) {
        Log.e(TAG, "Battery history unreadable, starting over", e)
        BatterySession()
    }

    fun save(session: BatterySession) {
        val samples = JSONArray()
        for (s in session.samples) {
            samples.put(JSONArray().put(s.timeMs).put(s.percent).put(s.millivolts ?: JSONObject.NULL))
        }
        val json = JSONObject()
            .put("startMs", session.startMs ?: JSONObject.NULL)
            .put("presses", session.presses)
            .put("samples", samples)
        val out = try {
            file.startWrite()
        } catch (e: IOException) {
            Log.e(TAG, "Could not write battery history", e)
            return
        }
        try {
            out.write(json.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(out)
        } catch (e: IOException) {
            Log.e(TAG, "Could not write battery history", e)
            file.failWrite(out)
        }
    }
}

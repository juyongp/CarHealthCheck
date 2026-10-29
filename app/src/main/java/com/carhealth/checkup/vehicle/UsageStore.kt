package com.carhealth.checkup.vehicle

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Usage the app accumulates itself, because the SDK has no direct value for it:
 * air-conditioning hours (cabin filter), wiper hours, brake presses (pad wear estimate),
 * the distance driven while the app was counting, and daily odometer and efficiency history.
 *
 * Counters only grow. A replacement stores a snapshot of them with the odometer, so the page
 * can tell how much was used since, and scale it up by distance for the time the app was closed.
 */
class UsageStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("car_health_usage", Context.MODE_PRIVATE)

    init {
        migrate()
    }

    @Synchronized
    fun addHvacSeconds(seconds: Int) = addLong(KEY_HVAC, seconds.toLong())

    @Synchronized
    fun addWiperSeconds(seconds: Int) = addLong(KEY_WIPER, seconds.toLong())

    @Synchronized
    fun addBrakePress() = addLong(KEY_BRAKE, 1L)

    /** Distance driven while the app was reading the car; usage per km is measured over this. */
    @Synchronized
    fun addObservedKm(km: Double) {
        if (km.isNaN() || km.isInfinite() || km <= 0.0) return
        addLong(KEY_OBSERVED_M, Math.round(km * 1000.0))
    }

    @Synchronized
    fun recordOdometer(km: Double) {
        if (km.isNaN() || km.isInfinite() || km < 0.0) return
        val editor = prefs.edit()
        if (!prefs.contains(KEY_START_ODO)) editor.putString(KEY_START_ODO, km.toString())
        val history = dailyUpsert(KEY_ODO_HISTORY, "km", km)
        editor.putString(KEY_ODO_HISTORY, history.toString())
        editor.apply()
    }

    @Synchronized
    fun recordEfficiency(value: Double) {
        if (value.isNaN() || value.isInfinite() || value <= 0.0) return
        prefs.edit().putString(KEY_EFFICIENCY, dailyUpsert(KEY_EFFICIENCY, "value", value).toString()).apply()
    }

    /**
     * Called when the driver records a replacement in the app (S6 정비 기록).
     * [daysAgo] is 0 for "just now"; [odometerKm] is the page's estimate for that day, or negative when unknown.
     */
    @Synchronized
    fun recordReplacement(itemId: String, daysAgo: Int, odometerKm: Double, how: String) {
        val at = System.currentTimeMillis() - daysAgo.coerceIn(0, 3650) * DAY_MS
        val record = snapshot(at)
        if (!odometerKm.isNaN() && !odometerKm.isInfinite() && odometerKm >= 0.0) record.put("odo", odometerKm)
        val log = JSONArray(prefs.getString(KEY_LOG, null) ?: "[]")
        log.put(JSONObject().put("id", itemId).put("at", at).put("how", how))
        while (log.length() > MAX_LOG) log.remove(0)
        prefs.edit()
            .putString(KEY_RECORD_PREFIX + itemId, record.toString())
            .putString(KEY_LOG, log.toString())
            .apply()
    }

    @Synchronized
    fun toJson(includeHvac: Boolean, includeWiper: Boolean, includeBrake: Boolean): JSONObject {
        val json = JSONObject()
        if (includeHvac) json.put("hvacSeconds", prefs.getLong(KEY_HVAC, 0L))
        if (includeWiper) json.put("wiperSeconds", prefs.getLong(KEY_WIPER, 0L))
        if (includeBrake) json.put("brakePresses", prefs.getLong(KEY_BRAKE, 0L))
        json.put("observedKm", prefs.getLong(KEY_OBSERVED_M, 0L) / 1000.0)

        val efficiency = JSONArray()
        val history = JSONArray(prefs.getString(KEY_EFFICIENCY, null) ?: "[]")
        for (i in 0 until history.length()) {
            val value = history.optJSONObject(i)?.optDouble("value") ?: continue
            if (!value.isNaN() && !value.isInfinite()) efficiency.put(value)
        }
        json.put("efficiencyHistory", efficiency)
        json.put("odoHistory", JSONArray(prefs.getString(KEY_ODO_HISTORY, null) ?: "[]"))

        val start = JSONObject().put("at", prefs.getLong(KEY_START_AT, System.currentTimeMillis()))
        prefs.getString(KEY_START_ODO, null)?.toDoubleOrNull()?.let { start.put("odo", it) }
        json.put("start", start)

        val records = JSONObject()
        for ((key, value) in prefs.all) {
            if (!key.startsWith(KEY_RECORD_PREFIX) || value !is String) continue
            runCatching { records.put(key.removePrefix(KEY_RECORD_PREFIX), JSONObject(value)) }
        }
        json.put("records", records)
        json.put("log", JSONArray(prefs.getString(KEY_LOG, null) ?: "[]"))
        json.put("now", System.currentTimeMillis())
        return json
    }

    private fun snapshot(at: Long): JSONObject = JSONObject()
        .put("at", at)
        .put("hvac", prefs.getLong(KEY_HVAC, 0L))
        .put("wiper", prefs.getLong(KEY_WIPER, 0L))
        .put("brake", prefs.getLong(KEY_BRAKE, 0L))

    /** Keeps one entry per day, newest last. */
    private fun dailyUpsert(key: String, field: String, value: Double): JSONArray {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val history = JSONArray(prefs.getString(key, null) ?: "[]")
        val last = if (history.length() > 0) history.optJSONObject(history.length() - 1) else null
        if (last != null && last.optString("day") == today) {
            last.put(field, value)
        } else {
            history.put(JSONObject().put("day", today).put(field, value))
        }
        while (history.length() > MAX_DAYS) history.remove(0)
        return history
    }

    /**
     * Version 1 reset the counters on each replacement and kept only a "replaced_<id>" time.
     * A zero snapshot at that time reads the same way under the new scheme.
     */
    private fun migrate() {
        val editor = prefs.edit()
        if (!prefs.contains(KEY_START_AT)) editor.putLong(KEY_START_AT, System.currentTimeMillis())
        val old = prefs.all.filterKeys { it.startsWith(OLD_REPLACED_PREFIX) }
        if (old.isNotEmpty()) {
            val log = JSONArray(prefs.getString(KEY_LOG, null) ?: "[]")
            for ((key, value) in old.entries.sortedBy { (it.value as? Long) ?: 0L }) {
                val at = value as? Long ?: continue
                val itemId = key.removePrefix(OLD_REPLACED_PREFIX)
                val record = JSONObject().put("at", at).put("hvac", 0L).put("wiper", 0L).put("brake", 0L)
                editor.putString(KEY_RECORD_PREFIX + itemId, record.toString())
                log.put(JSONObject().put("id", itemId).put("at", at).put("how", "diy"))
                editor.remove(key)
            }
            editor.putString(KEY_LOG, log.toString())
        }
        editor.apply()
    }

    private fun addLong(key: String, delta: Long) {
        prefs.edit().putLong(key, prefs.getLong(key, 0L) + delta).apply()
    }

    companion object {
        private const val KEY_HVAC = "hvac_seconds"
        private const val KEY_WIPER = "wiper_seconds"
        private const val KEY_BRAKE = "brake_presses"
        private const val KEY_OBSERVED_M = "observed_meters"
        private const val KEY_EFFICIENCY = "efficiency_history"
        private const val KEY_ODO_HISTORY = "odometer_history"
        private const val KEY_START_AT = "tracking_start_at"
        private const val KEY_START_ODO = "tracking_start_odometer"
        private const val KEY_RECORD_PREFIX = "record_"
        private const val KEY_LOG = "maintenance_log"
        private const val OLD_REPLACED_PREFIX = "replaced_"
        private const val MAX_DAYS = 120
        private const val MAX_LOG = 60
        private const val DAY_MS = 86_400_000L
    }
}

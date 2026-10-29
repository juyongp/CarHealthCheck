package com.carhealth.checkup.vehicle

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Usage the app accumulates itself, because the SDK has no direct value for it:
 * air-conditioning hours (cabin filter), wiper hours, brake presses (pad wear estimate),
 * the distance driven while the app was counting, daily odometer and efficiency history,
 * charging sessions and deep discharges (battery habits), and the maintenance log.
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

    /**
     * Charging habits from the state of charge (%). A rise of a point or more since the last reference
     * value is charging (also when it happened while the app was closed: the next reading is just higher);
     * rises within a few hours of each other are one session. Falling below [LOW_SOC] counts as a deep discharge.
     */
    @Synchronized
    fun recordSoc(soc: Double) {
        if (soc.isNaN() || soc.isInfinite() || soc < 0.0 || soc > 100.0) return
        val now = System.currentTimeMillis()
        val reference = prefs.getString(KEY_SOC_REF, null)?.toDoubleOrNull()
        val editor = prefs.edit()
        if (reference == null) {
            editor.putString(KEY_SOC_REF, soc.toString()).apply()
            return
        }
        val delta = soc - reference
        if (delta > -1.0 && delta < 1.0) return
        val sessions = JSONArray(prefs.getString(KEY_CHARGES, null) ?: "[]")
        if (delta >= 1.0) {
            val last = if (sessions.length() > 0) sessions.optJSONObject(sessions.length() - 1) else null
            if (last != null && now - last.optLong("upd") < SESSION_GAP_MS && last.optDouble("to") <= reference + 0.5) {
                last.put("to", soc).put("upd", now)
            } else {
                sessions.put(JSONObject().put("from", reference).put("to", soc).put("at", now).put("upd", now))
            }
            while (sessions.length() > MAX_CHARGES) sessions.remove(0)
            editor.putString(KEY_CHARGES, sessions.toString())
        } else if (soc < LOW_SOC && reference >= LOW_SOC) {
            val lows = JSONArray(prefs.getString(KEY_LOWS, null) ?: "[]")
            lows.put(JSONObject().put("at", now).put("soc", soc))
            while (lows.length() > MAX_CHARGES) lows.remove(0)
            editor.putString(KEY_LOWS, lows.toString())
        }
        editor.putString(KEY_SOC_REF, soc.toString()).apply()
    }

    @Synchronized
    fun recordEfficiency(value: Double) {
        if (value.isNaN() || value.isInfinite() || value <= 0.0) return
        prefs.edit().putString(KEY_EFFICIENCY, dailyUpsert(KEY_EFFICIENCY, "value", value).toString()).apply()
    }

    /**
     * Adds a replacement to the maintenance log (S6 정비 기록) and returns its key.
     * [daysAgo] is 0 for "just now"; [odometerKm] is the page's estimate for that day, or negative when unknown.
     * [how] is "diy" or "shop" when the driver entered it, "car" when the car's own warning confirmed it.
     */
    @Synchronized
    fun recordReplacement(itemId: String, daysAgo: Int, odometerKm: Double, how: String): Long {
        val now = System.currentTimeMillis()
        val log = readLog()
        // Keys order the entries and identify one for undo or deletion, so they must be unique.
        var key = now
        while ((0 until log.length()).any { log.optJSONObject(it)?.optLong("key") == key }) key++
        val entry = snapshot(now - daysAgo.coerceIn(0, 3650) * DAY_MS)
            .put("key", key).put("id", itemId).put("how", how)
        if (!odometerKm.isNaN() && !odometerKm.isInfinite() && odometerKm >= 0.0) entry.put("odo", odometerKm)
        log.put(entry)
        while (log.length() > MAX_LOG) log.remove(0)
        val editor = prefs.edit().putString(KEY_LOG, log.toString())
        applyCurrentRecord(editor, log, itemId)
        editor.apply()
        return key
    }

    /** Removes a log entry (undo, or a record entered by mistake); the item falls back to its previous record. */
    @Synchronized
    fun deleteRecord(key: Long) {
        val log = readLog()
        val index = (0 until log.length()).firstOrNull { log.optJSONObject(it)?.optLong("key") == key } ?: return
        val itemId = log.optJSONObject(index)?.optString("id").orEmpty()
        log.remove(index)
        val editor = prefs.edit().putString(KEY_LOG, log.toString())
        if (itemId.isNotEmpty()) applyCurrentRecord(editor, log, itemId)
        editor.apply()
    }

    /**
     * Remembers the car's last low-level warning for [itemId] and returns true when a warning
     * that was on has gone off, i.e. the fluid was topped up. Kept across restarts, so a refill
     * made while the app was closed is caught the next time it reads the car.
     */
    @Synchronized
    fun warningCleared(itemId: String, low: Boolean): Boolean {
        val key = KEY_WARNING_PREFIX + itemId
        val wasLow = prefs.getBoolean(key, false)
        if (!prefs.contains(key) || wasLow != low) prefs.edit().putBoolean(key, low).apply()
        return wasLow && !low
    }

    /** The item's current record is its latest entry by date, so an older entry added later doesn't replace it. */
    private fun applyCurrentRecord(editor: SharedPreferences.Editor, log: JSONArray, itemId: String) {
        val latest = (0 until log.length()).mapNotNull { log.optJSONObject(it) }
            .filter { it.optString("id") == itemId }
            .maxByOrNull { it.optLong("at") }
        if (latest == null) {
            editor.remove(KEY_RECORD_PREFIX + itemId)
            return
        }
        val record = JSONObject().put("at", latest.optLong("at"))
            .put("hvac", latest.optLong("hvac")).put("wiper", latest.optLong("wiper")).put("brake", latest.optLong("brake"))
        if (latest.has("odo")) record.put("odo", latest.optDouble("odo"))
        editor.putString(KEY_RECORD_PREFIX + itemId, record.toString())
    }

    private fun readLog(): JSONArray = JSONArray(prefs.getString(KEY_LOG, null) ?: "[]")

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
        json.put("log", readLog())
        json.put("charges", JSONArray(prefs.getString(KEY_CHARGES, null) ?: "[]"))
        json.put("lowSoc", JSONArray(prefs.getString(KEY_LOWS, null) ?: "[]"))
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
     * Version 1 reset the counters on each replacement and kept only a "replaced_<id>" time;
     * a zero snapshot at that time reads the same way now. Version 1.1 log entries had no key
     * or counters; they get a key and the counters of the item's record from the same day.
     */
    private fun migrate() {
        val editor = prefs.edit()
        if (!prefs.contains(KEY_START_AT)) editor.putLong(KEY_START_AT, System.currentTimeMillis())
        val log = readLog()
        var changed = false
        for ((key, value) in prefs.all.filterKeys { it.startsWith(OLD_REPLACED_PREFIX) }) {
            val at = value as? Long ?: continue
            log.put(JSONObject().put("id", key.removePrefix(OLD_REPLACED_PREFIX)).put("at", at).put("how", "diy")
                .put("hvac", 0L).put("wiper", 0L).put("brake", 0L))
            editor.remove(key)
            changed = true
        }
        val used = HashSet<Long>()
        for (i in 0 until log.length()) {
            val entry = log.optJSONObject(i) ?: continue
            if (entry.has("key")) { used.add(entry.optLong("key")); continue }
            var key = entry.optLong("at")
            while (!used.add(key)) key++
            entry.put("key", key)
            if (!entry.has("hvac")) {
                val record = prefs.getString(KEY_RECORD_PREFIX + entry.optString("id"), null)
                    ?.let { runCatching { JSONObject(it) }.getOrNull() }
                    ?.takeIf { it.optLong("at") == entry.optLong("at") }
                entry.put("hvac", record?.optLong("hvac") ?: 0L).put("wiper", record?.optLong("wiper") ?: 0L)
                    .put("brake", record?.optLong("brake") ?: 0L)
                if (record?.has("odo") == true) entry.put("odo", record.optDouble("odo"))
            }
            changed = true
        }
        if (changed) {
            editor.putString(KEY_LOG, log.toString())
            val ids = (0 until log.length()).mapNotNull { log.optJSONObject(it)?.optString("id") }.toSet()
            ids.forEach { applyCurrentRecord(editor, log, it) }
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
        private const val KEY_WARNING_PREFIX = "low_warning_"
        private const val KEY_SOC_REF = "soc_reference"
        private const val KEY_CHARGES = "charge_sessions"
        private const val KEY_LOWS = "deep_discharges"
        private const val LOW_SOC = 10.0
        private const val SESSION_GAP_MS = 3 * 3_600_000L
        private const val MAX_CHARGES = 80
        private const val OLD_REPLACED_PREFIX = "replaced_"
        private const val MAX_DAYS = 120
        private const val MAX_LOG = 60
        private const val DAY_MS = 86_400_000L
    }
}

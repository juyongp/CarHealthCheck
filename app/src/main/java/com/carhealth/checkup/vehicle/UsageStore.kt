package com.carhealth.checkup.vehicle

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Usage the app accumulates itself, because the SDK has no direct value for it:
 * air-conditioning hours (cabin filter), wiper hours, brake presses (pad wear estimate)
 * and a daily history of the car's cumulative efficiency.
 */
class UsageStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("car_health_usage", Context.MODE_PRIVATE)

    @Synchronized
    fun addHvacSeconds(seconds: Int) = addLong(KEY_HVAC, seconds.toLong())

    @Synchronized
    fun addWiperSeconds(seconds: Int) = addLong(KEY_WIPER, seconds.toLong())

    @Synchronized
    fun addBrakePress() = addLong(KEY_BRAKE, 1L)

    @Synchronized
    fun recordEfficiency(value: Double) {
        if (value.isNaN() || value.isInfinite() || value <= 0.0) return
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val history = JSONArray(prefs.getString(KEY_EFFICIENCY, null) ?: "[]")
        val last = if (history.length() > 0) history.optJSONObject(history.length() - 1) else null
        if (last != null && last.optString("day") == today) {
            last.put("value", value)
        } else {
            history.put(JSONObject().put("day", today).put("value", value))
        }
        while (history.length() > MAX_DAYS) history.remove(0)
        prefs.edit().putString(KEY_EFFICIENCY, history.toString()).apply()
    }

    /** Called when the driver finishes a replacement in the app (S6 정비 기록). */
    @Synchronized
    fun recordReplacement(itemId: String) {
        val editor = prefs.edit()
        when (itemId) {
            "cabinFilter" -> editor.putLong(KEY_HVAC, 0L)
            "wiper" -> editor.putLong(KEY_WIPER, 0L)
            "brakeF", "brakeR" -> editor.putLong(KEY_BRAKE, 0L)
        }
        editor.putLong("replaced_$itemId", System.currentTimeMillis())
        editor.apply()
    }

    @Synchronized
    fun toJson(includeHvac: Boolean, includeWiper: Boolean, includeBrake: Boolean): JSONObject {
        val json = JSONObject()
        if (includeHvac) json.put("hvacSeconds", prefs.getLong(KEY_HVAC, 0L))
        if (includeWiper) json.put("wiperSeconds", prefs.getLong(KEY_WIPER, 0L))
        if (includeBrake) json.put("brakePresses", prefs.getLong(KEY_BRAKE, 0L))
        val efficiency = JSONArray()
        val history = JSONArray(prefs.getString(KEY_EFFICIENCY, null) ?: "[]")
        for (i in 0 until history.length()) {
            val value = history.optJSONObject(i)?.optDouble("value") ?: continue
            if (!value.isNaN() && !value.isInfinite()) efficiency.put(value)
        }
        json.put("efficiencyHistory", efficiency)
        return json
    }

    private fun addLong(key: String, delta: Long) {
        prefs.edit().putLong(key, prefs.getLong(key, 0L) + delta).apply()
    }

    companion object {
        private const val KEY_HVAC = "hvac_seconds"
        private const val KEY_WIPER = "wiper_seconds"
        private const val KEY_BRAKE = "brake_presses"
        private const val KEY_EFFICIENCY = "efficiency_history"
        private const val MAX_DAYS = 90
    }
}

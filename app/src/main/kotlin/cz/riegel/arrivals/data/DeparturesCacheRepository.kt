package cz.riegel.arrivals.data

import android.content.Context
import cz.riegel.arrivals.data.model.GolemioDepartureBoardResponse
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant

class DeparturesCacheRepository(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_CACHE_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    fun saveLastResponse(response: GolemioDepartureBoardResponse, timestamp: Instant) {
        try {
            val jsonString = json.encodeToString(response)
            prefs.edit()
                .putString(KEY_CACHED_PAYLOAD, jsonString)
                .putLong(KEY_CACHED_TIME, timestamp.toEpochMilli())
                .apply()
        } catch (e: Exception) {
            // Logging or no-op
        }
    }

    fun getLastResponse(): Pair<GolemioDepartureBoardResponse, Instant>? {
        val jsonString = prefs.getString(KEY_CACHED_PAYLOAD, null) ?: return null
        val epochMilli = prefs.getLong(KEY_CACHED_TIME, 0L)
        if (epochMilli == 0L) return null

        return try {
            val response = json.decodeFromString<GolemioDepartureBoardResponse>(jsonString)
            Pair(response, Instant.ofEpochMilli(epochMilli))
        } catch (e: Exception) {
            null
        }
    }

    fun saveLastCoordinates(coords: UserCoordinates) {
        prefs.edit()
            .putString(KEY_LAT, coords.lat.toString())
            .putString(KEY_LON, coords.lon.toString())
            .putLong(KEY_COORDS_TIME, coords.timestamp.toEpochMilli())
            .apply()
    }

    fun getLastCoordinates(): UserCoordinates? {
        val latStr = prefs.getString(KEY_LAT, null) ?: return null
        val lonStr = prefs.getString(KEY_LON, null) ?: return null
        val timeMilli = prefs.getLong(KEY_COORDS_TIME, 0L)
        if (timeMilli == 0L) return null

        val lat = latStr.toDoubleOrNull() ?: return null
        val lon = lonStr.toDoubleOrNull() ?: return null

        return UserCoordinates(
            lat = lat,
            lon = lon,
            timestamp = Instant.ofEpochMilli(timeMilli)
        )
    }

    fun saveActiveSelection(stopIndex: Int, platformCode: String, pickerMode: String = "Departures") {
        prefs.edit()
            .putInt(KEY_ACTIVE_STOP_INDEX, stopIndex)
            .putString(KEY_ACTIVE_PLATFORM_CODE, platformCode)
            .putString(KEY_ACTIVE_PICKER_MODE, pickerMode)
            .apply()
    }

    fun getActiveStopIndex(): Int {
        return prefs.getInt(KEY_ACTIVE_STOP_INDEX, 0)
    }

    fun getActivePlatformCode(): String? {
        return prefs.getString(KEY_ACTIVE_PLATFORM_CODE, null)
    }

    fun getActivePickerMode(): String? {
        return prefs.getString(KEY_ACTIVE_PICKER_MODE, null)
    }

    companion object {
        private const val PREFS_CACHE_NAME = "arrivals_cache_prefs"
        private const val KEY_CACHED_PAYLOAD = "cached_payload"
        private const val KEY_CACHED_TIME = "cached_time"
        private const val KEY_LAT = "last_lat"
        private const val KEY_LON = "last_lon"
        private const val KEY_COORDS_TIME = "coords_time"
        private const val KEY_ACTIVE_STOP_INDEX = "active_stop_index"
        private const val KEY_ACTIVE_PLATFORM_CODE = "active_platform_code"
        private const val KEY_ACTIVE_PICKER_MODE = "active_picker_mode"
    }
}

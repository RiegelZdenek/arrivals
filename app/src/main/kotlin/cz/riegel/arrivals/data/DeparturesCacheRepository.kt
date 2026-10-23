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

    companion object {
        private const val PREFS_CACHE_NAME = "arrivals_cache_prefs"
        private const val KEY_CACHED_PAYLOAD = "cached_payload"
        private const val KEY_CACHED_TIME = "cached_time"
        private const val KEY_LAT = "last_lat"
        private const val KEY_LON = "last_lon"
        private const val KEY_COORDS_TIME = "coords_time"
    }
}

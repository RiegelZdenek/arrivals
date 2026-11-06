package cz.riegel.arrivals.data

import android.content.Context
import cz.riegel.arrivals.data.model.GolemioDepartureBoardResponse
import cz.riegel.arrivals.domain.model.Coordinates
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

sealed interface LocationResolution {
    val coordinates: Coordinates
    data class Fresh(override val coordinates: Coordinates) : LocationResolution
    data class UndergroundFallback(
        override val coordinates: Coordinates,
        val warningBanner: String?
    ) : LocationResolution
}

data class CachedDeparturesResult(
    val response: GolemioDepartureBoardResponse,
    val coordinates: Coordinates?,
    val warningBanner: String
)

interface DeparturesRepository {
    fun resolveLocation(
        freshCoordinates: Coordinates?,
        currentTime: Instant = Instant.now()
    ): LocationResolution?

    fun saveLocation(coordinates: Coordinates, observedAt: Instant = Instant.now())
    fun getLastCoordinates(): Coordinates?

    fun saveDepartures(response: GolemioDepartureBoardResponse, timestamp: Instant = Instant.now())
    fun getCachedDepartures(): CachedDeparturesResult?

    fun saveActiveSelection(stopIndex: Int, platformCode: String)
    fun getActiveStopIndex(): Int
    fun getActivePlatformCode(): String?

    companion object {
        const val UNDERGROUND_WARNING_BANNER = "Underground GPS fallback • Last known location"
        private const val UNDERGROUND_BANNER_MIN_AGE_MINUTES = 5L
        private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")

        fun create(
            context: Context,
            maxStaleDurationMinutes: Long = 15,
            zoneId: ZoneId = ZoneId.of("Europe/Prague")
        ): DeparturesRepository = SharedPreferencesDeparturesRepository(
            context = context,
            maxStaleDurationMinutes = maxStaleDurationMinutes,
            zoneId = zoneId
        )

        internal fun resolveWithPolicy(
            freshCoordinates: Coordinates?,
            cachedLocation: Pair<Coordinates, Instant>?,
            currentTime: Instant,
            maxStaleDurationMinutes: Long,
            onSaveFresh: (Coordinates, Instant) -> Unit
        ): LocationResolution? {
            if (freshCoordinates != null) {
                onSaveFresh(freshCoordinates, currentTime)
                return LocationResolution.Fresh(freshCoordinates)
            }
            val (cachedCoords, cachedTime) = cachedLocation ?: return null
            val ageMinutes = Duration.between(cachedTime, currentTime).toMinutes()
            if (ageMinutes <= maxStaleDurationMinutes) {
                val banner = if (ageMinutes >= UNDERGROUND_BANNER_MIN_AGE_MINUTES) {
                    UNDERGROUND_WARNING_BANNER
                } else {
                    null
                }
                return LocationResolution.UndergroundFallback(cachedCoords, banner)
            }
            return null
        }

        internal fun formatCachedWarning(timestamp: Instant, zoneId: ZoneId): String {
            val timeStr = timeFormatter.format(timestamp.atZone(zoneId))
            return "Cached $timeStr • Tap to retry"
        }
    }
}

class SharedPreferencesDeparturesRepository(
    context: Context,
    private val maxStaleDurationMinutes: Long = 15,
    private val zoneId: ZoneId = ZoneId.of("Europe/Prague")
) : DeparturesRepository {

    private val prefs = context.getSharedPreferences(PREFS_CACHE_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    override fun resolveLocation(
        freshCoordinates: Coordinates?,
        currentTime: Instant
    ): LocationResolution? = DeparturesRepository.resolveWithPolicy(
        freshCoordinates = freshCoordinates,
        cachedLocation = getSavedLocationWithTimestamp(),
        currentTime = currentTime,
        maxStaleDurationMinutes = maxStaleDurationMinutes,
        onSaveFresh = ::saveLocation
    )

    override fun saveLocation(coordinates: Coordinates, observedAt: Instant) {
        prefs.edit()
            .putString(KEY_LAT, coordinates.lat.toString())
            .putString(KEY_LON, coordinates.lon.toString())
            .putLong(KEY_COORDS_TIME, observedAt.toEpochMilli())
            .apply()
    }

    override fun getLastCoordinates(): Coordinates? = getSavedLocationWithTimestamp()?.first

    private fun getSavedLocationWithTimestamp(): Pair<Coordinates, Instant>? {
        val latStr = prefs.getString(KEY_LAT, null) ?: return null
        val lonStr = prefs.getString(KEY_LON, null) ?: return null
        val timeMilli = prefs.getLong(KEY_COORDS_TIME, 0L)
        if (timeMilli == 0L) return null

        val lat = latStr.toDoubleOrNull() ?: return null
        val lon = lonStr.toDoubleOrNull() ?: return null

        return Pair(Coordinates(lat = lat, lon = lon), Instant.ofEpochMilli(timeMilli))
    }

    override fun saveDepartures(response: GolemioDepartureBoardResponse, timestamp: Instant) {
        try {
            val jsonString = json.encodeToString(response)
            prefs.edit()
                .putString(KEY_CACHED_PAYLOAD, jsonString)
                .putLong(KEY_CACHED_TIME, timestamp.toEpochMilli())
                .apply()
        } catch (e: Exception) {
            // Ignore serialization failures
        }
    }

    override fun getCachedDepartures(): CachedDeparturesResult? {
        val jsonString = prefs.getString(KEY_CACHED_PAYLOAD, null) ?: return null
        val epochMilli = prefs.getLong(KEY_CACHED_TIME, 0L)
        if (epochMilli == 0L) return null

        return try {
            val response = json.decodeFromString<GolemioDepartureBoardResponse>(jsonString)
            val timestamp = Instant.ofEpochMilli(epochMilli)
            CachedDeparturesResult(
                response = response,
                coordinates = getLastCoordinates(),
                warningBanner = DeparturesRepository.formatCachedWarning(timestamp, zoneId)
            )
        } catch (e: Exception) {
            null
        }
    }

    override fun saveActiveSelection(stopIndex: Int, platformCode: String) {
        prefs.edit()
            .putInt(KEY_ACTIVE_STOP_INDEX, stopIndex)
            .putString(KEY_ACTIVE_PLATFORM_CODE, platformCode)
            .apply()
    }

    override fun getActiveStopIndex(): Int = prefs.getInt(KEY_ACTIVE_STOP_INDEX, 0)

    override fun getActivePlatformCode(): String? = prefs.getString(KEY_ACTIVE_PLATFORM_CODE, null)

    companion object {
        internal const val PREFS_CACHE_NAME = "arrivals_cache_prefs"
        private const val KEY_CACHED_PAYLOAD = "cached_payload"
        private const val KEY_CACHED_TIME = "cached_time"
        private const val KEY_LAT = "last_lat"
        private const val KEY_LON = "last_lon"
        private const val KEY_COORDS_TIME = "coords_time"
        private const val KEY_ACTIVE_STOP_INDEX = "active_stop_index"
        private const val KEY_ACTIVE_PLATFORM_CODE = "active_platform_code"
    }
}

class InMemoryDeparturesRepository(
    private val maxStaleDurationMinutes: Long = 15,
    private val zoneId: ZoneId = ZoneId.of("Europe/Prague")
) : DeparturesRepository {

    private var savedLocation: Pair<Coordinates, Instant>? = null
    private var savedDepartures: Pair<GolemioDepartureBoardResponse, Instant>? = null
    private var activeStopIndex: Int = 0
    private var activePlatformCode: String? = null

    override fun resolveLocation(
        freshCoordinates: Coordinates?,
        currentTime: Instant
    ): LocationResolution? = DeparturesRepository.resolveWithPolicy(
        freshCoordinates = freshCoordinates,
        cachedLocation = savedLocation,
        currentTime = currentTime,
        maxStaleDurationMinutes = maxStaleDurationMinutes,
        onSaveFresh = ::saveLocation
    )

    override fun saveLocation(coordinates: Coordinates, observedAt: Instant) {
        savedLocation = Pair(coordinates, observedAt)
    }

    override fun getLastCoordinates(): Coordinates? = savedLocation?.first

    override fun saveDepartures(response: GolemioDepartureBoardResponse, timestamp: Instant) {
        savedDepartures = Pair(response, timestamp)
    }

    override fun getCachedDepartures(): CachedDeparturesResult? {
        val (response, timestamp) = savedDepartures ?: return null
        return CachedDeparturesResult(
            response = response,
            coordinates = getLastCoordinates(),
            warningBanner = DeparturesRepository.formatCachedWarning(timestamp, zoneId)
        )
    }

    override fun saveActiveSelection(stopIndex: Int, platformCode: String) {
        activeStopIndex = stopIndex
        activePlatformCode = platformCode
    }

    override fun getActiveStopIndex(): Int = activeStopIndex

    override fun getActivePlatformCode(): String? = activePlatformCode
}

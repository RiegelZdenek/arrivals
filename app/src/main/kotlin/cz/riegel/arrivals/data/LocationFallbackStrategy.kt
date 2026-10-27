package cz.riegel.arrivals.data

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class UserCoordinates(
    val lat: Double,
    val lon: Double,
    val timestamp: Instant,
    val isStaleUndergroundFallback: Boolean = false,
    val accuracyMeters: Float? = null
)

class LocationFallbackStrategy(
    private val maxStaleDurationMinutes: Long = 30
) {
    fun resolveCoordinates(
        freshLocation: UserCoordinates?,
        cachedLocation: UserCoordinates?,
        currentTime: Instant = Instant.now()
    ): UserCoordinates? {
        if (freshLocation != null) {
            return freshLocation.copy(isStaleUndergroundFallback = false)
        }

        if (cachedLocation != null) {
            val ageMinutes = Duration.between(cachedLocation.timestamp, currentTime).toMinutes()
            if (ageMinutes <= maxStaleDurationMinutes) {
                return cachedLocation.copy(isStaleUndergroundFallback = true)
            }
        }

        return null
    }
}

object CacheResilience {
    private val zoneId = ZoneId.of("Europe/Prague")
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    fun formatCachedWarning(timestamp: Instant): String {
        val timeStr = timeFormatter.format(timestamp.atZone(zoneId))
        return "Cached $timeStr • Tap to retry"
    }
}

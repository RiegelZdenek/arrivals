package cz.riegel.arrivals.domain.model

import java.time.Instant

data class Coordinates(
    val lat: Double,
    val lon: Double
)

enum class TransportMode(val symbol: String) {
    Metro("Ⓜ"),
    Tram("🚊"),
    Bus("🚌"),
    Trolleybus("🚎")
}

data class Line(
    val name: String,
    val transportMode: TransportMode
)

data class Delay(
    val minutes: Int = 0,
    val isAvailable: Boolean = false
) {
    val isDelayed: Boolean
        get() = isAvailable && minutes > 0

    val badgeText: String?
        get() = if (isDelayed) "+$minutes min" else null
}

data class CountdownDisplay(
    val minutesRemaining: Long,
    val displayText: String,
    val isClockTime: Boolean
)

data class Departure(
    val id: String,
    val line: Line,
    val headsign: String,
    val scheduledTime: Instant,
    val predictedTime: Instant,
    val countdown: CountdownDisplay,
    val delay: Delay,
    val isWheelchairAccessible: Boolean = false
) {
    val terminus: String
        get() = headsign
}

data class Platform(
    val id: String,
    val code: String, // e.g., "A", "B", or Terminus fallback "→ Centrum"
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val departures: List<Departure> = emptyList(),
    val distanceMeters: Int? = null
) {
    val distinctTransportModes: List<TransportMode>
        get() = departures.map { it.line.transportMode }.distinct().sorted()

    val transportModeSymbols: List<String>
        get() = distinctTransportModes.map { it.symbol }

    val distinctTerminuses: List<String>
        get() = departures
            .map { it.terminus.removePrefix("→").trim() }
            .filter { it.isNotEmpty() }
            .distinct()

    val terminusSummary: String?
        get() = distinctTerminuses.takeIf { it.isNotEmpty() }?.joinToString(", ")
}

data class Stop(
    val id: String,
    val name: String,
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val distanceMeters: Int? = null,
    val platforms: List<Platform> = emptyList()
) {
    val distinctTransportModes: List<TransportMode>
        get() = platforms
            .flatMap { it.departures }
            .map { it.line.transportMode }
            .distinct()
            .sorted()

    val transportModeSymbols: List<String>
        get() = distinctTransportModes.map { it.symbol }
}

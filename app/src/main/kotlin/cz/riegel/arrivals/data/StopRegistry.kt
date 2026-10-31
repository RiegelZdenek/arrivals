package cz.riegel.arrivals.data

import cz.riegel.arrivals.domain.model.Coordinates
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.InputStream

@Serializable
internal data class CompactStop(
    val n: String,
    val lat: Double,
    val lon: Double
)

internal class StopRegistry(
    private val stopsStreamProvider: (() -> InputStream)? = null
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val stops: List<CompactStop> by lazy {
        loadStops()
    }

    private fun loadStops(): List<CompactStop> {
        val provider = stopsStreamProvider ?: return defaultPragueHubs
        return try {
            provider().use { stream ->
                val content = stream.bufferedReader(Charsets.UTF_8).readText().removePrefix("\uFEFF")
                json.decodeFromString<List<CompactStop>>(content)
            }
        } catch (e: Exception) {
            defaultPragueHubs
        }
    }

    fun findNearbyStopNames(userLat: Double, userLon: Double, radiusMeters: Int): List<String> {
        val userCoords = Coordinates(userLat, userLon)
        return stops.asSequence()
            .map { stop ->
                val dist = userCoords.distanceTo(Coordinates(stop.lat, stop.lon))
                stop.n to dist
            }
            .filter { (_, dist) -> dist <= radiusMeters }
            .sortedBy { (_, dist) -> dist }
            .map { (name, _) -> name }
            .distinct()
            .take(5)
            .toList()
    }

    fun findClosestStop(userLat: Double, userLon: Double): Pair<CompactStop, Int>? {
        if (stops.isEmpty()) return null
        val userCoords = Coordinates(userLat, userLon)
        return stops.asSequence()
            .map { stop ->
                val dist = userCoords.distanceTo(Coordinates(stop.lat, stop.lon))
                stop to dist
            }
            .minByOrNull { it.second }
    }

    companion object {
        fun isWithinPragueCoverage(lat: Double, lon: Double): Boolean {
            return lat in 49.70..50.50 && lon in 14.00..15.00
        }

        val defaultPragueHubs = listOf(
            CompactStop("I. P. Pavlova", 50.0754, 14.4301),
            CompactStop("Karlovo náměstí", 50.0760, 14.4200),
            CompactStop("Anděl", 50.0711, 14.4038),
            CompactStop("Hlavní nádraží", 50.0831, 14.4352),
            CompactStop("Můstek", 50.0841, 14.4243),
            CompactStop("Muzeum", 50.0798, 14.4306),
            CompactStop("Náměstí Míru", 50.0755, 14.4373),
            CompactStop("Florenc", 50.0906, 14.4395),
            CompactStop("Dejvická", 50.1005, 14.3941),
            CompactStop("Hradčanská", 50.0967, 14.4037)
        )
    }
}

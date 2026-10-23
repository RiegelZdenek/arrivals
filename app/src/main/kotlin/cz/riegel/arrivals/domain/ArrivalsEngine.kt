package cz.riegel.arrivals.domain

import cz.riegel.arrivals.domain.model.Platform
import cz.riegel.arrivals.domain.model.Stop
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class ArrivalsEngine {

    val adaptiveSearchRadii: List<Int> = listOf(100, 250, 500)

    fun rankStopsByProximity(
        stops: List<Stop>,
        userLat: Double,
        userLon: Double
    ): List<Stop> {
        return stops.map { stop ->
            val dist = calculateDistanceMeters(userLat, userLon, stop.lat, stop.lon)
            val rankedPlatforms = stop.platforms.map { platform ->
                if (platform.lat != 0.0 && platform.lon != 0.0) {
                    platform
                } else {
                    platform.copy(lat = stop.lat, lon = stop.lon)
                }
            }
            stop.copy(distanceMeters = dist, platforms = rankedPlatforms)
        }.sortedBy { it.distanceMeters ?: Int.MAX_VALUE }
    }

    fun findClosestPlatform(
        stop: Stop,
        userLat: Double,
        userLon: Double
    ): Platform? {
        if (stop.platforms.isEmpty()) return null
        return stop.platforms.minByOrNull { platform ->
            val pLat = if (platform.lat != 0.0) platform.lat else stop.lat
            val pLon = if (platform.lon != 0.0) platform.lon else stop.lon
            calculateDistanceMeters(userLat, userLon, pLat, pLon)
        }
    }

    fun reduce(state: ArrivalsViewState, action: ArrivalsAction): ArrivalsViewState {
        return when (action) {
            is ArrivalsAction.NextStop -> {
                if (state !is ArrivalsViewState.Content || state.stops.isEmpty()) return state
                val nextIndex = (state.activeStopIndex + 1) % state.stops.size
                val nextStop = state.stops[nextIndex]
                val defaultPlatform = nextStop.platforms.firstOrNull()?.code.orEmpty()
                state.copy(activeStopIndex = nextIndex, activePlatformCode = defaultPlatform)
            }
            is ArrivalsAction.PreviousStop -> {
                if (state !is ArrivalsViewState.Content || state.stops.isEmpty()) return state
                val prevIndex = if (state.activeStopIndex - 1 < 0) state.stops.size - 1 else state.activeStopIndex - 1
                val prevStop = state.stops[prevIndex]
                val defaultPlatform = prevStop.platforms.firstOrNull()?.code.orEmpty()
                state.copy(activeStopIndex = prevIndex, activePlatformCode = defaultPlatform)
            }
            is ArrivalsAction.SelectPlatform -> {
                if (state !is ArrivalsViewState.Content) return state
                state.copy(activePlatformCode = action.code)
            }
            is ArrivalsAction.UpdateData -> {
                if (action.stops.isEmpty()) {
                    return ArrivalsViewState.Empty()
                }
                val rankedStops = rankStopsByProximity(action.stops, action.userLat, action.userLon)
                val primaryStop = rankedStops.first()
                val closestPlatform = findClosestPlatform(primaryStop, action.userLat, action.userLon)
                ArrivalsViewState.Content(
                    stops = rankedStops,
                    activeStopIndex = 0,
                    activePlatformCode = closestPlatform?.code ?: primaryStop.platforms.firstOrNull()?.code.orEmpty(),
                    cachedWarning = action.cachedWarning
                )
            }
            is ArrivalsAction.Refresh -> {
                when (state) {
                    is ArrivalsViewState.Content -> state // retain content while refresh worker runs
                    else -> ArrivalsViewState.Loading()
                }
            }
        }
    }

    fun calculateDistanceMeters(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double
    ): Int {
        val earthRadius = 6371000.0 // meters
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return (earthRadius * c).toInt()
    }
}

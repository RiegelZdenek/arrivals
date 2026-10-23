package cz.riegel.arrivals.domain

import cz.riegel.arrivals.domain.model.Coordinates
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
        userCoords: Coordinates
    ): List<Stop> {
        return stops.map { stop ->
            val dist = calculateDistanceMeters(userCoords, Coordinates(stop.lat, stop.lon))
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
        userCoords: Coordinates?
    ): Platform? {
        if (stop.platforms.isEmpty()) return null
        if (userCoords == null) return stop.platforms.firstOrNull()

        return stop.platforms.minByOrNull { platform ->
            val pLat = if (platform.lat != 0.0) platform.lat else stop.lat
            val pLon = if (platform.lon != 0.0) platform.lon else stop.lon
            calculateDistanceMeters(userCoords, Coordinates(pLat, pLon))
        }
    }

    fun reduce(state: ArrivalsViewState, action: ArrivalsAction): ArrivalsViewState {
        return when (action) {
            is ArrivalsAction.NextStop -> {
                if (state !is ArrivalsViewState.Content || state.stops.isEmpty()) return state
                val nextIndex = (state.activeStopIndex + 1) % state.stops.size
                val nextStop = state.stops[nextIndex]
                val closestPlatform = findClosestPlatform(nextStop, state.userCoordinates)
                val defaultPlatform = closestPlatform?.code ?: nextStop.platforms.firstOrNull()?.code.orEmpty()
                state.copy(activeStopIndex = nextIndex, activePlatformCode = defaultPlatform)
            }
            is ArrivalsAction.PreviousStop -> {
                if (state !is ArrivalsViewState.Content || state.stops.isEmpty()) return state
                val prevIndex = if (state.activeStopIndex - 1 < 0) state.stops.size - 1 else state.activeStopIndex - 1
                val prevStop = state.stops[prevIndex]
                val closestPlatform = findClosestPlatform(prevStop, state.userCoordinates)
                val defaultPlatform = closestPlatform?.code ?: prevStop.platforms.firstOrNull()?.code.orEmpty()
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
                val rankedStops = if (action.userCoordinates != null) {
                    rankStopsByProximity(action.stops, action.userCoordinates)
                } else {
                    action.stops
                }
                val primaryStop = rankedStops.first()
                val closestPlatform = findClosestPlatform(primaryStop, action.userCoordinates)
                ArrivalsViewState.Content(
                    stops = rankedStops,
                    activeStopIndex = 0,
                    activePlatformCode = closestPlatform?.code ?: primaryStop.platforms.firstOrNull()?.code.orEmpty(),
                    userCoordinates = action.userCoordinates,
                    cachedWarning = action.cachedWarning
                )
            }
            is ArrivalsAction.Refresh -> {
                when (state) {
                    is ArrivalsViewState.Content -> state
                    else -> ArrivalsViewState.Loading()
                }
            }
        }
    }

    fun calculateDistanceMeters(
        from: Coordinates,
        to: Coordinates
    ): Int {
        val earthRadius = 6371000.0 // meters
        val dLat = Math.toRadians(to.lat - from.lat)
        val dLon = Math.toRadians(to.lon - from.lon)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(from.lat)) * cos(Math.toRadians(to.lat)) *
                sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return (earthRadius * c).toInt()
    }
}

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
                val pLat = if (platform.lat != 0.0) platform.lat else stop.lat
                val pLon = if (platform.lon != 0.0) platform.lon else stop.lon
                val pDist = if (pLat != 0.0 && pLon != 0.0) {
                    calculateDistanceMeters(userCoords, Coordinates(pLat, pLon))
                } else {
                    dist
                }
                platform.copy(lat = pLat, lon = pLon, distanceMeters = pDist)
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

    private fun resolveDefaultPlatformCode(
        stop: Stop,
        userCoords: Coordinates?
    ): String {
        return findClosestPlatform(stop, userCoords)?.code
            ?: stop.platforms.firstOrNull()?.code.orEmpty()
    }

    fun reduce(state: ArrivalsViewState, action: ArrivalsAction): ArrivalsViewState {
        return when (action) {
            is ArrivalsAction.OpenStopPicker -> {
                if (state !is ArrivalsViewState.Content || !state.hasMultipleStops) return state
                state.copy(pickerMode = PickerMode.StopPicker)
            }
            is ArrivalsAction.OpenPlatformPicker -> {
                if (state !is ArrivalsViewState.Content || !state.hasMultiplePlatforms) return state
                state.copy(pickerMode = PickerMode.PlatformPicker)
            }
            is ArrivalsAction.ClosePicker -> {
                if (state !is ArrivalsViewState.Content) return state
                state.copy(pickerMode = PickerMode.Departures)
            }
            is ArrivalsAction.SelectStop -> {
                if (state !is ArrivalsViewState.Content || state.stops.isEmpty()) return state
                if (action.stopIndex !in state.stops.indices) {
                    return state.copy(pickerMode = PickerMode.Departures)
                }
                val selectedStop = state.stops[action.stopIndex]
                val defaultPlatform = resolveDefaultPlatformCode(selectedStop, state.userCoordinates)
                state.copy(
                    activeStopIndex = action.stopIndex,
                    activePlatformCode = defaultPlatform,
                    pickerMode = PickerMode.Departures
                )
            }
            is ArrivalsAction.SelectPlatform -> {
                if (state !is ArrivalsViewState.Content) return state
                state.copy(
                    activePlatformCode = action.code,
                    pickerMode = PickerMode.Departures
                )
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

                if (state is ArrivalsViewState.Content && state.stops.isNotEmpty()) {
                    val previousStop = state.activeStop
                    val matchedStopIndex = rankedStops.indexOfFirst {
                        it.id == previousStop.id || it.name == previousStop.name
                    }
                    val resolvedStopIndex = if (matchedStopIndex >= 0) matchedStopIndex else 0
                    val resolvedStop = rankedStops[resolvedStopIndex]
                    val resolvedPlatformCode = if (
                        matchedStopIndex >= 0 &&
                        resolvedStop.platforms.any { it.code == state.activePlatformCode }
                    ) {
                        state.activePlatformCode
                    } else {
                        resolveDefaultPlatformCode(resolvedStop, action.userCoordinates)
                    }
                    val resolvedPickerMode = when (state.pickerMode) {
                        PickerMode.StopPicker -> {
                            if (rankedStops.size > 1) PickerMode.StopPicker else PickerMode.Departures
                        }
                        PickerMode.PlatformPicker -> {
                            if (resolvedStop.platforms.size > 1) PickerMode.PlatformPicker else PickerMode.Departures
                        }
                        PickerMode.Departures -> PickerMode.Departures
                    }
                    return ArrivalsViewState.Content(
                        stops = rankedStops,
                        activeStopIndex = resolvedStopIndex,
                        activePlatformCode = resolvedPlatformCode,
                        userCoordinates = action.userCoordinates,
                        cachedWarning = action.cachedWarning,
                        pickerMode = resolvedPickerMode
                    )
                }

                val primaryStop = rankedStops.first()
                ArrivalsViewState.Content(
                    stops = rankedStops,
                    activeStopIndex = 0,
                    activePlatformCode = resolveDefaultPlatformCode(primaryStop, action.userCoordinates),
                    userCoordinates = action.userCoordinates,
                    cachedWarning = action.cachedWarning
                )
            }
            is ArrivalsAction.Refresh -> {
                when (state) {
                    is ArrivalsViewState.Content -> state.copy(isRefreshing = true)
                    else -> ArrivalsViewState.Loading()
                }
            }
            is ArrivalsAction.SetRefreshing -> {
                when (state) {
                    is ArrivalsViewState.Content -> state.copy(isRefreshing = action.isRefreshing)
                    else -> state
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

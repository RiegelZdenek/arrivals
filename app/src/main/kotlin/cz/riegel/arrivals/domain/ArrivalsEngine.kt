package cz.riegel.arrivals.domain

import cz.riegel.arrivals.data.model.GolemioDepartureBoardResponse
import cz.riegel.arrivals.data.model.GolemioDepartureDto
import cz.riegel.arrivals.domain.model.Coordinates
import cz.riegel.arrivals.domain.model.CountdownDisplay
import cz.riegel.arrivals.domain.model.Delay
import cz.riegel.arrivals.domain.model.Departure
import cz.riegel.arrivals.domain.model.Line
import cz.riegel.arrivals.domain.model.Platform
import cz.riegel.arrivals.domain.model.Stop
import cz.riegel.arrivals.domain.model.TransportMode
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class ArrivalsEngine(
    private val zoneId: ZoneId = ZoneId.of("Europe/Prague")
) {

    private val clockTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

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
                val isDifferentStop = action.stopIndex != state.activeStopIndex
                val selectedStop = state.stops[action.stopIndex]
                val defaultPlatform = resolveDefaultPlatformCode(selectedStop, state.userCoordinates)
                state.copy(
                    activeStopIndex = action.stopIndex,
                    activePlatformCode = defaultPlatform,
                    pickerMode = PickerMode.Departures,
                    isLoadingDepartures = if (isDifferentStop) true else state.isLoadingDepartures,
                    isRefreshing = if (isDifferentStop) true else state.isRefreshing
                )
            }
            is ArrivalsAction.SelectPlatform -> {
                if (state !is ArrivalsViewState.Content) return state
                state.copy(
                    activePlatformCode = action.code,
                    pickerMode = PickerMode.Departures
                )
            }
            is ArrivalsAction.IngestApiResponse -> {
                val rawStops = normalize(action.response, action.referenceTime)
                if (rawStops.isEmpty()) {
                    if (state is ArrivalsViewState.Content && state.stops.isNotEmpty()) {
                        return state.copy(
                            stops = refreshCountdowns(state.stops, action.referenceTime),
                            isRefreshing = false,
                            isLoadingDepartures = false,
                            cachedWarning = action.cachedWarning ?: state.cachedWarning ?: "Unable to refresh departures"
                        )
                    }
                    return ArrivalsViewState.Empty()
                }

                val rankedStops = if (action.userCoordinates != null) {
                    rankStopsByProximity(rawStops, action.userCoordinates)
                } else {
                    rawStops
                }

                if (state is ArrivalsViewState.Content && state.stops.isNotEmpty()) {
                    val previousCoords = state.userCoordinates
                    val newCoords = action.userCoordinates
                    val isDisplaced = previousCoords != null && newCoords != null &&
                        calculateDistanceMeters(previousCoords, newCoords) > 50
                    val shouldSnapToClosest = state.userCoordinates == null || isDisplaced

                    val resolvedStopIndex: Int
                    val resolvedPlatformCode: String
                    val resolvedStop: Stop

                    if (shouldSnapToClosest) {
                        resolvedStopIndex = 0
                        resolvedStop = rankedStops.first()
                        resolvedPlatformCode = resolveDefaultPlatformCode(resolvedStop, action.userCoordinates)
                    } else {
                        val previousStop = state.activeStop
                        val matchedStopIndex = rankedStops.indexOfFirst {
                            it.id == previousStop.id || it.name == previousStop.name
                        }
                        resolvedStopIndex = if (matchedStopIndex >= 0) matchedStopIndex else 0
                        resolvedStop = rankedStops[resolvedStopIndex]
                        resolvedPlatformCode = if (
                            matchedStopIndex >= 0 &&
                            resolvedStop.platforms.any { it.code == state.activePlatformCode }
                        ) {
                            state.activePlatformCode
                        } else {
                            resolveDefaultPlatformCode(resolvedStop, action.userCoordinates)
                        }
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
                        isRefreshing = false,
                        isLoadingDepartures = false,
                        pickerMode = resolvedPickerMode
                    )
                }

                val primaryStop = rankedStops.first()
                ArrivalsViewState.Content(
                    stops = rankedStops,
                    activeStopIndex = 0,
                    activePlatformCode = resolveDefaultPlatformCode(primaryStop, action.userCoordinates),
                    userCoordinates = action.userCoordinates,
                    cachedWarning = action.cachedWarning,
                    isRefreshing = false,
                    isLoadingDepartures = false
                )
            }
            is ArrivalsAction.TickCountdown -> {
                if (state !is ArrivalsViewState.Content) return state
                state.copy(stops = refreshCountdowns(state.stops, action.referenceTime))
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

    private fun normalize(
        dto: GolemioDepartureBoardResponse,
        referenceTime: Instant
    ): List<Stop> {
        val stopsByIdOrName = dto.stops.associateBy { it.stopId }

        val departuresByStop = dto.departures.groupBy { dep ->
            stopsByIdOrName[dep.stop.id]?.stopName?.ifEmpty { null }
                ?: dep.stop.name.ifEmpty { null }
                ?: dto.stops.firstOrNull()?.stopName
                ?: "Zastávka"
        }

        val allStopNames = (departuresByStop.keys + dto.stops.mapNotNull { it.stopName.ifEmpty { null } })
            .distinct()

        return allStopNames.map { stopName ->
            val stopDepartures = departuresByStop[stopName].orEmpty()
            val stopDtosForStop = dto.stops.filter { it.stopName == stopName }

            val departuresByDisplayCode = stopDepartures.groupBy { dep ->
                val stopInfo = stopsByIdOrName[dep.stop.id]
                val platformCode = dep.stop.platformCode?.trim()?.ifEmpty { null }
                    ?: stopInfo?.platformCode?.trim()?.ifEmpty { null }
                platformCode ?: "→ ${dep.trip.headsign.trim()}"
            }

            val allPlatformCodes = (
                departuresByDisplayCode.keys +
                    stopDtosForStop.mapIndexed { idx, pStop ->
                        pStop.platformCode?.trim()?.ifEmpty { null }
                            ?: if (departuresByDisplayCode.isEmpty()) ('A' + idx).toString() else null
                    }.filterNotNull()
                ).distinct()

            val platforms = allPlatformCodes.map { displayCode ->
                val platformDepartures = departuresByDisplayCode[displayCode].orEmpty()
                val matchingStopDto = platformDepartures.firstNotNullOfOrNull { stopsByIdOrName[it.stop.id] }
                    ?: stopDtosForStop.firstOrNull { it.platformCode?.trim() == displayCode }
                    ?: stopDtosForStop.firstOrNull()

                val platformId = matchingStopDto?.stopId
                    ?: platformDepartures.firstOrNull()?.stop?.id?.ifEmpty { null }
                    ?: displayCode

                val normalizedDepartures = platformDepartures.mapNotNull { depDto ->
                    normalizeDeparture(depDto, referenceTime)
                }.filter { dep ->
                    isNotExpired(dep, referenceTime)
                }.sortedBy { it.predictedTime }

                Platform(
                    id = platformId,
                    code = displayCode,
                    lat = matchingStopDto?.stopLat ?: 0.0,
                    lon = matchingStopDto?.stopLon ?: 0.0,
                    departures = normalizedDepartures
                )
            }.sortedBy { it.code }

            val primaryStopInfo = stopDepartures.firstNotNullOfOrNull { stopsByIdOrName[it.stop.id] }
                ?: stopDtosForStop.firstOrNull()
            val avgLat = if (stopDtosForStop.isNotEmpty()) {
                stopDtosForStop.map { it.stopLat }.average()
            } else {
                primaryStopInfo?.stopLat ?: platforms.firstOrNull()?.lat ?: 0.0
            }
            val avgLon = if (stopDtosForStop.isNotEmpty()) {
                stopDtosForStop.map { it.stopLon }.average()
            } else {
                primaryStopInfo?.stopLon ?: platforms.firstOrNull()?.lon ?: 0.0
            }

            Stop(
                id = primaryStopInfo?.stopId ?: stopName,
                name = primaryStopInfo?.stopName ?: stopName,
                lat = avgLat,
                lon = avgLon,
                distanceMeters = null,
                platforms = platforms
            )
        }
    }

    private fun normalizeDeparture(
        depDto: GolemioDepartureDto,
        referenceTime: Instant
    ): Departure? {
        val scheduledInstant = parseTimestamp(depDto.departureTimestamp.scheduled) ?: return null
        val predictedInstant = depDto.departureTimestamp.predicted?.let { parseTimestamp(it) } ?: scheduledInstant

        val countdown = computeCountdown(predictedInstant, referenceTime)

        val delayMinutes = depDto.delay.minutes ?: calculateDelayMinutes(scheduledInstant, predictedInstant)
        val delay = Delay(
            minutes = maxOf(0, delayMinutes),
            isAvailable = depDto.delay.isAvailable || depDto.departureTimestamp.predicted != null
        )

        val transportMode = resolveTransportMode(depDto.route.type)

        return Departure(
            id = "${depDto.stop.id}_${depDto.route.shortName}_${predictedInstant.toEpochMilli()}",
            line = Line(
                name = depDto.route.shortName,
                transportMode = transportMode
            ),
            terminus = depDto.trip.headsign,
            scheduledTime = scheduledInstant,
            predictedTime = predictedInstant,
            countdown = countdown,
            delay = delay,
            isWheelchairAccessible = depDto.trip.isWheelchairAccessible ?: false
        )
    }

    private fun computeCountdown(
        predictedInstant: Instant,
        referenceTime: Instant
    ): CountdownDisplay {
        val diffSeconds = Duration.between(referenceTime, predictedInstant).seconds
        if (diffSeconds < 45) {
            return CountdownDisplay(
                minutesRemaining = 0,
                displayText = "<1 min",
                isClockTime = false
            )
        }

        val diffMinutes = (diffSeconds + 30) / 60
        return if (diffMinutes <= 30) {
            CountdownDisplay(
                minutesRemaining = diffMinutes,
                displayText = "$diffMinutes min",
                isClockTime = false
            )
        } else {
            val zonedDateTime = predictedInstant.atZone(zoneId)
            CountdownDisplay(
                minutesRemaining = diffMinutes,
                displayText = clockTimeFormatter.format(zonedDateTime),
                isClockTime = true
            )
        }
    }

    private fun refreshCountdowns(
        stops: List<Stop>,
        referenceTime: Instant
    ): List<Stop> {
        return stops.map { stop ->
            val updatedPlatforms = stop.platforms.map { platform ->
                val updatedDepartures = platform.departures
                    .filter { dep -> isNotExpired(dep, referenceTime) }
                    .map { dep -> dep.copy(countdown = computeCountdown(dep.predictedTime, referenceTime)) }
                platform.copy(departures = updatedDepartures)
            }
            stop.copy(platforms = updatedPlatforms)
        }
    }

    private fun isNotExpired(dep: Departure, referenceTime: Instant): Boolean {
        return Duration.between(referenceTime, dep.predictedTime).seconds >= -30
    }

    private fun resolveTransportMode(type: Int): TransportMode {
        return when (type) {
            0 -> TransportMode.Tram
            1 -> TransportMode.Metro
            2 -> TransportMode.Train
            3 -> TransportMode.Bus
            11 -> TransportMode.Trolleybus
            else -> TransportMode.Bus
        }
    }

    private fun parseTimestamp(raw: String): Instant? {
        if (raw.isBlank()) return null
        return try {
            OffsetDateTime.parse(raw).toInstant()
        } catch (e: Exception) {
            try {
                Instant.parse(raw)
            } catch (e2: Exception) {
                null
            }
        }
    }

    private fun calculateDelayMinutes(scheduled: Instant, predicted: Instant): Int {
        val diff = Duration.between(scheduled, predicted).toMinutes().toInt()
        return maxOf(0, diff)
    }

    fun calculateDistanceMeters(
        from: Coordinates,
        to: Coordinates
    ): Int = from.distanceTo(to)
}

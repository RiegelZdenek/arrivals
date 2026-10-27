package cz.riegel.arrivals.domain

import cz.riegel.arrivals.data.model.GolemioDepartureBoardResponse
import cz.riegel.arrivals.data.model.GolemioDepartureDto
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

class DepartureNormalizer(
    private val zoneId: ZoneId = ZoneId.of("Europe/Prague")
) {
    private val clockTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    fun normalize(
        dto: GolemioDepartureBoardResponse,
        referenceTime: Instant = Instant.now()
    ): List<Stop> {
        val stopsByIdOrName = dto.stops.associateBy { it.stopId }

        // Group departures by stop name (resolving from stop ID in stops list if dep.stop.name is absent)
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

            // Resolve display code for each departure
            val departuresByDisplayCode = stopDepartures.groupBy { dep ->
                val stopInfo = stopsByIdOrName[dep.stop.id]
                val platformCode = dep.stop.platformCode?.trim()?.ifEmpty { null }
                    ?: stopInfo?.platformCode?.trim()?.ifEmpty { null }
                platformCode ?: "→ ${dep.trip.headsign.trim()}"
            }

            // Collect all known platform codes from both departures and dto.stops
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

    fun normalizeDeparture(
        depDto: GolemioDepartureDto,
        referenceTime: Instant
    ): Departure? {
        val scheduledInstant = parseTimestamp(depDto.departureTimestamp.scheduled) ?: return null
        val predictedInstant = depDto.departureTimestamp.predicted?.let { parseTimestamp(it) } ?: scheduledInstant

        val diffMinutes = Duration.between(referenceTime, predictedInstant).toMinutes()

        val countdown = when {
            diffMinutes < 1 -> CountdownDisplay(
                minutesRemaining = diffMinutes,
                displayText = "<1 min",
                isClockTime = false
            )
            diffMinutes <= 30 -> CountdownDisplay(
                minutesRemaining = diffMinutes,
                displayText = "$diffMinutes min",
                isClockTime = false
            )
            else -> {
                val zonedDateTime = predictedInstant.atZone(zoneId)
                CountdownDisplay(
                    minutesRemaining = diffMinutes,
                    displayText = clockTimeFormatter.format(zonedDateTime),
                    isClockTime = true
                )
            }
        }

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
            headsign = depDto.trip.headsign,
            scheduledTime = scheduledInstant,
            predictedTime = predictedInstant,
            countdown = countdown,
            delay = delay,
            isWheelchairAccessible = depDto.trip.isWheelchairAccessible ?: false
        )
    }

    fun resolveTransportMode(type: Int): TransportMode {
        return when (type) {
            0 -> TransportMode.Tram
            1 -> TransportMode.Metro
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
}

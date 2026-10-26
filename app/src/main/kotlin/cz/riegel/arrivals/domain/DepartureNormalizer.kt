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

        if (departuresByStop.isEmpty() && dto.stops.isNotEmpty()) {
            return dto.stops.groupBy { it.stopName }.map { (stopName, platformStops) ->
                val platforms = platformStops.map { pStop ->
                    Platform(
                        id = pStop.stopId,
                        code = pStop.platformCode ?: "A",
                        lat = pStop.stopLat,
                        lon = pStop.stopLon,
                        departures = emptyList()
                    )
                }
                val first = platformStops.first()
                Stop(
                    id = first.stopId,
                    name = stopName,
                    lat = first.stopLat,
                    lon = first.stopLon,
                    distanceMeters = null,
                    platforms = platforms
                )
            }
        }

        return departuresByStop.map { (stopName, stopDepartures) ->
            // Group departures for this stop by physical platform pole ID
            val departuresByPlatform = stopDepartures.groupBy { dep ->
                dep.stop.id.ifEmpty { dep.stop.platformCode.orEmpty() }
            }

            val platforms = departuresByPlatform.map { (platformId, platformDepartures) ->
                val firstDep = platformDepartures.first()
                val stopInfo = stopsByIdOrName[firstDep.stop.id]

                val platformCode = firstDep.stop.platformCode?.trim()
                    ?: stopInfo?.platformCode?.trim()
                val displayCode = if (!platformCode.isNullOrEmpty()) {
                    platformCode
                } else {
                    // Fallback to primary headsign (e.g., "→ Centrum") per User Story 4
                    "→ ${firstDep.trip.headsign.trim()}"
                }

                val normalizedDepartures = platformDepartures.mapNotNull { depDto ->
                    normalizeDeparture(depDto, referenceTime)
                }.sortedBy { it.predictedTime }

                Platform(
                    id = platformId,
                    code = displayCode,
                    lat = stopInfo?.stopLat ?: 0.0,
                    lon = stopInfo?.stopLon ?: 0.0,
                    departures = normalizedDepartures
                )
            }.sortedBy { it.code }

            val primaryStopInfo = stopDepartures.firstNotNullOfOrNull { stopsByIdOrName[it.stop.id] }
                ?: dto.stops.firstOrNull { it.stopName == stopName }
            val avgLat = primaryStopInfo?.stopLat ?: platforms.firstOrNull()?.lat ?: 0.0
            val avgLon = primaryStopInfo?.stopLon ?: platforms.firstOrNull()?.lon ?: 0.0

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

package cz.riegel.arrivals.domain

import cz.riegel.arrivals.data.model.GolemioDepartureBoardResponse
import cz.riegel.arrivals.data.model.GolemioDepartureDto
import cz.riegel.arrivals.data.model.GolemioDepartureStopDto
import cz.riegel.arrivals.data.model.GolemioDelayDto
import cz.riegel.arrivals.data.model.GolemioRouteDto
import cz.riegel.arrivals.data.model.GolemioStopDto
import cz.riegel.arrivals.data.model.GolemioTimestampDto
import cz.riegel.arrivals.data.model.GolemioTripDto
import cz.riegel.arrivals.domain.model.CountdownDisplay
import cz.riegel.arrivals.domain.model.TransportMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class DepartureNormalizerTest {

    private val normalizer = DepartureNormalizer()
    // Reference instant: 2026-10-23T14:30:00Z (which is 16:30 in Prague CEST UTC+2)
    private val now = Instant.parse("2026-10-23T14:30:00Z")

    @Test
    fun normalize_mapsStopsPlatformsAndDeparturesCorrectly() {
        val dto = GolemioDepartureBoardResponse(
            stops = listOf(
                GolemioStopDto(
                    stopId = "U316Z101P",
                    stopName = "I. P. Pavlova",
                    platformCode = "A",
                    stopLat = 50.0754,
                    stopLon = 14.4301
                )
            ),
            departures = listOf(
                GolemioDepartureDto(
                    departureTimestamp = GolemioTimestampDto(
                        scheduled = "2026-10-23T16:33:00+02:00",
                        predicted = "2026-10-23T16:35:00+02:00"
                    ),
                    stop = GolemioDepartureStopDto(id = "U316Z101P", name = "I. P. Pavlova", platformCode = "A"),
                    route = GolemioRouteDto(shortName = "22", type = 0),
                    trip = GolemioTripDto(headsign = "Bílá Hora"),
                    delay = GolemioDelayDto(isAvailable = true, minutes = 2)
                )
            )
        )

        val stops = normalizer.normalize(dto, referenceTime = now)

        assertEquals(1, stops.size)
        val stop = stops[0]
        assertEquals("I. P. Pavlova", stop.name)
        assertEquals(1, stop.platforms.size)

        val platform = stop.platforms[0]
        assertEquals("A", platform.code)
        assertEquals(1, platform.departures.size)

        val departure = platform.departures[0]
        assertEquals("22", departure.line.name)
        assertEquals(TransportMode.Tram, departure.line.transportMode)
        assertEquals("Bílá Hora", departure.headsign)
        assertEquals(2, departure.delay.minutes)
        assertTrue(departure.delay.isDelayed)
        assertEquals("+2 min", departure.delay.badgeText)

        // 16:30 to predicted 16:35 is 5 minutes
        val countdown = departure.countdown
        assertEquals(5, countdown.minutesRemaining)
        assertEquals("5 min", countdown.displayText)
        assertFalse(countdown.isClockTime)
    }

    @Test
    fun normalize_handlesLessThanOneMinuteCountdown() {
        val dto = GolemioDepartureBoardResponse(
            stops = listOf(GolemioStopDto(stopId = "S1", stopName = "Anděl", platformCode = "B")),
            departures = listOf(
                GolemioDepartureDto(
                    departureTimestamp = GolemioTimestampDto(
                        scheduled = "2026-10-23T16:30:20+02:00",
                        predicted = "2026-10-23T16:30:20+02:00"
                    ),
                    stop = GolemioDepartureStopDto(id = "S1", name = "Anděl", platformCode = "B"),
                    route = GolemioRouteDto(shortName = "B", type = 1),
                    trip = GolemioTripDto(headsign = "Zličín"),
                    delay = GolemioDelayDto(isAvailable = true, minutes = 0)
                )
            )
        )

        val stops = normalizer.normalize(dto, referenceTime = now)
        val departure = stops[0].platforms[0].departures[0]

        assertEquals(TransportMode.Metro, departure.line.transportMode)
        assertEquals("<1 min", departure.countdown.displayText)
        assertEquals(0, departure.delay.minutes)
        assertFalse(departure.delay.isDelayed)
    }

    @Test
    fun normalize_handlesFarFutureDeparture_formatsAsClockTime() {
        val dto = GolemioDepartureBoardResponse(
            stops = listOf(GolemioStopDto(stopId = "S1", stopName = "Nádraží Veleslavín", platformCode = "1")),
            departures = listOf(
                GolemioDepartureDto(
                    // 45 minutes in the future (17:15)
                    departureTimestamp = GolemioTimestampDto(
                        scheduled = "2026-10-23T17:15:00+02:00",
                        predicted = "2026-10-23T17:15:00+02:00"
                    ),
                    stop = GolemioDepartureStopDto(id = "S1", name = "Nádraží Veleslavín", platformCode = "1"),
                    route = GolemioRouteDto(shortName = "119", type = 3),
                    trip = GolemioTripDto(headsign = "Letiště"),
                    delay = GolemioDelayDto(isAvailable = true, minutes = 0)
                )
            )
        )

        val stops = normalizer.normalize(dto, referenceTime = now)
        val departure = stops[0].platforms[0].departures[0]

        assertEquals(TransportMode.Bus, departure.line.transportMode)
        assertTrue(departure.countdown.isClockTime)
        assertEquals("17:15", departure.countdown.displayText)
    }

    @Test
    fun normalize_resolvesTransportModesCorrectly() {
        assertEquals(TransportMode.Metro, normalizer.resolveTransportMode(1))
        assertEquals(TransportMode.Tram, normalizer.resolveTransportMode(0))
        assertEquals(TransportMode.Bus, normalizer.resolveTransportMode(3))
        assertEquals(TransportMode.Trolleybus, normalizer.resolveTransportMode(11))
    }
}

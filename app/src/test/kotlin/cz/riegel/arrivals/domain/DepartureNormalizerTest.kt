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

    @Test
    fun normalize_resolvesStopNameFromStopsArrayWhenDepartureStopNameIsAbsent() {
        val dto = GolemioDepartureBoardResponse(
            stops = listOf(
                GolemioStopDto(stopId = "U754Z1P", stopName = "Prosecká", platformCode = "B")
            ),
            departures = listOf(
                GolemioDepartureDto(
                    departureTimestamp = GolemioTimestampDto(
                        scheduled = "2026-10-23T16:35:00+02:00",
                        minutes = "<1"
                    ),
                    stop = GolemioDepartureStopDto(id = "U754Z1P", name = "", platformCode = "B"),
                    route = GolemioRouteDto(shortName = "140", type = 3),
                    trip = GolemioTripDto(headsign = "Čakovice"),
                    delay = GolemioDelayDto(isAvailable = false)
                )
            )
        )

        val stops = normalizer.normalize(dto, referenceTime = now)
        assertEquals(1, stops.size)
        assertEquals("Prosecká", stops[0].name)
        assertEquals(1, stops[0].platforms.size)
        assertEquals("B", stops[0].platforms[0].code)
        assertEquals(1, stops[0].platforms[0].departures.size)
        assertEquals("Čakovice", stops[0].platforms[0].departures[0].headsign)
    }

    @Test
    fun normalize_handlesEmptyDeparturesWithStopsList() {
        val dto = GolemioDepartureBoardResponse(
            stops = listOf(
                GolemioStopDto(stopId = "S1", stopName = "Flora", platformCode = "A")
            ),
            departures = emptyList()
        )

        val stops = normalizer.normalize(dto, referenceTime = now)
        assertEquals(1, stops.size)
        assertEquals("Flora", stops[0].name)
        assertEquals(1, stops[0].platforms.size)
        assertEquals(0, stops[0].platforms[0].departures.size)
    }

    @Test
    fun refreshCountdowns_decrementsMinutesAndRemovesDepartedVehiclesWithoutRefetching() {
        val dto = GolemioDepartureBoardResponse(
            stops = listOf(
                GolemioStopDto(stopId = "S1", stopName = "Lipanská", platformCode = "A")
            ),
            departures = listOf(
                GolemioDepartureDto(
                    departureTimestamp = GolemioTimestampDto(
                        scheduled = "2026-10-23T16:31:00+02:00",
                        predicted = "2026-10-23T16:31:00+02:00"
                    ),
                    stop = GolemioDepartureStopDto(id = "S1", name = "Lipanská", platformCode = "A"),
                    route = GolemioRouteDto(shortName = "5", type = 0),
                    trip = GolemioTripDto(headsign = "Slivenec")
                ),
                GolemioDepartureDto(
                    departureTimestamp = GolemioTimestampDto(
                        scheduled = "2026-10-23T16:35:00+02:00",
                        predicted = "2026-10-23T16:35:00+02:00"
                    ),
                    stop = GolemioDepartureStopDto(id = "S1", name = "Lipanská", platformCode = "A"),
                    route = GolemioRouteDto(shortName = "9", type = 0),
                    trip = GolemioTripDto(headsign = "Spojovací")
                )
            )
        )

        // At 16:30:00 -> 1 min and 5 min
        val initialStops = normalizer.normalize(dto, referenceTime = now)
        val initialDeps = initialStops[0].platforms[0].departures
        assertEquals(2, initialDeps.size)
        assertEquals("1 min", initialDeps[0].countdown.displayText)
        assertEquals("5 min", initialDeps[1].countdown.displayText)

        // 1 minute later (16:31:00.250, simulating slight broadcast delivery jitter) -> <1 min and 4 min
        val afterOneMin = normalizer.refreshCountdowns(initialStops, referenceTime = now.plusMillis(60_250))
        val depsAtOneMin = afterOneMin[0].platforms[0].departures
        assertEquals(2, depsAtOneMin.size)
        assertEquals("<1 min", depsAtOneMin[0].countdown.displayText)
        assertEquals("4 min", depsAtOneMin[1].countdown.displayText)

        // 2 minutes later (16:32:00) -> 16:31 tram has departed and is removed, 16:35 tram is now at 3 min
        val afterTwoMins = normalizer.refreshCountdowns(afterOneMin, referenceTime = now.plusSeconds(120))
        val depsAtTwoMins = afterTwoMins[0].platforms[0].departures
        assertEquals(1, depsAtTwoMins.size)
        assertEquals("9", depsAtTwoMins[0].line.name)
        assertEquals("3 min", depsAtTwoMins[0].countdown.displayText)
    }
}

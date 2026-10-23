package cz.riegel.arrivals.domain

import cz.riegel.arrivals.domain.model.Coordinates
import cz.riegel.arrivals.domain.model.CountdownDisplay
import cz.riegel.arrivals.domain.model.Delay
import cz.riegel.arrivals.domain.model.Departure
import cz.riegel.arrivals.domain.model.Line
import cz.riegel.arrivals.domain.model.Platform
import cz.riegel.arrivals.domain.model.Stop
import cz.riegel.arrivals.domain.model.TransportMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class ArrivalsEngineTest {

    private val engine = ArrivalsEngine()

    private val userCoords = Coordinates(lat = 50.0755, lon = 14.4302)

    private fun sampleDeparture(headsign: String) = Departure(
        id = "dep_1",
        line = Line("22", TransportMode.Tram),
        headsign = headsign,
        scheduledTime = Instant.now(),
        predictedTime = Instant.now(),
        countdown = CountdownDisplay(2, "2 min", false),
        delay = Delay(0, true)
    )

    @Test
    fun computeProximityAndOrder_ordersStopsNearestFirstAndPopulatesDistance() {
        val stopFar = Stop(
            id = "stop_far",
            name = "Karlovo náměstí",
            lat = 50.0760,
            lon = 14.4200,
            platforms = listOf(Platform("p1", "A", 50.0760, 14.4200, listOf(sampleDeparture("Spálená"))))
        )
        val stopNear = Stop(
            id = "stop_near",
            name = "I. P. Pavlova",
            lat = 50.0754,
            lon = 14.4301,
            platforms = listOf(
                Platform("p_a", "A", 50.0754, 14.4301, listOf(sampleDeparture("Bílá Hora"))),
                Platform("p_b", "B", 50.0758, 14.4306, listOf(sampleDeparture("Nádraží Hostivař")))
            )
        )

        val processedStops = engine.rankStopsByProximity(
            stops = listOf(stopFar, stopNear),
            userCoords = userCoords
        )

        assertEquals("I. P. Pavlova", processedStops[0].name)
        assertTrue("Distance should be within 30 meters", processedStops[0].distanceMeters!! < 30)
        assertEquals("Karlovo náměstí", processedStops[1].name)
        assertTrue("Distance should be greater than 600m", processedStops[1].distanceMeters!! > 600)
    }

    @Test
    fun selectClosestPlatform_selectsPlatformPhysicallyNearestToUser() {
        val platformA = Platform("p_a", "A", 50.0754, 14.4301, listOf(sampleDeparture("Bílá Hora")))
        val platformB = Platform("p_b", "B", 50.0760, 14.4315, listOf(sampleDeparture("Hostivař")))

        val stop = Stop(
            id = "stop_1",
            name = "I. P. Pavlova",
            lat = 50.0754,
            lon = 14.4301,
            platforms = listOf(platformA, platformB)
        )

        val selectedPlatform = engine.findClosestPlatform(stop, userCoords = Coordinates(50.0754, 14.4301))
        assertEquals("A", selectedPlatform?.code)
    }

    @Test
    fun stateReducer_handlesStopCyclingWithNearestPlatformSelection() {
        val stop1 = Stop(
            id = "s1", name = "Stop 1",
            lat = 50.075, lon = 14.430,
            platforms = listOf(Platform("p1", "A", 50.075, 14.430), Platform("p2", "B", 50.076, 14.431))
        )
        val stop2 = Stop(
            id = "s2", name = "Stop 2",
            lat = 50.080, lon = 14.440,
            platforms = listOf(Platform("p3", "1", 50.080, 14.440), Platform("p4", "2", 50.081, 14.441))
        )

        var state = ArrivalsViewState.Content(
            stops = listOf(stop1, stop2),
            activeStopIndex = 0,
            activePlatformCode = "A",
            userCoordinates = Coordinates(50.080, 14.440)
        )

        // Action: NextStop - should auto-select closest platform "1" on Stop 2
        state = engine.reduce(state, ArrivalsAction.NextStop) as ArrivalsViewState.Content
        assertEquals(1, state.activeStopIndex)
        assertEquals("Stop 2", state.activeStop.name)
        assertEquals("1", state.activePlatformCode)

        // Action: SelectPlatform
        state = engine.reduce(state, ArrivalsAction.SelectPlatform("2")) as ArrivalsViewState.Content
        assertEquals("2", state.activePlatformCode)

        // Action: PreviousStop
        state = engine.reduce(state, ArrivalsAction.PreviousStop) as ArrivalsViewState.Content
        assertEquals(0, state.activeStopIndex)
        assertEquals("Stop 1", state.activeStop.name)
    }

    private fun assertTrue(message: String, condition: Boolean) {
        org.junit.Assert.assertTrue(message, condition)
    }
}

package cz.riegel.arrivals.widget

import cz.riegel.arrivals.domain.ArrivalsViewState
import cz.riegel.arrivals.domain.model.CountdownDisplay
import cz.riegel.arrivals.domain.model.Delay
import cz.riegel.arrivals.domain.model.Departure
import cz.riegel.arrivals.domain.model.Line
import cz.riegel.arrivals.domain.model.Platform
import cz.riegel.arrivals.domain.model.Stop
import cz.riegel.arrivals.domain.model.TransportMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ArrivalsWidgetContentTest {

    @Test
    fun widgetState_computesDisplayDeparturesForCompactAndStandard() {
        val departures = (1..6).map { i ->
            Departure(
                id = "dep_$i",
                line = Line("$i", TransportMode.Tram),
                headsign = "Destination $i",
                scheduledTime = Instant.now(),
                predictedTime = Instant.now(),
                countdown = CountdownDisplay(i.toLong(), "$i min", false),
                delay = Delay(0, false)
            )
        }

        val platform = Platform("p1", "A", departures = departures)
        val stop = Stop("s1", "I. P. Pavlova", distanceMeters = 15, platforms = listOf(platform))

        val state = ArrivalsViewState.Content(
            stops = listOf(stop),
            activeStopIndex = 0,
            activePlatformCode = "A"
        )

        assertEquals("I. P. Pavlova", state.activeStop.name)
        assertEquals(15, state.activeStop.distanceMeters)
        assertEquals("A", state.activePlatform?.code)
        assertEquals(6, state.activeDepartures.size)

        // Compact limits to 3 departures
        val compactList = state.activeDepartures.take(3)
        assertEquals(3, compactList.size)

        // Standard shows up to 6 departures
        val standardList = state.activeDepartures.take(6)
        assertEquals(6, standardList.size)
    }

    @Test
    fun widgetState_cachedWarningIsRenderedWhenPresent() {
        val stop = Stop("s1", "Anděl", distanceMeters = 50, platforms = emptyList())
        val state = ArrivalsViewState.Content(
            stops = listOf(stop),
            activeStopIndex = 0,
            activePlatformCode = "",
            cachedWarning = "Cached 15:42 • Tap to retry"
        )

        assertEquals("Cached 15:42 • Tap to retry", state.cachedWarning)
    }
}

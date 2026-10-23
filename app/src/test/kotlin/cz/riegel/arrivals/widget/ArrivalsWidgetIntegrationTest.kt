package cz.riegel.arrivals.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cz.riegel.arrivals.domain.ArrivalsViewState
import cz.riegel.arrivals.domain.model.Coordinates
import cz.riegel.arrivals.domain.model.CountdownDisplay
import cz.riegel.arrivals.domain.model.Delay
import cz.riegel.arrivals.domain.model.Departure
import cz.riegel.arrivals.domain.model.Line
import cz.riegel.arrivals.domain.model.Platform
import cz.riegel.arrivals.domain.model.Stop
import cz.riegel.arrivals.domain.model.TransportMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ArrivalsWidgetIntegrationTest {

    private lateinit var context: Context

    private fun sampleStop(id: String, name: String, platforms: List<String>) = Stop(
        id = id,
        name = name,
        lat = 50.075,
        lon = 14.430,
        platforms = platforms.map { code ->
            Platform(
                id = "${id}_$code",
                code = code,
                departures = listOf(
                    Departure(
                        id = "dep_1",
                        line = Line("22", TransportMode.Tram),
                        headsign = "Bílá Hora",
                        scheduledTime = Instant.now(),
                        predictedTime = Instant.now(),
                        countdown = CountdownDisplay(3, "3 min", false),
                        delay = Delay(1, true)
                    )
                )
            )
        }
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun widgetState_fullInteractionLifecycle() = runBlocking {
        val stop1 = sampleStop("s1", "I. P. Pavlova", listOf("A", "B"))
        val stop2 = sampleStop("s2", "Karlovo náměstí", listOf("1", "2"))

        // Set initial state
        WidgetStateManager.setState(
            context,
            ArrivalsViewState.Content(
                stops = listOf(stop1, stop2),
                activeStopIndex = 0,
                activePlatformCode = "A",
                userCoordinates = Coordinates(50.075, 14.430)
            )
        )

        var state = WidgetStateManager.getState(context) as ArrivalsViewState.Content
        assertEquals("I. P. Pavlova", state.activeStop.name)
        assertEquals("A", state.activePlatformCode)

        // Select Platform B
        WidgetStateManager.onSelectPlatform(context, "B")
        state = WidgetStateManager.getState(context) as ArrivalsViewState.Content
        assertEquals("B", state.activePlatformCode)

        // Next Stop -> Karlovo náměstí
        WidgetStateManager.onNextStop(context)
        state = WidgetStateManager.getState(context) as ArrivalsViewState.Content
        assertEquals("Karlovo náměstí", state.activeStop.name)
        assertEquals("1", state.activePlatformCode)

        // Prev Stop -> I. P. Pavlova
        WidgetStateManager.onPrevStop(context)
        state = WidgetStateManager.getState(context) as ArrivalsViewState.Content
        assertEquals("I. P. Pavlova", state.activeStop.name)
    }
}

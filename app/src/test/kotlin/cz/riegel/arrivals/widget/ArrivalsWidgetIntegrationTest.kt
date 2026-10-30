package cz.riegel.arrivals.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cz.riegel.arrivals.data.DeparturesCacheRepository
import cz.riegel.arrivals.domain.ArrivalsViewState
import cz.riegel.arrivals.domain.PickerMode
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
        assertEquals(PickerMode.Departures, state.pickerMode)

        // Open Stop Picker -> StopPicker mode
        WidgetStateManager.onOpenStopPicker(context)
        state = WidgetStateManager.getState(context) as ArrivalsViewState.Content
        assertEquals(PickerMode.StopPicker, state.pickerMode)

        // Close Picker -> returns to Departures without changing selection
        WidgetStateManager.onClosePicker(context)
        state = WidgetStateManager.getState(context) as ArrivalsViewState.Content
        assertEquals(PickerMode.Departures, state.pickerMode)
        assertEquals("I. P. Pavlova", state.activeStop.name)
        assertEquals("A", state.activePlatformCode)

        // Open Stop Picker and select Stop 1 (Karlovo náměstí) -> selects closest platform and returns to Departures
        WidgetStateManager.onOpenStopPicker(context)
        WidgetStateManager.onSelectStop(context, 1)
        state = WidgetStateManager.getState(context) as ArrivalsViewState.Content
        assertEquals(PickerMode.Departures, state.pickerMode)
        assertEquals("Karlovo náměstí", state.activeStop.name)
        assertEquals("1", state.activePlatformCode)
        assertEquals(1, DeparturesCacheRepository(context).getActiveStopIndex())
        assertEquals("1", DeparturesCacheRepository(context).getActivePlatformCode())

        // Open Platform Picker -> PlatformPicker mode
        WidgetStateManager.onOpenPlatformPicker(context)
        state = WidgetStateManager.getState(context) as ArrivalsViewState.Content
        assertEquals(PickerMode.PlatformPicker, state.pickerMode)

        // Close Platform Picker -> returns to Departures without changing platform
        WidgetStateManager.onClosePicker(context)
        state = WidgetStateManager.getState(context) as ArrivalsViewState.Content
        assertEquals(PickerMode.Departures, state.pickerMode)
        assertEquals("1", state.activePlatformCode)

        // Open Platform Picker and select Platform "2" -> updates platform, persists, and returns to Departures
        WidgetStateManager.onOpenPlatformPicker(context)
        WidgetStateManager.onSelectPlatform(context, "2")
        state = WidgetStateManager.getState(context) as ArrivalsViewState.Content
        assertEquals(PickerMode.Departures, state.pickerMode)
        assertEquals("2", state.activePlatformCode)
        assertEquals("2", DeparturesCacheRepository(context).getActivePlatformCode())

        // Open StopPicker and run tickCountdowns -> countdowns refresh across stops while preserving Stop, Platform & PickerMode
        val baseTime = Instant.parse("2026-10-30T12:00:00Z")
        val timedStop1 = Stop(
            id = "s1",
            name = "I. P. Pavlova",
            platforms = listOf(
                Platform(
                    id = "s1_A",
                    code = "A",
                    departures = listOf(
                        Departure(
                            id = "d1",
                            line = Line("22", TransportMode.Tram),
                            headsign = "Bílá Hora",
                            scheduledTime = baseTime.plusSeconds(300),
                            predictedTime = baseTime.plusSeconds(300),
                            countdown = CountdownDisplay(5, "5 min", false),
                            delay = Delay(0, false)
                        )
                    )
                )
            )
        )
        val timedStop2 = Stop(
            id = "s2",
            name = "Karlovo náměstí",
            platforms = listOf(
                Platform(
                    id = "s2_1",
                    code = "1",
                    departures = listOf(
                        Departure(
                            id = "d2",
                            line = Line("10", TransportMode.Tram),
                            headsign = "Sídliště Řepy",
                            scheduledTime = baseTime.plusSeconds(420),
                            predictedTime = baseTime.plusSeconds(420),
                            countdown = CountdownDisplay(7, "7 min", false),
                            delay = Delay(0, false)
                        )
                    )
                ),
                Platform(
                    id = "s2_2",
                    code = "2",
                    departures = listOf(
                        Departure(
                            id = "d3",
                            line = Line("16", TransportMode.Tram),
                            headsign = "Kotlářka",
                            scheduledTime = baseTime.plusSeconds(240),
                            predictedTime = baseTime.plusSeconds(240),
                            countdown = CountdownDisplay(4, "4 min", false),
                            delay = Delay(0, false)
                        )
                    )
                )
            )
        )

        WidgetStateManager.setState(
            context,
            ArrivalsViewState.Content(
                stops = listOf(timedStop1, timedStop2),
                activeStopIndex = 1,
                activePlatformCode = "2",
                pickerMode = PickerMode.StopPicker
            )
        )

        WidgetStateManager.tickCountdowns(context, baseTime.plusSeconds(60))
        state = WidgetStateManager.getState(context, baseTime.plusSeconds(60)) as ArrivalsViewState.Content
        assertEquals(1, state.activeStopIndex)
        assertEquals("Karlovo náměstí", state.activeStop.name)
        assertEquals("2", state.activePlatformCode)
        assertEquals(PickerMode.StopPicker, state.pickerMode)
        assertEquals("3 min", state.activeDepartures.first().countdown.displayText)
        assertEquals("4 min", state.stops[0].platforms[0].departures.first().countdown.displayText)
    }
}

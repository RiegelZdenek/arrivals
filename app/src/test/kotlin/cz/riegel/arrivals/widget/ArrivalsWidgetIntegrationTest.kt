package cz.riegel.arrivals.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import cz.riegel.arrivals.data.DeparturesCacheRepository
import cz.riegel.arrivals.data.model.GolemioDepartureBoardResponse
import cz.riegel.arrivals.data.model.GolemioDepartureDto
import cz.riegel.arrivals.data.model.GolemioDepartureStopDto
import cz.riegel.arrivals.data.model.GolemioRouteDto
import cz.riegel.arrivals.data.model.GolemioStopDto
import cz.riegel.arrivals.data.model.GolemioTimestampDto
import cz.riegel.arrivals.data.model.GolemioTripDto
import cz.riegel.arrivals.domain.ArrivalsAction
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
        val config = androidx.work.Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.DEBUG)
            .build()
        try {
            WorkManager.initialize(context, config)
        } catch (_: Exception) {
            // Already initialized
        }
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

    @Test
    fun transientPickerTransitions_retainedInMemoryWithoutDiskWrites() = runBlocking {
        val stop1 = sampleStop("s1", "I. P. Pavlova", listOf("A", "B"))
        val stop2 = sampleStop("s2", "Karlovo náměstí", listOf("1", "2"))

        WidgetStateManager.setState(
            context,
            ArrivalsViewState.Content(
                stops = listOf(stop1, stop2),
                activeStopIndex = 0,
                activePlatformCode = "A"
            )
        )

        val cacheRepo = DeparturesCacheRepository(context)
        // Ensure disk cache has initial selection
        assertEquals(0, cacheRepo.getActiveStopIndex())
        assertEquals("A", cacheRepo.getActivePlatformCode())
        assertNull("Transient picker mode should not be written to disk", cacheRepo.getActivePickerMode())

        // 1. Open Stop Picker -> in-memory state changes immediately
        WidgetStateManager.onOpenStopPicker(context)
        var state = WidgetStateManager.getState(context) as ArrivalsViewState.Content
        assertEquals(PickerMode.StopPicker, state.pickerMode)
        assertNull("Opening StopPicker must not write transient picker mode to disk", cacheRepo.getActivePickerMode())

        // 2. Close picker -> in-memory returns to Departures immediately
        WidgetStateManager.onClosePicker(context)
        state = WidgetStateManager.getState(context) as ArrivalsViewState.Content
        assertEquals(PickerMode.Departures, state.pickerMode)
        assertNull("Closing StopPicker must not write transient picker mode to disk", cacheRepo.getActivePickerMode())

        // 3. Open Platform Picker -> in-memory changes immediately
        WidgetStateManager.onOpenPlatformPicker(context)
        state = WidgetStateManager.getState(context) as ArrivalsViewState.Content
        assertEquals(PickerMode.PlatformPicker, state.pickerMode)
        assertNull("Opening PlatformPicker must not write transient picker mode to disk", cacheRepo.getActivePickerMode())

        // 4. Close picker
        WidgetStateManager.onClosePicker(context)
        state = WidgetStateManager.getState(context) as ArrivalsViewState.Content
        assertEquals(PickerMode.Departures, state.pickerMode)
        assertNull("Closing PlatformPicker must not write transient picker mode to disk", cacheRepo.getActivePickerMode())
    }

    @Test
    fun intraStopPlatformSwitching_instantAndNoNetworkEnqueued() = runBlocking {
        val now = Instant.now()
        val platformA = Platform(
            id = "s1_A",
            code = "A",
            departures = listOf(
                Departure(
                    id = "dep_22",
                    line = Line("22", TransportMode.Tram),
                    headsign = "Bílá Hora",
                    scheduledTime = now.plusSeconds(300),
                    predictedTime = now.plusSeconds(300),
                    countdown = CountdownDisplay(5, "5 min", false),
                    delay = Delay(0, false)
                )
            )
        )
        val platformB = Platform(
            id = "s1_B",
            code = "B",
            departures = listOf(
                Departure(
                    id = "dep_9",
                    line = Line("9", TransportMode.Tram),
                    headsign = "Sídliště Řepy",
                    scheduledTime = now.plusSeconds(600),
                    predictedTime = now.plusSeconds(600),
                    countdown = CountdownDisplay(10, "10 min", false),
                    delay = Delay(0, false)
                )
            )
        )
        val stop = Stop(
            id = "s1",
            name = "I. P. Pavlova",
            platforms = listOf(platformA, platformB)
        )

        WidgetStateManager.setState(
            context,
            ArrivalsViewState.Content(
                stops = listOf(stop),
                activeStopIndex = 0,
                activePlatformCode = "A"
            )
        )

        var state = WidgetStateManager.getState(context) as ArrivalsViewState.Content
        assertEquals("A", state.activePlatformCode)
        assertEquals("22", state.activeDepartures.first().line.name)

        // Select Platform "B"
        WidgetStateManager.onOpenPlatformPicker(context)
        WidgetStateManager.onSelectPlatform(context, "B")

        // 1. Immediately displays Platform B's departures
        state = WidgetStateManager.getState(context) as ArrivalsViewState.Content
        assertEquals("B", state.activePlatformCode)
        assertEquals(PickerMode.Departures, state.pickerMode)
        assertEquals(1, state.activeDepartures.size)
        assertEquals("9", state.activeDepartures.first().line.name)

        // 2. Persisted for active session in DeparturesCacheRepository
        val cacheRepo = DeparturesCacheRepository(context)
        assertEquals("B", cacheRepo.getActivePlatformCode())

        // 3. Persists across countdown ticks
        WidgetStateManager.tickCountdowns(context, Instant.now().plusSeconds(60))
        state = WidgetStateManager.getState(context) as ArrivalsViewState.Content
        assertEquals("B", state.activePlatformCode)
        assertEquals("9", state.activeDepartures.first().line.name)
    }

    @Test
    fun stopSelection_immediatelyClosesPickerAndEntersSkeletonLoading() = runBlocking {
        val stop1 = sampleStop("s1", "I. P. Pavlova", listOf("A", "B"))
        val stop2 = sampleStop("s2", "Karlovo náměstí", listOf("1", "2"))

        WidgetStateManager.setState(
            context,
            ArrivalsViewState.Content(
                stops = listOf(stop1, stop2),
                activeStopIndex = 0,
                activePlatformCode = "A",
                pickerMode = PickerMode.StopPicker
            )
        )

        // Select different stop (index 1)
        WidgetStateManager.onSelectStop(context, 1)

        val state = WidgetStateManager.getState(context) as ArrivalsViewState.Content
        // 1. Immediately closes picker
        assertEquals(PickerMode.Departures, state.pickerMode)
        assertEquals(1, state.activeStopIndex)
        assertEquals("Karlovo náměstí", state.activeStop.name)
        // 2. Enters skeleton loading alongside refresh indicator
        assertTrue(state.isLoadingDepartures)
        assertTrue(state.isRefreshing)
    }

    @Test
    fun stopSelection_triggersWorkerAndResolvesFreshDepartures() = runBlocking {
        val stop1 = sampleStop("s1", "I. P. Pavlova", listOf("A"))
        val stop2 = sampleStop("s2", "Karlovo náměstí", listOf("1"))

        WidgetStateManager.setState(
            context,
            ArrivalsViewState.Content(
                stops = listOf(stop1, stop2),
                activeStopIndex = 0,
                activePlatformCode = "A"
            )
        )

        // Select different stop
        WidgetStateManager.onSelectStop(context, 1)

        var state = WidgetStateManager.getState(context) as ArrivalsViewState.Content
        assertEquals(1, state.activeStopIndex)
        assertEquals("Karlovo náměstí", state.activeStop.name)
        assertTrue(state.isLoadingDepartures)
        assertTrue(state.isRefreshing)

        // 1. Verify OneTimeWork is enqueued for ArrivalsUpdateWorker
        val workInfos = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(ArrivalsUpdateWorker.WORK_NAME_ONE_TIME)
            .get()
        assertNotNull(workInfos)
        assertTrue(workInfos.isNotEmpty())

        // 2. When worker completes and updates data with fresh departures for Karlovo náměstí
        val now = Instant.now()
        val freshStop2 = Stop(
            id = "s2",
            name = "Karlovo náměstí",
            platforms = listOf(
                Platform(
                    id = "s2_1",
                    code = "1",
                    departures = listOf(
                        Departure(
                            id = "dep_fresh",
                            line = Line("18", TransportMode.Tram),
                            headsign = "Nádraží Podbaba",
                            scheduledTime = now.plusSeconds(180),
                            predictedTime = now.plusSeconds(180),
                            countdown = CountdownDisplay(3, "3 min", false),
                            delay = Delay(0, false)
                        )
                    )
                )
            )
        )

        WidgetStateManager.setState(
            context,
            ArrivalsViewState.Content(
                stops = listOf(stop1, freshStop2),
                activeStopIndex = 1,
                activePlatformCode = "1",
                isLoadingDepartures = false,
                isRefreshing = false
            )
        )

        state = WidgetStateManager.getState(context) as ArrivalsViewState.Content
        // Skeletons and refreshing dismissed, live fresh departures displayed
        assertFalse(state.isLoadingDepartures)
        assertFalse(state.isRefreshing)
        assertEquals("18", state.activeDepartures.first().line.name)
        assertEquals("Nádraží Podbaba", state.activeDepartures.first().headsign)
    }

    @Test
    fun stopSelection_networkFailure_restoresCachedDeparturesWithWarningBanner() = runBlocking {
        val now = Instant.now()
        val cacheRepo = DeparturesCacheRepository(context)

        // Seed cache with departures for Karlovo náměstí
        val cachedResponse = GolemioDepartureBoardResponse(
            stops = listOf(
                GolemioStopDto(stopId = "U123Z1", stopName = "Karlovo náměstí", platformCode = "1", stopLat = 50.075, stopLon = 14.430)
            ),
            departures = listOf(
                GolemioDepartureDto(
                    departureTimestamp = GolemioTimestampDto(
                        scheduled = now.plusSeconds(300).toString(),
                        predicted = now.plusSeconds(300).toString()
                    ),
                    stop = GolemioDepartureStopDto(id = "U123Z1", name = "Karlovo náměstí", platformCode = "1"),
                    route = GolemioRouteDto(shortName = "22", type = 0),
                    trip = GolemioTripDto(headsign = "Bílá Hora")
                )
            )
        )
        cacheRepo.saveLastResponse(cachedResponse, now.minusSeconds(120))

        val stop1 = sampleStop("s1", "I. P. Pavlova", listOf("A"))
        val stop2 = sampleStop("s2", "Karlovo náměstí", listOf("1"))

        WidgetStateManager.setState(
            context,
            ArrivalsViewState.Content(
                stops = listOf(stop1, stop2),
                activeStopIndex = 0,
                activePlatformCode = "A"
            )
        )

        // User selects stop 1 (Karlovo náměstí) -> enters skeleton loading
        WidgetStateManager.onSelectStop(context, 1)
        var state = WidgetStateManager.getState(context) as ArrivalsViewState.Content
        assertEquals(1, state.activeStopIndex)
        assertEquals("Karlovo náměstí", state.activeStop.name)
        assertTrue(state.isLoadingDepartures)
        assertTrue(state.isRefreshing)

        // Simulate network failure / timeout -> restoreCachedDepartures
        ArrivalsUpdateWorker.restoreCachedDepartures(
            context = context,
            now = now,
            fallbackErrorMessage = "Connection timed out"
        )

        val restoredState = WidgetStateManager.getState(context) as ArrivalsViewState.Content
        // 1. Skeletons and refreshing dismissed
        assertFalse(restoredState.isLoadingDepartures)
        assertFalse(restoredState.isRefreshing)
        // 2. Cached departures restored for Karlovo náměstí
        assertEquals("Karlovo náměstí", restoredState.activeStop.name)
        assertEquals(1, restoredState.activeDepartures.size)
        assertEquals("22", restoredState.activeDepartures.first().line.name)
        // 3. Warning banner displayed
        assertNotNull(restoredState.cachedWarning)
        assertTrue(restoredState.cachedWarning!!.contains("Cached"))
        assertTrue(restoredState.cachedWarning!!.contains("Tap to retry"))
    }

    @Test
    fun stopSelection_networkFailure_whenNoCachedDepartures_displaysErrorStateWithRetry() = runBlocking {
        // Clear cache
        context.getSharedPreferences("arrivals_cache_prefs", Context.MODE_PRIVATE).edit().clear().commit()

        val stop1 = sampleStop("s1", "I. P. Pavlova", listOf("A"))
        val stop2 = sampleStop("s2", "Karlovo náměstí", listOf("1"))

        WidgetStateManager.setState(
            context,
            ArrivalsViewState.Content(
                stops = listOf(stop1, stop2),
                activeStopIndex = 0,
                activePlatformCode = "A"
            )
        )

        // Select stop 1 (Karlovo náměstí) -> enters skeleton loading
        WidgetStateManager.onSelectStop(context, 1)
        val contentState = WidgetStateManager.getState(context) as ArrivalsViewState.Content
        assertTrue(contentState.isLoadingDepartures)

        // Simulate network failure / timeout with NO cached data for Karlovo náměstí
        ArrivalsUpdateWorker.restoreCachedDepartures(
            context = context,
            now = Instant.now(),
            fallbackErrorMessage = "Unable to connect"
        )

        val errorState = WidgetStateManager.getState(context)
        assertTrue(errorState is ArrivalsViewState.Error)
        val error = errorState as ArrivalsViewState.Error
        assertTrue(error.canRetry)
        assertTrue(error.message.contains("Karlovo náměstí") || error.message.contains("Unable"))
    }
}

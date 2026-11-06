package cz.riegel.arrivals.widget

import android.content.Context
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.action.actionParametersOf
import androidx.work.OutOfQuotaPolicy
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.TestListenableWorkerBuilder
import cz.riegel.arrivals.data.DeparturesRepository
import cz.riegel.arrivals.data.FakeTransitDataSource
import cz.riegel.arrivals.data.InMemoryDeparturesRepository
import cz.riegel.arrivals.data.LocationResolver
import cz.riegel.arrivals.data.SettingsRepository
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
                        terminus = "Bílá Hora",
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
        ArrivalsUpdateWorker.oneTimeWorkEnqueuedCount = 0
        ArrivalsUpdateWorker.lastEnqueuedWorkRequest = null
        ArrivalsUpdateWorker.defaultTransitDataSourceProvider = null
        ArrivalsUpdateWorker.lastSuccessfulUpdateMillis = 0L
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
        assertEquals(1, DeparturesRepository.create(context).getActiveStopIndex())
        assertEquals("1", DeparturesRepository.create(context).getActivePlatformCode())

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
        assertEquals("2", DeparturesRepository.create(context).getActivePlatformCode())

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
                            terminus = "Bílá Hora",
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
                            terminus = "Sídliště Řepy",
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
                            terminus = "Kotlářka",
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

        val cacheRepo = DeparturesRepository.create(context)
        // Ensure disk cache has initial selection
        assertEquals(0, cacheRepo.getActiveStopIndex())
        assertEquals("A", cacheRepo.getActivePlatformCode())

        // Track SharedPreferences write events to verify zero disk writes on transient toggles
        var diskWrites = 0
        val prefs = context.getSharedPreferences("arrivals_cache_prefs", Context.MODE_PRIVATE)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            diskWrites++
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)

        // 1. Open Stop Picker -> in-memory state changes immediately
        WidgetStateManager.onOpenStopPicker(context)
        var state = WidgetStateManager.getState(context) as ArrivalsViewState.Content
        assertEquals(PickerMode.StopPicker, state.pickerMode)
        assertEquals(0, diskWrites)

        // 2. Close picker -> in-memory returns to Departures immediately
        WidgetStateManager.onClosePicker(context)
        state = WidgetStateManager.getState(context) as ArrivalsViewState.Content
        assertEquals(PickerMode.Departures, state.pickerMode)
        assertEquals(0, diskWrites)

        // 3. Open Platform Picker -> in-memory changes immediately
        WidgetStateManager.onOpenPlatformPicker(context)
        state = WidgetStateManager.getState(context) as ArrivalsViewState.Content
        assertEquals(PickerMode.PlatformPicker, state.pickerMode)
        assertEquals(0, diskWrites)

        // 4. Close picker
        WidgetStateManager.onClosePicker(context)
        state = WidgetStateManager.getState(context) as ArrivalsViewState.Content
        assertEquals(PickerMode.Departures, state.pickerMode)
        assertEquals(0, diskWrites)

        prefs.unregisterOnSharedPreferenceChangeListener(listener)
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
                    terminus = "Bílá Hora",
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
                    terminus = "Sídliště Řepy",
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

        // 2. Persisted for active session in DeparturesRepository
        val cacheRepo = DeparturesRepository.create(context)
        assertEquals("B", cacheRepo.getActivePlatformCode())

        // 3. Persists across countdown ticks
        WidgetStateManager.tickCountdowns(context, Instant.now().plusSeconds(60))
        state = WidgetStateManager.getState(context) as ArrivalsViewState.Content
        assertEquals("B", state.activePlatformCode)
        assertEquals("9", state.activeDepartures.first().line.name)

        // 4. Verify no background network update work was enqueued
        assertEquals(0, ArrivalsUpdateWorker.oneTimeWorkEnqueuedCount)
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
        assertTrue("Selecting a stop must enqueue background worker", ArrivalsUpdateWorker.oneTimeWorkEnqueuedCount > 0)

        // 2. When worker completes and updates data with fresh departures for Karlovo náměstí
        val now = Instant.now()
        val freshResponse = GolemioDepartureBoardResponse(
            stops = listOf(
                GolemioStopDto(stopId = "s1", stopName = "I. P. Pavlova", platformCode = "A", stopLat = 50.075, stopLon = 14.430),
                GolemioStopDto(stopId = "s2", stopName = "Karlovo náměstí", platformCode = "1", stopLat = 50.075, stopLon = 14.430)
            ),
            departures = listOf(
                GolemioDepartureDto(
                    departureTimestamp = GolemioTimestampDto(
                        scheduled = now.plusSeconds(180).toString(),
                        predicted = now.plusSeconds(180).toString()
                    ),
                    stop = GolemioDepartureStopDto(id = "s2", name = "Karlovo náměstí", platformCode = "1"),
                    route = GolemioRouteDto(shortName = "18", type = 0),
                    trip = GolemioTripDto(headsign = "Nádraží Podbaba")
                )
            )
        )

        val engine = cz.riegel.arrivals.domain.ArrivalsEngine()
        val next = engine.reduce(
            state,
            ArrivalsAction.IngestApiResponse(
                response = freshResponse,
                referenceTime = now
            )
        )
        WidgetStateManager.setState(context, next)

        state = WidgetStateManager.getState(context, now) as ArrivalsViewState.Content
        // Skeletons and refreshing dismissed, live fresh departures displayed
        assertFalse(state.isLoadingDepartures)
        assertFalse(state.isRefreshing)
        assertEquals("18", state.activeDepartures.first().line.name)
        assertEquals("Nádraží Podbaba", state.activeDepartures.first().terminus)
    }

    @Test
    fun stopSelection_networkFailure_restoresCachedDeparturesWithWarningBanner() = runBlocking {
        val now = Instant.now()
        val cacheRepo = DeparturesRepository.create(context)

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
        cacheRepo.saveDepartures(cachedResponse, now.minusSeconds(120))

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

    @Test
    fun stopPicker_withTenPlusStops_rendersAllStopsAndAllowsSelectingOverflowingItem() = runBlocking {
        // Create 12 stops (10+ stops)
        val stops = (0..11).map { index ->
            sampleStop(
                id = "stop_$index",
                name = "Stop $index",
                platforms = listOf("A", "B")
            )
        }

        WidgetStateManager.setState(
            context,
            ArrivalsViewState.Content(
                stops = stops,
                activeStopIndex = 0,
                activePlatformCode = "A",
                userCoordinates = Coordinates(50.075, 14.430)
            )
        )

        // Open stop picker -> StopPicker mode
        WidgetStateManager.onOpenStopPicker(context)
        var state = WidgetStateManager.getState(context) as ArrivalsViewState.Content
        assertEquals(PickerMode.StopPicker, state.pickerMode)

        // Verify all 10+ stops render in stopPickerRows
        val rows = state.stopPickerRows
        assertEquals(12, rows.size)
        rows.forEachIndexed { i, row ->
            assertEquals(i, row.stopIndex)
            assertEquals("Stop $i", row.name)
            if (i == 0) {
                assertTrue(row.isSelected)
            } else {
                assertFalse(row.isSelected)
            }
        }

        // Select an overflowing item (e.g. stop index 8)
        WidgetStateManager.onSelectStop(context, 8)

        // Verify active stop state updates, selection persists, and returns to PickerMode.Departures
        state = WidgetStateManager.getState(context) as ArrivalsViewState.Content
        assertEquals(PickerMode.Departures, state.pickerMode)
        assertEquals(8, state.activeStopIndex)
        assertEquals("Stop 8", state.activeStop.name)
        assertEquals(8, DeparturesRepository.create(context).getActiveStopIndex())
    }

    @Test
    fun platformPicker_withTenPlusPlatforms_rendersAllPlatformsAndAllowsSelectingOverflowingItem() = runBlocking {
        // Create a stop with 12 platforms (10+ platforms)
        val platformCodes = (1..12).map { "$it" }
        val stop = sampleStop(
            id = "stop_main",
            name = "Hlavní nádraží",
            platforms = platformCodes
        )

        WidgetStateManager.setState(
            context,
            ArrivalsViewState.Content(
                stops = listOf(stop),
                activeStopIndex = 0,
                activePlatformCode = "1",
                userCoordinates = Coordinates(50.075, 14.430)
            )
        )

        // Open platform picker -> PlatformPicker mode
        WidgetStateManager.onOpenPlatformPicker(context)
        var state = WidgetStateManager.getState(context) as ArrivalsViewState.Content
        assertEquals(PickerMode.PlatformPicker, state.pickerMode)

        // Verify all 10+ platforms render in platformPickerRows
        val rows = state.platformPickerRows
        assertEquals(12, rows.size)
        rows.forEachIndexed { i, row ->
            val expectedCode = "${i + 1}"
            assertEquals(expectedCode, row.code)
            if (i == 0) {
                assertTrue(row.isSelected)
            } else {
                assertFalse(row.isSelected)
            }
        }

        // Select an overflowing item (e.g. index 8 -> code "9")
        WidgetStateManager.onSelectPlatform(context, "9")

        // Verify active platform state updates, selection persists, and returns to PickerMode.Departures
        state = WidgetStateManager.getState(context) as ArrivalsViewState.Content
        assertEquals(PickerMode.Departures, state.pickerMode)
        assertEquals("9", state.activePlatformCode)
        assertEquals("9", DeparturesRepository.create(context).getActivePlatformCode())
    }

    @Test
    fun departuresList_withTenPlusDepartures_retainsViewportLimitsAndStableIds() = runBlocking {
        val departures = (1..12).map { i ->
            Departure(
                id = "dep_line_$i",
                line = Line("$i", TransportMode.Tram),
                terminus = "Terminus $i",
                scheduledTime = Instant.now(),
                predictedTime = Instant.now(),
                countdown = CountdownDisplay(i.toLong(), "$i min", false),
                delay = Delay(0, false)
            )
        }
        val platform = Platform(
            id = "p1",
            code = "A",
            departures = departures
        )
        val stop = sampleStop("s1", "I. P. Pavlova", listOf("A")).copy(platforms = listOf(platform))

        WidgetStateManager.setState(
            context,
            ArrivalsViewState.Content(
                stops = listOf(stop),
                activeStopIndex = 0,
                activePlatformCode = "A"
            )
        )

        val state = WidgetStateManager.getState(context) as ArrivalsViewState.Content
        assertEquals(12, state.activeDepartures.size)

        // Viewport limit in compact mode (<180dp) is 4
        val compactDepartures = state.visibleDepartures(isCompact = true)
        assertEquals(4, compactDepartures.size)
        assertEquals(listOf("dep_line_1", "dep_line_2", "dep_line_3", "dep_line_4"), compactDepartures.map { it.id })

        // Viewport limit in standard mode (>=180dp) is 7
        val standardDepartures = state.visibleDepartures(isCompact = false)
        assertEquals(7, standardDepartures.size)
        assertEquals((1..7).map { "dep_line_$it" }, standardDepartures.map { it.id })

        // Check stable item IDs
        standardDepartures.forEach { departure ->
            assertEquals(departure.id.hashCode().toLong(), departureItemId(departure))
        }
    }

    @Test
    fun departuresList_skeletonLoading_rendersInStaticColumnWhileLoading() = runBlocking {
        val stop = sampleStop("s1", "I. P. Pavlova", listOf("A"))

        WidgetStateManager.setState(
            context,
            ArrivalsViewState.Content(
                stops = listOf(stop),
                activeStopIndex = 0,
                activePlatformCode = "A",
                isLoadingDepartures = true
            )
        )

        val state = WidgetStateManager.getState(context) as ArrivalsViewState.Content
        assertTrue(state.isLoadingDepartures)
        assertEquals(2, skeletonRowCountForHeight(110.dp))
        assertEquals(4, skeletonRowCountForHeight(180.dp))
    }

    @Test
    fun undergroundFallback_whenGpsLostHeadingUnderground_queriesActiveStopByNameAndDisplaysWarningBanner() = runBlocking {
        val now = Instant.parse("2026-10-23T14:30:00Z")
        val repository = InMemoryDeparturesRepository()
        SettingsRepository(context).saveApiKey("test-key")

        // Commuter selected Muzeum before heading underground into the metro
        val muzeumStop = sampleStop("s_muz", "Muzeum", listOf("A", "C"))
        WidgetStateManager.setState(
            context,
            ArrivalsViewState.Content(
                stops = listOf(muzeumStop),
                activeStopIndex = 0,
                activePlatformCode = "A"
            ),
            repository = repository
        )

        // Metro underground environment: GPS signal completely lost
        val fakeDataSource = FakeTransitDataSource()
        val undergroundLiveResponse = GolemioDepartureBoardResponse(
            stops = listOf(
                GolemioStopDto(stopId = "s_muz_A", stopName = "Muzeum", platformCode = "A")
            ),
            departures = listOf(
                GolemioDepartureDto(
                    departureTimestamp = GolemioTimestampDto(
                        scheduled = now.plusSeconds(90).toString(),
                        predicted = now.plusSeconds(90).toString()
                    ),
                    stop = GolemioDepartureStopDto(id = "s_muz_A", name = "Muzeum", platformCode = "A"),
                    route = GolemioRouteDto(shortName = "A", type = 1),
                    trip = GolemioTripDto(headsign = "Nemocnice Motol")
                )
            )
        )
        fakeDataSource.setResponseForStop("Muzeum", Result.success(undergroundLiveResponse))

        val worker = TestListenableWorkerBuilder<ArrivalsUpdateWorker>(context).build()
        worker.locationResolver = object : LocationResolver {
            override suspend fun resolveLocation(highAccuracyTimeoutMs: Long, balancedTimeoutMs: Long): android.location.Location? = null
            override suspend fun getLastKnownLocation(): android.location.Location? = null
        }
        worker.departuresRepository = repository
        worker.transitDataSource = fakeDataSource
        worker.clock = { now }

        val result = worker.doWork()

        assertEquals(androidx.work.ListenableWorker.Result.success(), result)
        assertEquals(listOf("Muzeum"), fakeDataSource.stopsCalls.map { it.stopNames }.flatten())

        val state = WidgetStateManager.getState(context, now, repository = repository) as ArrivalsViewState.Content
        assertEquals("Muzeum", state.activeStop.name)
        assertEquals("A", state.activePlatformCode)
        assertEquals("GPS unavailable • Showing Muzeum", state.cachedWarning)
        assertEquals(1, state.activeDepartures.size)
        assertEquals("A", state.activeDepartures.first().line.name)
        assertEquals("Nemocnice Motol", state.activeDepartures.first().terminus)
    }

    @Test
    fun enqueueOneTimeWork_configuresExpeditedWorkRequestWithRunAsNonExpeditedPolicy() {
        ArrivalsUpdateWorker.enqueueExpeditedOneTimeWork(context)

        val request = ArrivalsUpdateWorker.lastEnqueuedWorkRequest
        assertNotNull("WorkRequest should be created", request)
        assertTrue("WorkRequest must be expedited", request!!.workSpec.expedited)
        assertEquals(
            "OutOfQuotaPolicy must be RUN_AS_NON_EXPEDITED_WORK_REQUEST",
            OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST,
            request.workSpec.outOfQuotaPolicy
        )
    }

    @Test
    fun refreshActionCallback_triggersImmediateRefreshAndEnqueuesExpeditedWorker() = runBlocking {
        val stop = sampleStop("s1", "I. P. Pavlova", listOf("A"))
        WidgetStateManager.setState(
            context,
            ArrivalsViewState.Content(
                stops = listOf(stop),
                activeStopIndex = 0,
                activePlatformCode = "A",
                isRefreshing = false
            )
        )

        val dummyGlanceId = object : GlanceId {}
        RefreshActionCallback().onAction(context, dummyGlanceId, actionParametersOf())

        val state = WidgetStateManager.getState(context) as ArrivalsViewState.Content
        assertTrue("Immediate feedback: isRefreshing must be true", state.isRefreshing)
        assertTrue("Expedited one-time work should be enqueued", ArrivalsUpdateWorker.oneTimeWorkEnqueuedCount > 0)
        assertNotNull(ArrivalsUpdateWorker.lastEnqueuedWorkRequest)
        assertTrue(ArrivalsUpdateWorker.lastEnqueuedWorkRequest!!.workSpec.expedited)
    }

    @Test
    fun onRefresh_triggersInProcessCoroutineUpdate_fetchesDeparturesAndUpdatesState() = runBlocking {
        val settingsRepo = cz.riegel.arrivals.data.SettingsRepository(context)
        settingsRepo.saveApiKey("test-key")
        settingsRepo.saveSimulatedHubIndex(1)

        val initialStop = sampleStop("s1", "Initial Stop", listOf("A"))
        WidgetStateManager.setState(
            context,
            ArrivalsViewState.Content(
                stops = listOf(initialStop),
                activeStopIndex = 0,
                activePlatformCode = "A",
                isRefreshing = false
            )
        )

        val now = Instant.now()
        val mockResponse = GolemioDepartureBoardResponse(
            stops = listOf(
                GolemioStopDto(stopId = "s_hlavni", stopName = "Hlavní nádraží", platformCode = "1", stopLat = 50.083, stopLon = 14.435)
            ),
            departures = listOf(
                GolemioDepartureDto(
                    departureTimestamp = GolemioTimestampDto(
                        scheduled = now.plusSeconds(120).toString(),
                        predicted = now.plusSeconds(120).toString()
                    ),
                    stop = GolemioDepartureStopDto(id = "s_hlavni", name = "Hlavní nádraží", platformCode = "1"),
                    route = GolemioRouteDto(shortName = "9", type = 0),
                    trip = GolemioTripDto(headsign = "Sídliště Řepy")
                )
            )
        )
        val fakeDataSource = object : cz.riegel.arrivals.data.TransitDataSource {
            override suspend fun fetchNearbyDepartures(
                coordinates: Coordinates,
                radiusMeters: Int,
                limit: Int
            ): Result<GolemioDepartureBoardResponse> = Result.success(mockResponse)

            override suspend fun fetchDeparturesForStops(
                stopNames: List<String>,
                limit: Int
            ): Result<GolemioDepartureBoardResponse> = Result.success(mockResponse)
        }
        ArrivalsUpdateWorker.defaultTransitDataSourceProvider = { _, _ -> fakeDataSource }

        val job = WidgetStateManager.onRefresh(context)
        job.join()

        val state = WidgetStateManager.getState(context) as ArrivalsViewState.Content
        assertFalse("Refreshing indicator should dismiss upon completion", state.isRefreshing)
        assertEquals("Hlavní nádraží", state.activeStop.name)
        assertEquals(1, state.activeDepartures.size)
        assertEquals("9", state.activeDepartures.first().line.name)
    }

    @Test
    fun inProcessUpdateAndWorker_concurrentExecution_isSafeAndIdempotent() = runBlocking {
        val settingsRepo = cz.riegel.arrivals.data.SettingsRepository(context)
        settingsRepo.saveApiKey("test-key")
        settingsRepo.saveSimulatedHubIndex(1)

        var callCount = 0
        val now = Instant.now()
        val mockResponse = GolemioDepartureBoardResponse(
            stops = listOf(
                GolemioStopDto(stopId = "s_hlavni", stopName = "Hlavní nádraží", platformCode = "1", stopLat = 50.083, stopLon = 14.435)
            ),
            departures = listOf(
                GolemioDepartureDto(
                    departureTimestamp = GolemioTimestampDto(
                        scheduled = now.plusSeconds(120).toString(),
                        predicted = now.plusSeconds(120).toString()
                    ),
                    stop = GolemioDepartureStopDto(id = "s_hlavni", name = "Hlavní nádraží", platformCode = "1"),
                    route = GolemioRouteDto(shortName = "9", type = 0),
                    trip = GolemioTripDto(headsign = "Sídliště Řepy")
                )
            )
        )
        val fakeDataSource = object : cz.riegel.arrivals.data.TransitDataSource {
            override suspend fun fetchNearbyDepartures(
                coordinates: Coordinates,
                radiusMeters: Int,
                limit: Int
            ): Result<GolemioDepartureBoardResponse> {
                callCount++
                return Result.success(mockResponse)
            }

            override suspend fun fetchDeparturesForStops(
                stopNames: List<String>,
                limit: Int
            ): Result<GolemioDepartureBoardResponse> {
                callCount++
                return Result.success(mockResponse)
            }
        }
        ArrivalsUpdateWorker.defaultTransitDataSourceProvider = { _, _ -> fakeDataSource }

        val job = WidgetStateManager.onRefresh(context)
        kotlinx.coroutines.delay(50)
        ArrivalsUpdateWorker.executeUpdate(context)
        job.join()

        val state = WidgetStateManager.getState(context) as ArrivalsViewState.Content
        assertFalse(state.isRefreshing)
        assertEquals("Hlavní nádraží", state.activeStop.name)
        assertEquals(1, callCount)
    }

    @Test
    fun executeUpdate_whenThrottled_clearsIsRefreshingFlag() = runBlocking {
        val now = Instant.now()
        val initialStop = sampleStop("s1", "Initial Stop", listOf("A"))
        WidgetStateManager.setState(
            context,
            ArrivalsViewState.Content(
                stops = listOf(initialStop),
                activeStopIndex = 0,
                activePlatformCode = "A",
                isRefreshing = true
            )
        )

        // Set lastSuccessfulUpdateMillis to now so that non-forced update is throttled
        ArrivalsUpdateWorker.lastSuccessfulUpdateMillis = now.toEpochMilli()

        val result = ArrivalsUpdateWorker.executeUpdate(
            appContext = context,
            currentTime = now.plusMillis(100),
            force = false
        )

        assertTrue(result)
        val state = WidgetStateManager.getState(context) as ArrivalsViewState.Content
        assertFalse("Throttled non-forced update must clear isRefreshing so widget does not stay stuck", state.isRefreshing)
    }

    @Test
    fun onRefresh_passesForceTrue_bypassesThrottling() = runBlocking {
        val settingsRepo = cz.riegel.arrivals.data.SettingsRepository(context)
        settingsRepo.saveApiKey("test-key")
        settingsRepo.saveSimulatedHubIndex(1)

        val now = Instant.now()
        var callCount = 0
        val mockResponse = GolemioDepartureBoardResponse(
            stops = listOf(
                GolemioStopDto(stopId = "s_hlavni", stopName = "Hlavní nádraží", platformCode = "1", stopLat = 50.083, stopLon = 14.435)
            ),
            departures = listOf(
                GolemioDepartureDto(
                    departureTimestamp = GolemioTimestampDto(
                        scheduled = now.plusSeconds(120).toString(),
                        predicted = now.plusSeconds(120).toString()
                    ),
                    stop = GolemioDepartureStopDto(id = "s_hlavni", name = "Hlavní nádraží", platformCode = "1"),
                    route = GolemioRouteDto(shortName = "9", type = 0),
                    trip = GolemioTripDto(headsign = "Sídliště Řepy")
                )
            )
        )
        val fakeDataSource = object : cz.riegel.arrivals.data.TransitDataSource {
            override suspend fun fetchNearbyDepartures(
                coordinates: Coordinates,
                radiusMeters: Int,
                limit: Int
            ): Result<GolemioDepartureBoardResponse> {
                callCount++
                return Result.success(mockResponse)
            }

            override suspend fun fetchDeparturesForStops(
                stopNames: List<String>,
                limit: Int
            ): Result<GolemioDepartureBoardResponse> {
                callCount++
                return Result.success(mockResponse)
            }
        }
        ArrivalsUpdateWorker.defaultTransitDataSourceProvider = { _, _ -> fakeDataSource }

        val initialStop = sampleStop("s1", "Initial Stop", listOf("A"))
        WidgetStateManager.setState(
            context,
            ArrivalsViewState.Content(
                stops = listOf(initialStop),
                activeStopIndex = 0,
                activePlatformCode = "A",
                isRefreshing = false
            )
        )

        // Set lastSuccessfulUpdateMillis to simulate a recent update 500ms ago
        ArrivalsUpdateWorker.lastSuccessfulUpdateMillis = System.currentTimeMillis() - 500L

        // onRefresh should pass force = true and NOT be throttled
        val job = WidgetStateManager.onRefresh(context)
        job.join()

        assertEquals("Manual refresh should not be throttled", 1, callCount)
    }
}

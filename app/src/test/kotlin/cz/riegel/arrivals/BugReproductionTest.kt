package cz.riegel.arrivals

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cz.riegel.arrivals.data.DeparturesRepository
import cz.riegel.arrivals.data.FakeTransitDataSource
import cz.riegel.arrivals.data.InMemoryDeparturesRepository
import cz.riegel.arrivals.data.LocationResolution
import cz.riegel.arrivals.data.StopRegistry
import cz.riegel.arrivals.data.model.GolemioDepartureBoardResponse
import cz.riegel.arrivals.data.model.GolemioDepartureDto
import cz.riegel.arrivals.data.model.GolemioDepartureStopDto
import cz.riegel.arrivals.data.model.GolemioRouteDto
import cz.riegel.arrivals.data.model.GolemioStopDto
import cz.riegel.arrivals.data.model.GolemioTimestampDto
import cz.riegel.arrivals.data.model.GolemioTripDto
import cz.riegel.arrivals.domain.ArrivalsAction
import cz.riegel.arrivals.domain.ArrivalsEngine
import cz.riegel.arrivals.domain.ArrivalsViewState
import cz.riegel.arrivals.domain.model.Coordinates
import cz.riegel.arrivals.widget.ArrivalsUpdateWorker
import cz.riegel.arrivals.widget.WidgetStateManager
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BugReproductionTest {

    @Test
    fun bug1_stopRegistry_lipanskaHasCorrectUtf8Encoding() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val registry = StopRegistry.create(context)

        // Coordinates right at Lipanská (50.08396, 14.45343)
        val closest = registry.findClosestStop(50.08396, 14.45343)
        assertNotNull(closest)
        assertEquals("Lipanská", closest!!.first.n)

        val nearby = registry.findNearbyStopNames(50.08396, 14.45343, 300)
        assertEquals("Lipanská", nearby.first())
    }

    @Test
    fun bug2_normalizerAndProximity_keepsClosestStopFirstEvenWhenGolemioReturnsMultipleStops() {
        val now = Instant.parse("2026-10-27T18:30:00Z")
        val engine = ArrivalsEngine()

        // Suppose Golemio returns Rokycanova first in JSON, and Lipanská second (or Lipanská has platforms in stops[])
        val dto = GolemioDepartureBoardResponse(
            stops = listOf(
                GolemioStopDto(stopId = "U743Z1P", stopName = "Rokycanova", platformCode = "A", stopLat = 50.08622, stopLon = 14.45553),
                GolemioStopDto(stopId = "U339Z1P", stopName = "Lipanská", platformCode = "A", stopLat = 50.08391, stopLon = 14.45371),
                GolemioStopDto(stopId = "U339Z2P", stopName = "Lipanská", platformCode = "B", stopLat = 50.08400, stopLon = 14.45315)
            ),
            departures = listOf(
                GolemioDepartureDto(
                    departureTimestamp = GolemioTimestampDto(scheduled = "2026-10-27T18:35:00Z", predicted = "2026-10-27T18:35:00Z"),
                    stop = GolemioDepartureStopDto(id = "U743Z1P", platformCode = "A"),
                    route = GolemioRouteDto(shortName = "136", type = 3),
                    trip = GolemioTripDto(headsign = "Sídliště Čakovice")
                ),
                GolemioDepartureDto(
                    departureTimestamp = GolemioTimestampDto(scheduled = "2026-10-27T18:36:00Z", predicted = "2026-10-27T18:36:00Z"),
                    stop = GolemioDepartureStopDto(id = "U339Z1P", platformCode = "A"),
                    route = GolemioRouteDto(shortName = "9", type = 0),
                    trip = GolemioTripDto(headsign = "Spojovací")
                )
            )
        )

        val state = engine.reduce(
            ArrivalsViewState.Loading(),
            ArrivalsAction.IngestApiResponse(
                response = dto,
                userCoordinates = Coordinates(50.08396, 14.45343),
                referenceTime = now
            )
        ) as ArrivalsViewState.Content

        assertEquals("Lipanská", state.stops.first().name)
        val lipanska = state.stops.find { it.name == "Lipanská" }
        assertNotNull("Lipanská should be normalized", lipanska)
        // Both platform A and platform B from dto.stops should be present on Lipanská
        assertEquals(listOf("A", "B"), lipanska!!.platforms.map { it.code })
    }

    @Test
    fun bug3_locationFallback_retainsLastKnownGpsWhenStationaryInEmulator() {
        val now = Instant.parse("2026-10-27T18:37:00Z")
        val repository: DeparturesRepository = InMemoryDeparturesRepository(maxStaleDurationMinutes = 30)

        // Stationary emulator GPS fix observed 10 minutes ago in SettingsActivity
        repository.saveLocation(
            coordinates = Coordinates(lat = 50.08396, lon = 14.45343),
            observedAt = now.minusSeconds(10 * 60)
        )

        // When background worker runs and freshCoordinates is null (foreground-only permission), cached fix is used
        val resolved = repository.resolveLocation(
            freshCoordinates = null,
            currentTime = now
        )
        assertNotNull("Should fall back to last known coordinates rather than losing GPS completely", resolved)
        assertTrue(resolved is LocationResolution.UndergroundFallback)
        assertEquals(50.08396, resolved!!.coordinates.lat, 0.0001)
    }

    @Test
    fun bug4_widgetPlatformSelection_survivesProcessRecreationAndUpdatesState() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val now = Instant.now()
        val repository = DeparturesRepository.create(context)

        val dto = GolemioDepartureBoardResponse(
            stops = listOf(
                GolemioStopDto(stopId = "S1_A", stopName = "Lipanská", platformCode = "A", stopLat = 50.08391, stopLon = 14.45371),
                GolemioStopDto(stopId = "S1_B", stopName = "Lipanská", platformCode = "B", stopLat = 50.08400, stopLon = 14.45315),
                GolemioStopDto(stopId = "S1_C", stopName = "Lipanská", platformCode = "C", stopLat = 50.08405, stopLon = 14.45320)
            ),
            departures = listOf(
                GolemioDepartureDto(
                    departureTimestamp = GolemioTimestampDto(scheduled = now.plusSeconds(300).toString()),
                    stop = GolemioDepartureStopDto(id = "S1_A", platformCode = "A"),
                    route = GolemioRouteDto(shortName = "9", type = 0),
                    trip = GolemioTripDto(headsign = "Spojovací")
                ),
                GolemioDepartureDto(
                    departureTimestamp = GolemioTimestampDto(scheduled = now.plusSeconds(360).toString()),
                    stop = GolemioDepartureStopDto(id = "S1_B", platformCode = "B"),
                    route = GolemioRouteDto(shortName = "5", type = 0),
                    trip = GolemioTripDto(headsign = "Slivenec")
                ),
                GolemioDepartureDto(
                    departureTimestamp = GolemioTimestampDto(scheduled = now.plusSeconds(420).toString()),
                    stop = GolemioDepartureStopDto(id = "S1_C", platformCode = "C"),
                    route = GolemioRouteDto(shortName = "26", type = 0),
                    trip = GolemioTripDto(headsign = "Dědina")
                )
            )
        )
        repository.saveDepartures(dto, now)
        repository.saveLocation(Coordinates(50.08391, 14.45371), now)

        // Simulate process recreation where in-memory state was reset to Loading
        WidgetStateManager.setState(context, ArrivalsViewState.Loading())

        // User clicks Platform "A", then Platform "B", then Platform "C"
        WidgetStateManager.onSelectPlatform(context, "A")
        val stateAfterA = WidgetStateManager.getState(context, now) as ArrivalsViewState.Content
        assertEquals("A", stateAfterA.activePlatformCode)
        assertEquals(0, stateAfterA.activeDistanceMeters)
        assertEquals("5 min", stateAfterA.activeDepartures.first().countdown.displayText)

        // Advance clock by 1 minute without calling API -> countdown ticks from 5 min to 4 min
        WidgetStateManager.tickCountdowns(context, now.plusSeconds(60))
        val stateAfterTick = WidgetStateManager.getState(context, now.plusSeconds(60)) as ArrivalsViewState.Content
        assertEquals("4 min", stateAfterTick.activeDepartures.first().countdown.displayText)

        WidgetStateManager.onSelectPlatform(context, "B")
        val stateAfterB = WidgetStateManager.getState(context, now.plusSeconds(60))
        assertTrue("State should be Content after selecting platform B, but was $stateAfterB", stateAfterB is ArrivalsViewState.Content)
        assertEquals("B", (stateAfterB as ArrivalsViewState.Content).activePlatformCode)
        assertEquals("5", stateAfterB.activeDepartures.first().line.name)
        assertEquals("5 min", stateAfterB.activeDepartures.first().countdown.displayText)
        assertTrue("Distance to platform B should be > 0m", (stateAfterB.activeDistanceMeters ?: 0) > 0)

        WidgetStateManager.onSelectPlatform(context, "C")
        val stateAfterC = WidgetStateManager.getState(context, now.plusSeconds(60)) as ArrivalsViewState.Content
        assertEquals("C", stateAfterC.activePlatformCode)
        assertEquals("26", stateAfterC.activeDepartures.first().line.name)
        assertEquals("6 min", stateAfterC.activeDepartures.first().countdown.displayText)
        assertEquals(stateAfterC.activePlatform?.distanceMeters, stateAfterC.activeDistanceMeters)
    }

    @Test
    fun workerAdaptiveRadius_expandsUntilNonEmptyResponseViaFakeTransitDataSource() = runTest {
        val fakeDataSource = FakeTransitDataSource()
        val coords = Coordinates(50.08396, 14.45343)
        val nonEmptyDto = GolemioDepartureBoardResponse(
            stops = listOf(GolemioStopDto(stopId = "S1", stopName = "Lipanská", platformCode = "A", stopLat = 50.08391, stopLon = 14.45371))
        )
        fakeDataSource.setResponseForRadius(100, Result.success(GolemioDepartureBoardResponse()))
        fakeDataSource.setResponseForRadius(250, Result.success(nonEmptyDto))

        val result = ArrivalsUpdateWorker.fetchWithAdaptiveRadius(
            transitDataSource = fakeDataSource,
            userCoordinates = coords,
            preferredRadius = 100
        )

        assertTrue(result.isSuccess)
        assertEquals(1, result.getOrThrow().stops.size)
        assertEquals(listOf(100, 250), fakeDataSource.nearbyCalls.map { it.radiusMeters })
    }
}

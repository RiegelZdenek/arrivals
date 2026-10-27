package cz.riegel.arrivals

import android.content.Context
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.test.core.app.ApplicationProvider
import cz.riegel.arrivals.data.DeparturesCacheRepository
import cz.riegel.arrivals.data.LocationFallbackStrategy
import cz.riegel.arrivals.data.StopRegistry
import cz.riegel.arrivals.data.UserCoordinates
import cz.riegel.arrivals.data.model.GolemioDepartureBoardResponse
import cz.riegel.arrivals.data.model.GolemioDepartureDto
import cz.riegel.arrivals.data.model.GolemioDepartureStopDto
import cz.riegel.arrivals.data.model.GolemioRouteDto
import cz.riegel.arrivals.data.model.GolemioStopDto
import cz.riegel.arrivals.data.model.GolemioTimestampDto
import cz.riegel.arrivals.data.model.GolemioTripDto
import cz.riegel.arrivals.domain.ArrivalsViewState
import cz.riegel.arrivals.domain.DepartureNormalizer
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
        val registry = StopRegistry(context)

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
        val normalizer = DepartureNormalizer()

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

        val stops = normalizer.normalize(dto, referenceTime = now)
        val lipanska = stops.find { it.name == "Lipanská" }
        assertNotNull("Lipanská should be normalized", lipanska)
        // Both platform A and platform B from dto.stops should be present on Lipanská
        assertEquals(listOf("A", "B"), lipanska!!.platforms.map { it.code })
    }

    @Test
    fun bug3_locationFallback_retainsLastKnownGpsWhenStationaryInEmulator() {
        val now = Instant.parse("2026-10-27T18:37:00Z")
        val strategy = LocationFallbackStrategy(maxStaleDurationMinutes = 30)

        // Stationary emulator GPS fix observed 10 minutes ago in SettingsActivity (even if emulator boot was earlier)
        val observedInForeground = strategy.fromProviderReading(
            lat = 50.08396,
            lon = 14.45343,
            accuracyMeters = 5.0f,
            observedAt = now.minusSeconds(10 * 60)
        )

        // When background worker runs and freshLocation is null (foreground-only permission), cached fix is used
        val resolved = strategy.resolveCoordinates(
            freshLocation = null,
            cachedLocation = observedInForeground,
            currentTime = now
        )
        assertNotNull("Should fall back to last known coordinates rather than losing GPS completely", resolved)
        assertEquals(50.08396, resolved!!.lat, 0.0001)
    }

    @Test
    fun bug4_widgetPlatformSelection_survivesProcessRecreationAndUpdatesState() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val now = Instant.parse("2026-10-27T18:30:00Z")
        val cacheRepo = DeparturesCacheRepository(context)

        val dto = GolemioDepartureBoardResponse(
            stops = listOf(
                GolemioStopDto(stopId = "S1_A", stopName = "Lipanská", platformCode = "A", stopLat = 50.08391, stopLon = 14.45371),
                GolemioStopDto(stopId = "S1_B", stopName = "Lipanská", platformCode = "B", stopLat = 50.08400, stopLon = 14.45315),
                GolemioStopDto(stopId = "S1_C", stopName = "Lipanská", platformCode = "C", stopLat = 50.08405, stopLon = 14.45320)
            ),
            departures = listOf(
                GolemioDepartureDto(
                    departureTimestamp = GolemioTimestampDto(scheduled = "2026-10-27T18:35:00Z"),
                    stop = GolemioDepartureStopDto(id = "S1_A", platformCode = "A"),
                    route = GolemioRouteDto(shortName = "9", type = 0),
                    trip = GolemioTripDto(headsign = "Spojovací")
                ),
                GolemioDepartureDto(
                    departureTimestamp = GolemioTimestampDto(scheduled = "2026-10-27T18:36:00Z"),
                    stop = GolemioDepartureStopDto(id = "S1_B", platformCode = "B"),
                    route = GolemioRouteDto(shortName = "5", type = 0),
                    trip = GolemioTripDto(headsign = "Slivenec")
                ),
                GolemioDepartureDto(
                    departureTimestamp = GolemioTimestampDto(scheduled = "2026-10-27T18:37:00Z"),
                    stop = GolemioDepartureStopDto(id = "S1_C", platformCode = "C"),
                    route = GolemioRouteDto(shortName = "26", type = 0),
                    trip = GolemioTripDto(headsign = "Dědina")
                )
            )
        )
        cacheRepo.saveLastResponse(dto, now)
        cacheRepo.saveLastCoordinates(UserCoordinates(50.08391, 14.45371, now))

        // Simulate process recreation where in-memory state was reset to Loading
        WidgetStateManager.setState(context, ArrivalsViewState.Loading())

        // User clicks Platform "B", then Platform "C"
        WidgetStateManager.onSelectPlatform(context, "B")
        val stateAfterB = WidgetStateManager.getState(context)
        assertTrue("State should be Content after selecting platform B, but was $stateAfterB", stateAfterB is ArrivalsViewState.Content)
        assertEquals("B", (stateAfterB as ArrivalsViewState.Content).activePlatformCode)
        assertEquals("5", stateAfterB.activeDepartures.first().line.name)

        WidgetStateManager.onSelectPlatform(context, "C")
        val stateAfterC = WidgetStateManager.getState(context) as ArrivalsViewState.Content
        assertEquals("C", stateAfterC.activePlatformCode)
        assertEquals("26", stateAfterC.activeDepartures.first().line.name)
    }
}

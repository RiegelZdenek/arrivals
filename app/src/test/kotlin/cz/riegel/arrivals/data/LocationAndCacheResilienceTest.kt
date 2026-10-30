package cz.riegel.arrivals.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cz.riegel.arrivals.data.model.GolemioDepartureBoardResponse
import cz.riegel.arrivals.data.model.GolemioDepartureDto
import cz.riegel.arrivals.data.model.GolemioDepartureStopDto
import cz.riegel.arrivals.data.model.GolemioRouteDto
import cz.riegel.arrivals.data.model.GolemioStopDto
import cz.riegel.arrivals.data.model.GolemioTimestampDto
import cz.riegel.arrivals.data.model.GolemioTripDto
import cz.riegel.arrivals.domain.ArrivalsViewState
import cz.riegel.arrivals.domain.model.CountdownDisplay
import cz.riegel.arrivals.domain.model.Delay
import cz.riegel.arrivals.domain.model.Departure
import cz.riegel.arrivals.domain.model.Line
import cz.riegel.arrivals.domain.model.Platform
import cz.riegel.arrivals.domain.model.Stop
import cz.riegel.arrivals.domain.model.TransportMode
import cz.riegel.arrivals.widget.ArrivalsUpdateWorker
import cz.riegel.arrivals.widget.WidgetStateManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocationAndCacheResilienceTest {

    private val locationStrategy = LocationFallbackStrategy()

    @Test
    fun resolveCoordinates_acceptsFreshLocation() {
        val now = Instant.parse("2026-10-23T14:30:00Z")
        val freshLocation = UserCoordinates(lat = 50.0755, lon = 14.4302, timestamp = now)

        val result = locationStrategy.resolveCoordinates(
            freshLocation = freshLocation,
            cachedLocation = null,
            currentTime = now
        )

        assertNotNull(result)
        assertEquals(50.0755, result!!.lat, 0.0001)
        assertFalse(result.isStaleUndergroundFallback)
    }

    @Test
    fun resolveCoordinates_acceptsStaleLocationUnder30MinutesWhenFreshGpsFails() {
        val now = Instant.parse("2026-10-23T14:30:00Z")
        // Stale fix from 20 minutes ago (e.g. commuter walked down into underground metro station)
        val twentyMinutesAgo = now.minusSeconds(20 * 60)
        val cachedLocation = UserCoordinates(lat = 50.0755, lon = 14.4302, timestamp = twentyMinutesAgo)

        val result = locationStrategy.resolveCoordinates(
            freshLocation = null, // GPS lost underground
            cachedLocation = cachedLocation,
            currentTime = now
        )

        assertNotNull("Should accept cached location up to 30 min old (ADR-0003)", result)
        assertEquals(50.0755, result!!.lat, 0.0001)
        assertTrue(result.isStaleUndergroundFallback)
    }

    @Test
    fun resolveCoordinates_rejectsStaleLocationOlderThan30Minutes() {
        val now = Instant.parse("2026-10-23T14:30:00Z")
        // Stale fix from 31 minutes ago
        val thirtyOneMinutesAgo = now.minusSeconds(31 * 60)
        val cachedLocation = UserCoordinates(lat = 50.0755, lon = 14.4302, timestamp = thirtyOneMinutesAgo)

        val result = locationStrategy.resolveCoordinates(
            freshLocation = null,
            cachedLocation = cachedLocation,
            currentTime = now
        )

        assertNull("Should reject cached location older than 30 minutes", result)
    }

    @Test
    fun cacheResilience_createsWarningBannerFormat() {
        val fetchTime = Instant.parse("2026-10-23T13:42:00Z") // 15:42 in Prague
        val banner = CacheResilience.formatCachedWarning(fetchTime)
        assertEquals("Cached 15:42 • Tap to retry", banner)
    }

    @Test
    fun restoreCachedDepartures_unitTest_fallbackBehaviorAndWarning() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val cacheRepo = DeparturesCacheRepository(context)
        val now = Instant.now()

        val cachedResponse = GolemioDepartureBoardResponse(
            stops = listOf(
                GolemioStopDto(stopId = "U100Z1", stopName = "Anděl", platformCode = "A")
            ),
            departures = listOf(
                GolemioDepartureDto(
                    departureTimestamp = GolemioTimestampDto(
                        scheduled = now.plusSeconds(300).toString(),
                        predicted = now.plusSeconds(300).toString()
                    ),
                    stop = GolemioDepartureStopDto(id = "U100Z1", name = "Anděl", platformCode = "A"),
                    route = GolemioRouteDto(shortName = "9", type = 0),
                    trip = GolemioTripDto(headsign = "Sídliště Řepy")
                )
            )
        )
        cacheRepo.saveLastResponse(cachedResponse, now.minusSeconds(60))

        val stop = Stop("U100Z1", "Anděl", platforms = listOf(Platform("U100Z1", "A")))
        WidgetStateManager.setState(
            context,
            ArrivalsViewState.Content(
                stops = listOf(stop),
                activeStopIndex = 0,
                activePlatformCode = "A",
                isLoadingDepartures = true,
                isRefreshing = true
            )
        )

        ArrivalsUpdateWorker.restoreCachedDepartures(
            context = context,
            now = now,
            fallbackErrorMessage = "Timeout"
        )

        val restored = WidgetStateManager.getState(context) as ArrivalsViewState.Content
        assertFalse(restored.isLoadingDepartures)
        assertFalse(restored.isRefreshing)
        assertNotNull(restored.cachedWarning)
        assertTrue(restored.cachedWarning!!.contains("Cached"))
        assertEquals("9", restored.activeDepartures.first().line.name)
    }
}

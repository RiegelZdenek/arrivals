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
import cz.riegel.arrivals.domain.model.Coordinates
import cz.riegel.arrivals.domain.model.Platform
import cz.riegel.arrivals.domain.model.Stop
import cz.riegel.arrivals.widget.ArrivalsUpdateWorker
import cz.riegel.arrivals.widget.WidgetStateManager
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
class LocationAndCacheResilienceTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("arrivals_cache_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        WidgetStateManager.repositoryProvider = { DeparturesRepository.create(it) }
    }

    private fun adaptersUnderTest(): List<DeparturesRepository> {
        context.getSharedPreferences("arrivals_cache_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        return listOf(
            InMemoryDeparturesRepository(),
            SharedPreferencesDeparturesRepository(context)
        )
    }

    @Test
    fun resolveLocation_acceptsFreshCoordinatesAndPersistsThem() {
        val now = Instant.parse("2026-10-23T14:30:00Z")
        val freshCoordinates = Coordinates(lat = 50.0755, lon = 14.4302)

        for (repository in adaptersUnderTest()) {
            val result = repository.resolveLocation(
                freshCoordinates = freshCoordinates,
                currentTime = now
            )

            assertTrue(result is LocationResolution.Fresh)
            assertEquals(freshCoordinates, result!!.coordinates)
            assertEquals(freshCoordinates, repository.getLastCoordinates())
        }
    }

    @Test
    fun resolveLocation_acceptsStaleLocationUnder5MinutesWithoutWarningBanner() {
        val now = Instant.parse("2026-10-23T14:30:00Z")
        val threeMinutesAgo = now.minusSeconds(3 * 60)
        val cachedCoordinates = Coordinates(lat = 50.0755, lon = 14.4302)

        for (repository in adaptersUnderTest()) {
            repository.saveLocation(cachedCoordinates, observedAt = threeMinutesAgo)

            val result = repository.resolveLocation(
                freshCoordinates = null,
                currentTime = now
            )

            assertTrue(result is LocationResolution.UndergroundFallback)
            val fallback = result as LocationResolution.UndergroundFallback
            assertEquals(cachedCoordinates, fallback.coordinates)
            assertNull(fallback.warningBanner)
        }
    }

    @Test
    fun resolveLocation_acceptsStaleLocationUpTo30MinutesWithUndergroundWarningBanner() {
        val now = Instant.parse("2026-10-23T14:30:00Z")
        val twentyMinutesAgo = now.minusSeconds(20 * 60)
        val cachedCoordinates = Coordinates(lat = 50.0755, lon = 14.4302)

        for (repository in adaptersUnderTest()) {
            repository.saveLocation(cachedCoordinates, observedAt = twentyMinutesAgo)

            val result = repository.resolveLocation(
                freshCoordinates = null,
                currentTime = now
            )

            assertNotNull("Should accept cached location up to 30 min old (ADR-0003)", result)
            assertTrue(result is LocationResolution.UndergroundFallback)
            val fallback = result as LocationResolution.UndergroundFallback
            assertEquals(cachedCoordinates, fallback.coordinates)
            assertEquals("Underground GPS fallback • Last known location", fallback.warningBanner)
        }
    }

    @Test
    fun resolveLocation_rejectsStaleLocationOlderThan30Minutes() {
        val now = Instant.parse("2026-10-23T14:30:00Z")
        val thirtyOneMinutesAgo = now.minusSeconds(31 * 60)
        val cachedCoordinates = Coordinates(lat = 50.0755, lon = 14.4302)

        for (repository in adaptersUnderTest()) {
            repository.saveLocation(cachedCoordinates, observedAt = thirtyOneMinutesAgo)

            val result = repository.resolveLocation(
                freshCoordinates = null,
                currentTime = now
            )

            assertNull("Should reject cached location older than 30 minutes", result)
        }
    }

    @Test
    fun getCachedDepartures_returnsResponseCoordinatesAndFormattedPragueWarningBanner() {
        val fetchTime = Instant.parse("2026-10-23T13:42:00Z") // 15:42 in Prague (CEST)
        val coords = Coordinates(lat = 50.0755, lon = 14.4302)
        val response = GolemioDepartureBoardResponse(
            stops = listOf(GolemioStopDto(stopId = "U100Z1", stopName = "Anděl", platformCode = "A"))
        )

        for (repository in adaptersUnderTest()) {
            repository.saveLocation(coords, observedAt = fetchTime)
            repository.saveDepartures(response, timestamp = fetchTime)

            val cached = repository.getCachedDepartures()
            assertNotNull(cached)
            assertEquals(response, cached!!.response)
            assertEquals(coords, cached.coordinates)
            assertEquals("Cached 15:42 • Tap to retry", cached.warningBanner)
        }
    }

    @Test
    fun activeSelection_persistsStopIndexAndPlatformCodeAcrossAdapters() {
        for (repository in adaptersUnderTest()) {
            assertEquals(0, repository.getActiveStopIndex())
            assertNull(repository.getActivePlatformCode())

            repository.saveActiveSelection(stopIndex = 2, platformCode = "B")
            assertEquals(2, repository.getActiveStopIndex())
            assertEquals("B", repository.getActivePlatformCode())
        }
    }

    @Test
    fun restoreCachedDepartures_unitTest_fallbackBehaviorAndWarning() = runBlocking {
        val repository = InMemoryDeparturesRepository()
        WidgetStateManager.repositoryProvider = { repository }
        val now = Instant.parse("2026-10-23T13:43:00Z")
        val fetchTime = Instant.parse("2026-10-23T13:42:00Z")

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
        repository.saveDepartures(cachedResponse, fetchTime)

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
            fallbackErrorMessage = "Timeout",
            repository = repository
        )

        val restored = WidgetStateManager.getState(context, now) as ArrivalsViewState.Content
        assertFalse(restored.isLoadingDepartures)
        assertFalse(restored.isRefreshing)
        assertEquals("Cached 15:42 • Tap to retry", restored.cachedWarning)
        assertEquals("9", restored.activeDepartures.first().line.name)
    }
}

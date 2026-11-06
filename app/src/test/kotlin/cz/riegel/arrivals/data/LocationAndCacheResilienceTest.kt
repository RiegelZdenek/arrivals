package cz.riegel.arrivals.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.TestListenableWorkerBuilder
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
    fun resolveLocation_acceptsStaleLocationUpTo15MinutesWithUndergroundWarningBanner() {
        val now = Instant.parse("2026-10-23T14:30:00Z")
        val twelveMinutesAgo = now.minusSeconds(12 * 60)
        val cachedCoordinates = Coordinates(lat = 50.0755, lon = 14.4302)

        for (repository in adaptersUnderTest()) {
            repository.saveLocation(cachedCoordinates, observedAt = twelveMinutesAgo)

            val result = repository.resolveLocation(
                freshCoordinates = null,
                currentTime = now
            )

            assertNotNull("Should accept cached location up to 15 min old (ADR-0003)", result)
            assertTrue(result is LocationResolution.UndergroundFallback)
            val fallback = result as LocationResolution.UndergroundFallback
            assertEquals(cachedCoordinates, fallback.coordinates)
            assertEquals("Underground GPS fallback • Last known location", fallback.warningBanner)
        }
    }

    @Test
    fun resolveLocation_rejectsStaleLocationOlderThan15Minutes() {
        val now = Instant.parse("2026-10-23T14:30:00Z")
        val sixteenMinutesAgo = now.minusSeconds(16 * 60)
        val cachedCoordinates = Coordinates(lat = 50.0755, lon = 14.4302)

        for (repository in adaptersUnderTest()) {
            repository.saveLocation(cachedCoordinates, observedAt = sixteenMinutesAgo)

            val result = repository.resolveLocation(
                freshCoordinates = null,
                currentTime = now
            )

            assertNull("Should reject cached location older than 15 minutes", result)
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
            ),
            repository = repository
        )

        ArrivalsUpdateWorker.restoreCachedDepartures(
            context = context,
            now = now,
            fallbackErrorMessage = "Timeout",
            repository = repository
        )

        val restored = WidgetStateManager.getState(context, now, repository = repository) as ArrivalsViewState.Content
        assertFalse(restored.isLoadingDepartures)
        assertFalse(restored.isRefreshing)
        assertEquals("Cached 15:42 • Tap to retry", restored.cachedWarning)
        assertEquals("9", restored.activeDepartures.first().line.name)
    }

    @Test
    fun rehydrateFromCache_retainsWarningBannerFromCachedDepartures() {
        val repository = InMemoryDeparturesRepository()
        val now = Instant.parse("2026-10-23T13:45:00Z")
        val fetchTime = Instant.parse("2026-10-23T13:42:00Z") // 15:42 in Prague

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

        // Test direct internal rehydrateFromCache
        val directRehydrated = WidgetStateManager.rehydrateFromCache(now, repository)
        assertNotNull("Rehydration should succeed when cache is present", directRehydrated)
        assertEquals("Cached 15:42 • Tap to retry", directRehydrated!!.cachedWarning)

        // Test process restoration via getState() when in Loading state
        WidgetStateManager.setState(context, ArrivalsViewState.Loading(), repository)
        val state = WidgetStateManager.getState(context, now, repository)
        assertTrue("State should be Content after rehydrating from cache", state is ArrivalsViewState.Content)
        val content = state as ArrivalsViewState.Content
        assertEquals("Cached 15:42 • Tap to retry", content.cachedWarning)
        assertEquals("Anděl", content.activeStop.name)
        assertEquals(1, content.activeDepartures.size)
    }

    @Test
    fun restoreCachedDepartures_evictsDeparturesElapsedMoreThan30Seconds() = runBlocking {
        val repository = InMemoryDeparturesRepository()
        val now = Instant.parse("2026-10-23T13:45:00Z")
        val fetchTime = Instant.parse("2026-10-23T13:42:00Z")

        val cachedResponse = GolemioDepartureBoardResponse(
            stops = listOf(
                GolemioStopDto(stopId = "U100Z1", stopName = "Anděl", platformCode = "A")
            ),
            departures = listOf(
                // Expired: 45 seconds ago (< now - 30s)
                GolemioDepartureDto(
                    departureTimestamp = GolemioTimestampDto(
                        scheduled = now.minusSeconds(45).toString(),
                        predicted = now.minusSeconds(45).toString()
                    ),
                    stop = GolemioDepartureStopDto(id = "U100Z1", name = "Anděl", platformCode = "A"),
                    route = GolemioRouteDto(shortName = "12", type = 0),
                    trip = GolemioTripDto(headsign = "Výstaviště")
                ),
                // Expired: 31 seconds ago (< now - 30s)
                GolemioDepartureDto(
                    departureTimestamp = GolemioTimestampDto(
                        scheduled = now.minusSeconds(31).toString(),
                        predicted = now.minusSeconds(31).toString()
                    ),
                    stop = GolemioDepartureStopDto(id = "U100Z1", name = "Anděl", platformCode = "A"),
                    route = GolemioRouteDto(shortName = "20", type = 0),
                    trip = GolemioTripDto(headsign = "Divoká Šárka")
                ),
                // Upcoming: 120 seconds in future
                GolemioDepartureDto(
                    departureTimestamp = GolemioTimestampDto(
                        scheduled = now.plusSeconds(120).toString(),
                        predicted = now.plusSeconds(120).toString()
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
                activePlatformCode = "A"
            ),
            repository = repository
        )

        ArrivalsUpdateWorker.restoreCachedDepartures(
            context = context,
            now = now,
            fallbackErrorMessage = "Connection failed",
            repository = repository
        )

        val restored = WidgetStateManager.getState(context, now, repository = repository) as ArrivalsViewState.Content
        assertEquals(1, restored.activeDepartures.size)
        assertEquals("9", restored.activeDepartures.first().line.name)
        assertEquals("Cached 15:42 • Tap to retry", restored.cachedWarning)
    }

    @Test
    fun restoreCachedDepartures_whenAllCachedDeparturesElapsed_displaysExplicitEmptyStateWithOfflineBanner() = runBlocking {
        val repository = InMemoryDeparturesRepository()
        val now = Instant.parse("2026-10-23T13:45:00Z")
        val fetchTime = Instant.parse("2026-10-23T13:42:00Z")

        val cachedResponse = GolemioDepartureBoardResponse(
            stops = listOf(
                GolemioStopDto(stopId = "U100Z1", stopName = "Anděl", platformCode = "A")
            ),
            departures = listOf(
                // All departures elapsed (> 30s ago)
                GolemioDepartureDto(
                    departureTimestamp = GolemioTimestampDto(
                        scheduled = now.minusSeconds(60).toString(),
                        predicted = now.minusSeconds(60).toString()
                    ),
                    stop = GolemioDepartureStopDto(id = "U100Z1", name = "Anděl", platformCode = "A"),
                    route = GolemioRouteDto(shortName = "9", type = 0),
                    trip = GolemioTripDto(headsign = "Sídliště Řepy")
                ),
                GolemioDepartureDto(
                    departureTimestamp = GolemioTimestampDto(
                        scheduled = now.minusSeconds(40).toString(),
                        predicted = now.minusSeconds(40).toString()
                    ),
                    stop = GolemioDepartureStopDto(id = "U100Z1", name = "Anděl", platformCode = "A"),
                    route = GolemioRouteDto(shortName = "12", type = 0),
                    trip = GolemioTripDto(headsign = "Výstaviště")
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
                activePlatformCode = "A"
            ),
            repository = repository
        )

        ArrivalsUpdateWorker.restoreCachedDepartures(
            context = context,
            now = now,
            fallbackErrorMessage = "Connection timed out",
            repository = repository
        )

        val state = WidgetStateManager.getState(context, now, repository = repository)
        assertTrue("State should be Content with empty departures, not Error", state is ArrivalsViewState.Content)
        val content = state as ArrivalsViewState.Content
        assertTrue("All departures should be filtered out / empty", content.activeDepartures.isEmpty())
        assertEquals("No upcoming departures • Offline • Tap to retry", content.cachedWarning)
        assertEquals("Anděl", content.activeStop.name)
    }

    @Test
    fun restoreCachedDepartures_whenActiveStopHasOnlyElapsedDepartures_displaysExplicitEmptyStateWithOfflineBanner() = runBlocking {
        val repository = InMemoryDeparturesRepository()
        val now = Instant.parse("2026-10-23T13:45:00Z")
        val fetchTime = Instant.parse("2026-10-23T13:42:00Z")

        val cachedResponse = GolemioDepartureBoardResponse(
            stops = listOf(
                GolemioStopDto(stopId = "U100Z1", stopName = "Anděl", platformCode = "A"),
                GolemioStopDto(stopId = "U200Z1", stopName = "Karlovo náměstí", platformCode = "B")
            ),
            departures = listOf(
                // Anděl: expired
                GolemioDepartureDto(
                    departureTimestamp = GolemioTimestampDto(
                        scheduled = now.minusSeconds(60).toString(),
                        predicted = now.minusSeconds(60).toString()
                    ),
                    stop = GolemioDepartureStopDto(id = "U100Z1", name = "Anděl", platformCode = "A"),
                    route = GolemioRouteDto(shortName = "9", type = 0),
                    trip = GolemioTripDto(headsign = "Sídliště Řepy")
                ),
                // Karlovo náměstí: upcoming
                GolemioDepartureDto(
                    departureTimestamp = GolemioTimestampDto(
                        scheduled = now.plusSeconds(300).toString(),
                        predicted = now.plusSeconds(300).toString()
                    ),
                    stop = GolemioDepartureStopDto(id = "U200Z1", name = "Karlovo náměstí", platformCode = "B"),
                    route = GolemioRouteDto(shortName = "22", type = 0),
                    trip = GolemioTripDto(headsign = "Bílá Hora")
                )
            )
        )
        repository.saveDepartures(cachedResponse, fetchTime)

        // Commuter is actively focused on Anděl
        val andelStop = Stop("U100Z1", "Anděl", platforms = listOf(Platform("U100Z1", "A")))
        val knStop = Stop("U200Z1", "Karlovo náměstí", platforms = listOf(Platform("U200Z1", "B")))
        WidgetStateManager.setState(
            context,
            ArrivalsViewState.Content(
                stops = listOf(andelStop, knStop),
                activeStopIndex = 0,
                activePlatformCode = "A"
            ),
            repository = repository
        )

        ArrivalsUpdateWorker.restoreCachedDepartures(
            context = context,
            now = now,
            fallbackErrorMessage = "Connection timed out",
            repository = repository
        )

        val state = WidgetStateManager.getState(context, now, repository = repository)
        assertTrue("State should be Content", state is ArrivalsViewState.Content)
        val content = state as ArrivalsViewState.Content
        assertEquals("Anděl", content.activeStop.name)
        assertTrue("Active stop departures should be empty because they elapsed", content.activeDepartures.isEmpty())
        assertEquals("No upcoming departures • Offline • Tap to retry", content.cachedWarning)
    }

    private fun createMockLocation(provider: String, lat: Double = 50.0755, lon: Double = 14.4302): android.location.Location {
        return android.location.Location(provider).apply {
            latitude = lat
            longitude = lon
            time = System.currentTimeMillis()
        }
    }

    @Test
    fun multiTierResolver_tier1Success_returnsHighAccuracyWithoutCallingSubsequentTiers() = runBlocking {
        var tier1Called = false
        var tier2Called = false
        var tier3Called = false
        var tier4Called = false
        var passedTimeout: Long? = null

        val expectedLocation = createMockLocation("gps")
        val resolver = DefaultLocationResolver(
            context = context,
            queryHighAccuracy = { timeout ->
                tier1Called = true
                passedTimeout = timeout
                expectedLocation
            },
            queryBalancedPower = {
                tier2Called = true
                null
            },
            queryFusedLastLocation = {
                tier3Called = true
                null
            },
            queryLocationManager = {
                tier4Called = true
                null
            }
        )

        val resolved = resolver.resolveLocation(highAccuracyTimeoutMs = 4_000L, balancedTimeoutMs = 3_000L)

        assertTrue(tier1Called)
        assertEquals(4_000L, passedTimeout)
        assertFalse("Tier 2 should not be queried when Tier 1 succeeds", tier2Called)
        assertFalse("Tier 3 should not be queried when Tier 1 succeeds", tier3Called)
        assertFalse("Tier 4 should not be queried when Tier 1 succeeds", tier4Called)
        assertEquals(expectedLocation, resolved)
    }

    @Test
    fun multiTierResolver_tier1TimeoutOrNull_fallsBackToTier2Balanced() = runBlocking {
        var tier1Called = false
        var tier2Called = false
        var tier3Called = false
        var tier4Called = false
        var passedBalancedTimeout: Long? = null

        val expectedLocation = createMockLocation("network")
        val resolver = DefaultLocationResolver(
            context = context,
            queryHighAccuracy = {
                tier1Called = true
                null // Simulated GPS timeout / null
            },
            queryBalancedPower = { timeout ->
                tier2Called = true
                passedBalancedTimeout = timeout
                expectedLocation
            },
            queryFusedLastLocation = {
                tier3Called = true
                null
            },
            queryLocationManager = {
                tier4Called = true
                null
            }
        )

        val resolved = resolver.resolveLocation(highAccuracyTimeoutMs = 4_000L, balancedTimeoutMs = 3_000L)

        assertTrue(tier1Called)
        assertTrue(tier2Called)
        assertEquals(3_000L, passedBalancedTimeout)
        assertFalse("Tier 3 should not be queried when Tier 2 succeeds", tier3Called)
        assertFalse("Tier 4 should not be queried when Tier 2 succeeds", tier4Called)
        assertEquals(expectedLocation, resolved)
    }

    @Test
    fun multiTierResolver_tier1Exception_seamlesslyFallsBackToTier2Balanced() = runBlocking {
        var tier2Called = false
        val expectedLocation = createMockLocation("network")
        val resolver = DefaultLocationResolver(
            context = context,
            queryHighAccuracy = {
                throw SecurityException("Mock GPS permission failure")
            },
            queryBalancedPower = {
                tier2Called = true
                expectedLocation
            },
            queryFusedLastLocation = { null },
            queryLocationManager = { null }
        )

        val resolved = resolver.resolveLocation()

        assertTrue(tier2Called)
        assertEquals(expectedLocation, resolved)
    }

    @Test
    fun multiTierResolver_tier2TimeoutOrNull_fallsBackToTier3FusedLastLocation() = runBlocking {
        var tier1Called = false
        var tier2Called = false
        var tier3Called = false
        var tier4Called = false

        val expectedLocation = createMockLocation("fused")
        val resolver = DefaultLocationResolver(
            context = context,
            queryHighAccuracy = {
                tier1Called = true
                null
            },
            queryBalancedPower = {
                tier2Called = true
                null
            },
            queryFusedLastLocation = {
                tier3Called = true
                expectedLocation
            },
            queryLocationManager = {
                tier4Called = true
                null
            }
        )

        val resolved = resolver.resolveLocation()

        assertTrue(tier1Called)
        assertTrue(tier2Called)
        assertTrue(tier3Called)
        assertFalse("Tier 4 should not be queried when Tier 3 succeeds", tier4Called)
        assertEquals(expectedLocation, resolved)
    }

    @Test
    fun multiTierResolver_tier2Exception_seamlesslyFallsBackToTier3() = runBlocking {
        var tier3Called = false
        val expectedLocation = createMockLocation("fused")
        val resolver = DefaultLocationResolver(
            context = context,
            queryHighAccuracy = { null },
            queryBalancedPower = { throw RuntimeException("Cellular tower triangulation error") },
            queryFusedLastLocation = {
                tier3Called = true
                expectedLocation
            },
            queryLocationManager = { null }
        )

        val resolved = resolver.resolveLocation()

        assertTrue(tier3Called)
        assertEquals(expectedLocation, resolved)
    }

    @Test
    fun multiTierResolver_tier3NullOrException_fallsBackToTier4LocationManager() = runBlocking {
        var tier4Called = false
        val expectedLocation = createMockLocation("passive")
        val resolver = DefaultLocationResolver(
            context = context,
            queryHighAccuracy = { null },
            queryBalancedPower = { null },
            queryFusedLastLocation = { throw IllegalStateException("Fused service unavailable") },
            queryLocationManager = {
                tier4Called = true
                expectedLocation
            }
        )

        val resolved = resolver.resolveLocation()

        assertTrue(tier4Called)
        assertEquals(expectedLocation, resolved)
    }

    @Test
    fun multiTierResolver_allTiersFail_returnsNullWithoutThrowing() = runBlocking {
        val resolver = DefaultLocationResolver(
            context = context,
            queryHighAccuracy = { throw RuntimeException("GPS failed") },
            queryBalancedPower = { throw RuntimeException("Network failed") },
            queryFusedLastLocation = { throw RuntimeException("Fused failed") },
            queryLocationManager = { throw RuntimeException("LocationManager failed") }
        )

        val resolved = resolver.resolveLocation()

        assertNull("All tiers failing should return null seamlessly without throwing", resolved)
    }

    @Test
    fun multiTierResolver_manualRefresh_passes10SecondsTimeoutForTier1() = runBlocking {
        var passedTimeout: Long? = null
        val resolver = DefaultLocationResolver(
            context = context,
            queryHighAccuracy = { timeout ->
                passedTimeout = timeout
                createMockLocation("gps")
            },
            queryBalancedPower = { null },
            queryFusedLastLocation = { null },
            queryLocationManager = { null }
        )

        resolver.resolveLocation(
            highAccuracyTimeoutMs = LocationResolver.MANUAL_REFRESH_HIGH_ACCURACY_TIMEOUT_MS,
            balancedTimeoutMs = LocationResolver.DEFAULT_BALANCED_TIMEOUT_MS
        )

        assertEquals(10_000L, passedTimeout)
    }

    @Test
    fun multiTierResolver_getLastKnownLocation_checksTier3ThenTier4WithoutQueryingTiers1And2() = runBlocking {
        var tier1Called = false
        var tier2Called = false
        var tier3Called = false
        var tier4Called = false

        val expectedLocation = createMockLocation("passive")
        val resolver = DefaultLocationResolver(
            context = context,
            queryHighAccuracy = {
                tier1Called = true
                null
            },
            queryBalancedPower = {
                tier2Called = true
                null
            },
            queryFusedLastLocation = {
                tier3Called = true
                null // Tier 3 misses
            },
            queryLocationManager = {
                tier4Called = true
                expectedLocation
            }
        )

        val result = resolver.getLastKnownLocation()

        assertFalse("Tier 1 should not be called in getLastKnownLocation", tier1Called)
        assertFalse("Tier 2 should not be called in getLastKnownLocation", tier2Called)
        assertTrue("Tier 3 should be checked in getLastKnownLocation", tier3Called)
        assertTrue("Tier 4 should be checked when Tier 3 misses in getLastKnownLocation", tier4Called)
        assertEquals(expectedLocation, result)
    }

    @Test
    fun worker_whenLocationUnavailable_andActiveStopPresent_fallsBackToStopNameQueryWithWarningBanner() = runBlocking {
        val repository = InMemoryDeparturesRepository()
        val now = Instant.parse("2026-10-23T14:30:00Z")
        SettingsRepository(context).saveApiKey("test-key")

        val andelStop = Stop("U100Z1", "Anděl", platforms = listOf(Platform("U100Z1", "A")))
        WidgetStateManager.setState(
            context,
            ArrivalsViewState.Content(
                stops = listOf(andelStop),
                activeStopIndex = 0,
                activePlatformCode = "A"
            ),
            repository = repository
        )

        val fakeDataSource = FakeTransitDataSource()
        val liveResponse = GolemioDepartureBoardResponse(
            stops = listOf(
                GolemioStopDto(stopId = "U100Z1", stopName = "Anděl", platformCode = "A")
            ),
            departures = listOf(
                GolemioDepartureDto(
                    departureTimestamp = GolemioTimestampDto(
                        scheduled = now.plusSeconds(180).toString(),
                        predicted = now.plusSeconds(180).toString()
                    ),
                    stop = GolemioDepartureStopDto(id = "U100Z1", name = "Anděl", platformCode = "A"),
                    route = GolemioRouteDto(shortName = "9", type = 0),
                    trip = GolemioTripDto(headsign = "Sídliště Řepy")
                )
            )
        )
        fakeDataSource.setResponseForStop("Anděl", Result.success(liveResponse))

        val worker = TestListenableWorkerBuilder<ArrivalsUpdateWorker>(context).build()
        worker.locationResolver = object : LocationResolver {
            override suspend fun resolveLocation(highAccuracyTimeoutMs: Long, balancedTimeoutMs: Long): android.location.Location? = null
            override suspend fun getLastKnownLocation(): android.location.Location? = null
        }
        worker.departuresRepository = repository
        worker.transitDataSource = fakeDataSource
        worker.clock = { now }

        val workerResult = worker.doWork()

        assertEquals(androidx.work.ListenableWorker.Result.success(), workerResult)
        assertEquals(listOf("Anděl"), fakeDataSource.stopsCalls.map { it.stopNames }.flatten())

        val state = WidgetStateManager.getState(context, now, repository = repository)
        assertTrue("State should be Content", state is ArrivalsViewState.Content)
        val content = state as ArrivalsViewState.Content
        assertEquals("Anděl", content.activeStop.name)
        assertEquals(1, content.activeDepartures.size)
        assertEquals("9", content.activeDepartures.first().line.name)
        assertEquals("GPS unavailable • Showing Anděl", content.cachedWarning)
    }

    @Test
    fun worker_whenLocationStaleBeyond15Minutes_andActiveStopPresent_fallsBackToStopNameQueryWithWarningBanner() = runBlocking {
        val repository = InMemoryDeparturesRepository()
        val now = Instant.parse("2026-10-23T14:30:00Z")
        val twentyMinutesAgo = now.minusSeconds(20 * 60)
        repository.saveLocation(Coordinates(lat = 50.0755, lon = 14.4302), observedAt = twentyMinutesAgo)
        SettingsRepository(context).saveApiKey("test-key")

        val namestiMiruStop = Stop("U200Z1", "Náměstí Míru", platforms = listOf(Platform("U200Z1", "A")))
        WidgetStateManager.setState(
            context,
            ArrivalsViewState.Content(
                stops = listOf(namestiMiruStop),
                activeStopIndex = 0,
                activePlatformCode = "A"
            ),
            repository = repository
        )

        val fakeDataSource = FakeTransitDataSource()
        val liveResponse = GolemioDepartureBoardResponse(
            stops = listOf(
                GolemioStopDto(stopId = "U200Z1", stopName = "Náměstí Míru", platformCode = "A")
            ),
            departures = listOf(
                GolemioDepartureDto(
                    departureTimestamp = GolemioTimestampDto(
                        scheduled = now.plusSeconds(120).toString(),
                        predicted = now.plusSeconds(120).toString()
                    ),
                    stop = GolemioDepartureStopDto(id = "U200Z1", name = "Náměstí Míru", platformCode = "A"),
                    route = GolemioRouteDto(shortName = "A", type = 1),
                    trip = GolemioTripDto(headsign = "Depo Hostivař")
                )
            )
        )
        fakeDataSource.setResponseForStop("Náměstí Míru", Result.success(liveResponse))

        val worker = TestListenableWorkerBuilder<ArrivalsUpdateWorker>(context).build()
        worker.locationResolver = object : LocationResolver {
            override suspend fun resolveLocation(highAccuracyTimeoutMs: Long, balancedTimeoutMs: Long): android.location.Location? = null
            override suspend fun getLastKnownLocation(): android.location.Location? = null
        }
        worker.departuresRepository = repository
        worker.transitDataSource = fakeDataSource
        worker.clock = { now }

        val workerResult = worker.doWork()

        assertEquals(androidx.work.ListenableWorker.Result.success(), workerResult)
        assertEquals(listOf("Náměstí Míru"), fakeDataSource.stopsCalls.map { it.stopNames }.flatten())

        val state = WidgetStateManager.getState(context, now, repository = repository)
        assertTrue("State should be Content", state is ArrivalsViewState.Content)
        val content = state as ArrivalsViewState.Content
        assertEquals("Náměstí Míru", content.activeStop.name)
        assertEquals("GPS unavailable • Showing Náměstí Míru", content.cachedWarning)
    }

    @Test
    fun worker_whenActiveStopQueryFails_fallsBackToRestoreCachedDepartures() = runBlocking {
        val repository = InMemoryDeparturesRepository()
        val now = Instant.parse("2026-10-23T14:30:00Z")
        val fetchTime = Instant.parse("2026-10-23T14:28:00Z") // 16:28 in Prague
        SettingsRepository(context).saveApiKey("test-key")

        val cachedResponse = GolemioDepartureBoardResponse(
            stops = listOf(GolemioStopDto(stopId = "U100Z1", stopName = "Anděl", platformCode = "A")),
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

        val andelStop = Stop("U100Z1", "Anděl", platforms = listOf(Platform("U100Z1", "A")))
        WidgetStateManager.setState(
            context,
            ArrivalsViewState.Content(
                stops = listOf(andelStop),
                activeStopIndex = 0,
                activePlatformCode = "A"
            ),
            repository = repository
        )

        val fakeDataSource = FakeTransitDataSource()
        fakeDataSource.setResponseForStop("Anděl", Result.failure(java.io.IOException("Network down")))

        val worker = TestListenableWorkerBuilder<ArrivalsUpdateWorker>(context).build()
        worker.locationResolver = object : LocationResolver {
            override suspend fun resolveLocation(highAccuracyTimeoutMs: Long, balancedTimeoutMs: Long): android.location.Location? = null
            override suspend fun getLastKnownLocation(): android.location.Location? = null
        }
        worker.departuresRepository = repository
        worker.transitDataSource = fakeDataSource
        worker.clock = { now }

        val workerResult = worker.doWork()

        assertEquals(androidx.work.ListenableWorker.Result.success(), workerResult)
        val state = WidgetStateManager.getState(context, now, repository = repository)
        assertTrue("State should be Content with cached departures", state is ArrivalsViewState.Content)
        val content = state as ArrivalsViewState.Content
        assertEquals("Anděl", content.activeStop.name)
        assertEquals("Cached 16:28 • Tap to retry", content.cachedWarning)
    }

    @Test
    fun worker_whenNoLocationAndNoActiveStop_fallsBackToLocationError() = runBlocking {
        val now = Instant.parse("2026-10-23T14:30:00Z")
        val repository = InMemoryDeparturesRepository()
        SettingsRepository(context).saveApiKey("test-key")

        WidgetStateManager.setState(context, ArrivalsViewState.Loading(), repository = repository)

        val fakeDataSource = FakeTransitDataSource()

        val worker = TestListenableWorkerBuilder<ArrivalsUpdateWorker>(context).build()
        worker.locationResolver = object : LocationResolver {
            override suspend fun resolveLocation(highAccuracyTimeoutMs: Long, balancedTimeoutMs: Long): android.location.Location? = null
            override suspend fun getLastKnownLocation(): android.location.Location? = null
        }
        worker.departuresRepository = repository
        worker.transitDataSource = fakeDataSource
        worker.clock = { now }

        val workerResult = worker.doWork()

        assertEquals(androidx.work.ListenableWorker.Result.success(), workerResult)
        val state = WidgetStateManager.getState(context, repository = repository)
        assertTrue("State should be Error when no location and no active stop", state is ArrivalsViewState.Error)
        val error = state as ArrivalsViewState.Error
        assertTrue(error.message.contains("GPS") || error.message.contains("Location permission"))
    }
}

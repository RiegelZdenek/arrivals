package cz.riegel.arrivals.widget

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cz.riegel.arrivals.domain.ArrivalsViewState
import cz.riegel.arrivals.domain.model.Coordinates
import cz.riegel.arrivals.domain.model.CountdownDisplay
import cz.riegel.arrivals.domain.model.Delay
import cz.riegel.arrivals.domain.model.Departure
import cz.riegel.arrivals.domain.model.Line
import cz.riegel.arrivals.domain.model.Platform
import cz.riegel.arrivals.domain.model.Stop
import cz.riegel.arrivals.domain.model.TransportMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ArrivalsWidgetContentTest {

    @Test
    fun widgetState_computesDisplayDeparturesForCompactAndStandard() {
        val departures = (1..8).map { i ->
            Departure(
                id = "dep_$i",
                line = Line("$i", TransportMode.Tram),
                headsign = "Terminus $i",
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
        assertEquals(8, state.activeDepartures.size)

        // Compact (< 180dp) limits to 4 departures
        assertEquals(4, maxDeparturesForHeight(110.dp))
        val compactList = state.visibleDepartures(isCompact = true)
        assertEquals(4, compactList.size)
        assertEquals(listOf("1", "2", "3", "4"), compactList.map { it.line.name })

        // Standard/large (>= 180dp) shows up to 7 departures
        assertEquals(7, maxDeparturesForHeight(180.dp))
        assertEquals(7, maxDeparturesForHeight(250.dp))
        val standardList = state.visibleDepartures(isCompact = false)
        assertEquals(7, standardList.size)
        assertEquals(listOf("1", "2", "3", "4", "5", "6", "7"), standardList.map { it.line.name })
    }

    @Test
    fun headerPlatformPill_visibilityAndDropdownIndicatorRules() {
        // 1. Multiple platforms with physical codes -> shown with dropdown indicator and clickable
        val multiPlatformStop = Stop(
            id = "s1",
            name = "I. P. Pavlova",
            platforms = listOf(
                Platform("p1", "A"),
                Platform("p2", "B")
            )
        )
        val multiState = ArrivalsViewState.Content(
            stops = listOf(multiPlatformStop),
            activeStopIndex = 0,
            activePlatformCode = "A"
        )
        assertTrue(multiState.hasMultiplePlatforms)
        assertTrue(multiState.showHeaderPlatformPill)
        assertEquals("A ▾", multiState.headerPlatformPillText)

        // 2. Multiple platforms with Terminus fallback codes -> shown with dropdown indicator and clickable
        val multiFallbackStop = Stop(
            id = "s2",
            name = "Lipanská",
            platforms = listOf(
                Platform("p1", "→ Bílá Hora"),
                Platform("p2", "→ Spojovací")
            )
        )
        val multiFallbackState = ArrivalsViewState.Content(
            stops = listOf(multiFallbackStop),
            activeStopIndex = 0,
            activePlatformCode = "→ Bílá Hora"
        )
        assertTrue(multiFallbackState.hasMultiplePlatforms)
        assertTrue(multiFallbackState.showHeaderPlatformPill)
        assertEquals("→ Bílá Hora ▾", multiFallbackState.headerPlatformPillText)

        // 3. Single platform with physical code -> shown statically without dropdown indicator
        val singlePhysicalStop = Stop(
            id = "s3",
            name = "Muzeum",
            platforms = listOf(Platform("p1", "A"))
        )
        val singlePhysicalState = ArrivalsViewState.Content(
            stops = listOf(singlePhysicalStop),
            activeStopIndex = 0,
            activePlatformCode = "A"
        )
        assertFalse(singlePhysicalState.hasMultiplePlatforms)
        assertTrue(singlePhysicalState.showHeaderPlatformPill)
        assertEquals("A", singlePhysicalState.headerPlatformPillText)

        // 4. Single platform with Terminus fallback code -> hidden
        val singleFallbackStop = Stop(
            id = "s4",
            name = "Krymská",
            platforms = listOf(Platform("p1", "→ Bílá Hora"))
        )
        val singleFallbackState = ArrivalsViewState.Content(
            stops = listOf(singleFallbackStop),
            activeStopIndex = 0,
            activePlatformCode = "→ Bílá Hora"
        )
        assertFalse(singleFallbackState.hasMultiplePlatforms)
        assertFalse(singleFallbackState.showHeaderPlatformPill)
        assertNull(singleFallbackState.headerPlatformPillText)
    }

    @Test
    fun activeNavigationCoordinates_usesActivePlatformOrFallsBackToStop() {
        val stopWithPlatformCoords = Stop(
            id = "s1",
            name = "I. P. Pavlova",
            lat = 50.0750,
            lon = 14.4300,
            platforms = listOf(
                Platform("p1", "A", lat = 50.0755, lon = 14.4305),
                Platform("p2", "B", lat = 0.0, lon = 0.0)
            )
        )
        val stateOnA = ArrivalsViewState.Content(
            stops = listOf(stopWithPlatformCoords),
            activeStopIndex = 0,
            activePlatformCode = "A"
        )
        assertEquals(Coordinates(50.0755, 14.4305), stateOnA.activeNavigationCoordinates)

        val stateOnB = stateOnA.copy(activePlatformCode = "B")
        assertEquals(Coordinates(50.0750, 14.4300), stateOnB.activeNavigationCoordinates)
    }

    @Test
    fun headerStopName_showsDropdownIndicatorOnlyWhenMultipleStops() {
        val stop1 = Stop("s1", "I. P. Pavlova", platforms = listOf(Platform("p1", "A")))
        val stop2 = Stop("s2", "Muzeum", platforms = listOf(Platform("p2", "B")))

        val singleStopState = ArrivalsViewState.Content(stops = listOf(stop1))
        assertFalse(singleStopState.hasMultipleStops)
        assertEquals("I. P. Pavlova", singleStopState.headerStopNameText)

        val multiStopState = ArrivalsViewState.Content(stops = listOf(stop1, stop2))
        assertTrue(multiStopState.hasMultipleStops)
        assertEquals("I. P. Pavlova ▾", multiStopState.headerStopNameText)
    }

    @Test
    fun headerDimensions_standardizedSizesAndWhitespace() {
        // Unified action buttons (refresh, back, spinner) fixed 28x28 dp box with 8 dp corner radius
        assertEquals(28.dp, HeaderDimens.ACTION_BUTTON_SIZE)
        assertEquals(8.dp, HeaderDimens.ACTION_BUTTON_CORNER_RADIUS)
        assertEquals(14.dp, HeaderDimens.ACTION_BUTTON_ICON_SIZE)
        assertEquals(14.sp, HeaderDimens.ACTION_BUTTON_FONT_SIZE)

        // Platform pill fixed 28 dp height, 8 dp horizontal padding, and 8 dp corner radius
        assertEquals(28.dp, HeaderDimens.PLATFORM_PILL_HEIGHT)
        assertEquals(8.dp, HeaderDimens.PLATFORM_PILL_CORNER_RADIUS)
        assertEquals(8.dp, HeaderDimens.PLATFORM_PILL_HORIZONTAL_PADDING)

        // Stop name clickable area padding (6 dp horizontal, 4 dp vertical)
        assertEquals(6.dp, HeaderDimens.STOP_NAME_HORIZONTAL_PADDING)
        assertEquals(4.dp, HeaderDimens.STOP_NAME_VERTICAL_PADDING)

        // Header item spacing (8 dp between Stop Name, Platform Pill, and Distance Badge)
        assertEquals(8.dp, HeaderDimens.HEADER_SPACING)

        // Distance badge compact ~22 dp height (6 dp horizontal, 2 dp vertical padding, 8 dp corner radius)
        assertEquals(8.dp, HeaderDimens.DISTANCE_BADGE_CORNER_RADIUS)
        assertEquals(6.dp, HeaderDimens.DISTANCE_BADGE_HORIZONTAL_PADDING)
        assertEquals(2.dp, HeaderDimens.DISTANCE_BADGE_VERTICAL_PADDING)
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

    @Test
    fun skeletonRowCountForHeight_compactAndStandardHeights() {
        assertEquals(2, skeletonRowCountForHeight(110.dp))
        assertEquals(2, skeletonRowCountForHeight(179.dp))
        assertEquals(4, skeletonRowCountForHeight(180.dp))
        assertEquals(4, skeletonRowCountForHeight(250.dp))
        assertEquals(4, skeletonRowCountForHeight(340.dp))
    }

    @Test
    fun transportModeSymbols_formatsCorrectlyForPickersIncludingTrain() {
        val departures = listOf(
            Departure(
                id = "d1",
                line = Line("S7", TransportMode.Train),
                headsign = "Beroun",
                scheduledTime = Instant.now(),
                predictedTime = Instant.now(),
                countdown = CountdownDisplay(5, "5 min", false),
                delay = Delay(0, false)
            ),
            Departure(
                id = "d2",
                line = Line("22", TransportMode.Tram),
                headsign = "Bílá Hora",
                scheduledTime = Instant.now(),
                predictedTime = Instant.now(),
                countdown = CountdownDisplay(10, "10 min", false),
                delay = Delay(0, false)
            )
        )
        val platform = Platform("p1", "1", departures = departures)
        val stop = Stop("s1", "Praha hl.n.", platforms = listOf(platform))

        assertEquals(listOf("T", "V"), platform.transportModeSymbols)
        assertEquals(listOf("T", "V"), stop.transportModeSymbols)
        assertEquals("T V", platform.transportModeSymbols.joinToString(" "))
    }
}

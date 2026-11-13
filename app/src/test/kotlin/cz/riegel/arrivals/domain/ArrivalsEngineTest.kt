package cz.riegel.arrivals.domain

import cz.riegel.arrivals.data.model.GolemioDelayDto
import cz.riegel.arrivals.data.model.GolemioDepartureBoardResponse
import cz.riegel.arrivals.data.model.GolemioDepartureDto
import cz.riegel.arrivals.data.model.GolemioDepartureStopDto
import cz.riegel.arrivals.data.model.GolemioRouteDto
import cz.riegel.arrivals.data.model.GolemioStopDto
import cz.riegel.arrivals.data.model.GolemioTimestampDto
import cz.riegel.arrivals.data.model.GolemioTripDto
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ArrivalsEngineTest {

    private val engine = ArrivalsEngine()

    private val userCoords = Coordinates(lat = 50.0755, lon = 14.4302)
    private val now = Instant.parse("2026-10-23T14:30:00Z")

    private fun sampleDeparture(terminus: String) = Departure(
        id = "dep_1",
        line = Line("22", TransportMode.Tram),
        terminus = terminus,
        scheduledTime = Instant.now(),
        predictedTime = Instant.now(),
        countdown = CountdownDisplay(2, "2 min", false),
        delay = Delay(0, true)
    )

    @Test
    fun computeProximityAndOrder_ordersStopsNearestFirstAndPopulatesDistance() {
        val stopFar = Stop(
            id = "stop_far",
            name = "Karlovo náměstí",
            lat = 50.0760,
            lon = 14.4200,
            platforms = listOf(Platform("p1", "A", 50.0760, 14.4200, listOf(sampleDeparture("Spálená"))))
        )
        val stopNear = Stop(
            id = "stop_near",
            name = "I. P. Pavlova",
            lat = 50.0754,
            lon = 14.4301,
            platforms = listOf(
                Platform("p_a", "A", 50.0754, 14.4301, listOf(sampleDeparture("Bílá Hora"))),
                Platform("p_b", "B", 50.0758, 14.4306, listOf(sampleDeparture("Nádraží Hostivař")))
            )
        )

        val processedStops = engine.rankStopsByProximity(
            stops = listOf(stopFar, stopNear),
            userCoords = userCoords
        )

        assertEquals("I. P. Pavlova", processedStops[0].name)
        assertTrue("Distance should be within 30 meters", processedStops[0].distanceMeters!! < 30)
        assertEquals("Karlovo náměstí", processedStops[1].name)
        assertTrue("Distance should be greater than 600m", processedStops[1].distanceMeters!! > 600)
    }

    @Test
    fun selectClosestPlatform_selectsPlatformPhysicallyNearestToUser() {
        val platformA = Platform("p_a", "A", 50.0754, 14.4301, listOf(sampleDeparture("Bílá Hora")))
        val platformB = Platform("p_b", "B", 50.0760, 14.4315, listOf(sampleDeparture("Hostivař")))

        val stop = Stop(
            id = "stop_1",
            name = "I. P. Pavlova",
            lat = 50.0754,
            lon = 14.4301,
            platforms = listOf(platformA, platformB)
        )

        val selectedPlatform = engine.findClosestPlatform(stop, userCoords = Coordinates(50.0754, 14.4301))
        assertEquals("A", selectedPlatform?.code)
    }

    @Test
    fun stateReducer_openStopPickerClosePickerAndSelectStopWithNearestPlatform() {
        val stop1 = Stop(
            id = "s1", name = "Stop 1",
            lat = 50.075, lon = 14.430,
            platforms = listOf(Platform("p1", "A", 50.075, 14.430), Platform("p2", "B", 50.076, 14.431))
        )
        val stop2 = Stop(
            id = "s2", name = "Stop 2",
            lat = 50.080, lon = 14.440,
            platforms = listOf(Platform("p3", "1", 50.080, 14.440), Platform("p4", "2", 50.081, 14.441))
        )

        var state = ArrivalsViewState.Content(
            stops = listOf(stop1, stop2),
            activeStopIndex = 0,
            activePlatformCode = "A",
            userCoordinates = Coordinates(50.080, 14.440)
        )
        assertEquals(PickerMode.Departures, state.pickerMode)

        // Action: OpenStopPicker when multiple stops exist -> transitions to StopPicker
        state = engine.reduce(state, ArrivalsAction.OpenStopPicker) as ArrivalsViewState.Content
        assertEquals(PickerMode.StopPicker, state.pickerMode)

        // Action: ClosePicker -> returns to Departures without changing Stop or Platform
        state = engine.reduce(state, ArrivalsAction.ClosePicker) as ArrivalsViewState.Content
        assertEquals(PickerMode.Departures, state.pickerMode)
        assertEquals(0, state.activeStopIndex)
        assertEquals("A", state.activePlatformCode)

        // Open StopPicker again and SelectStop(1) -> selects Stop 2, closest platform "1", and closes picker
        state = engine.reduce(state, ArrivalsAction.OpenStopPicker) as ArrivalsViewState.Content
        assertEquals(PickerMode.StopPicker, state.pickerMode)

        state = engine.reduce(state, ArrivalsAction.SelectStop(1)) as ArrivalsViewState.Content
        assertEquals(PickerMode.Departures, state.pickerMode)
        assertEquals(1, state.activeStopIndex)
        assertEquals("Stop 2", state.activeStop.name)
        assertEquals("1", state.activePlatformCode)

        // SelectStop when userCoordinates is null falls back to first platform
        val noCoordsState = state.copy(userCoordinates = null, pickerMode = PickerMode.StopPicker)
        val afterFallback = engine.reduce(noCoordsState, ArrivalsAction.SelectStop(0)) as ArrivalsViewState.Content
        assertEquals(PickerMode.Departures, afterFallback.pickerMode)
        assertEquals(0, afterFallback.activeStopIndex)
        assertEquals("A", afterFallback.activePlatformCode)
    }

    @Test
    fun stateReducer_openStopPickerIsNoOpWhenSingleStop() {
        val singleStop = Stop(
            id = "s1",
            name = "I. P. Pavlova",
            platforms = listOf(Platform("p1", "A"))
        )
        val state = ArrivalsViewState.Content(
            stops = listOf(singleStop),
            activeStopIndex = 0,
            activePlatformCode = "A"
        )

        val reduced = engine.reduce(state, ArrivalsAction.OpenStopPicker) as ArrivalsViewState.Content
        assertEquals(PickerMode.Departures, reduced.pickerMode)
    }

    @Test
    fun stopPickerRows_derivesDistinctTransportModesAcrossPlatformsAndWalkingDistance() {
        val depMetro = Departure(
            id = "d1",
            line = Line("C", TransportMode.Metro),
            terminus = "Háje",
            scheduledTime = Instant.now(),
            predictedTime = Instant.now(),
            countdown = CountdownDisplay(1, "1 min", false),
            delay = Delay(0, false)
        )
        val depTram1 = Departure(
            id = "d2",
            line = Line("22", TransportMode.Tram),
            terminus = "Bílá Hora",
            scheduledTime = Instant.now(),
            predictedTime = Instant.now(),
            countdown = CountdownDisplay(2, "2 min", false),
            delay = Delay(0, false)
        )
        val depTram2 = Departure(
            id = "d3",
            line = Line("10", TransportMode.Tram),
            terminus = "Sídliště Řepy",
            scheduledTime = Instant.now(),
            predictedTime = Instant.now(),
            countdown = CountdownDisplay(4, "4 min", false),
            delay = Delay(0, false)
        )
        val depBus = Departure(
            id = "d4",
            line = Line("135", TransportMode.Bus),
            terminus = "Florenc",
            scheduledTime = Instant.now(),
            predictedTime = Instant.now(),
            countdown = CountdownDisplay(5, "5 min", false),
            delay = Delay(0, false)
        )

        val stop1 = Stop(
            id = "s1",
            name = "I. P. Pavlova",
            distanceMeters = 25,
            platforms = listOf(
                Platform("p1", "M1", departures = listOf(depMetro)),
                Platform("p2", "A", departures = listOf(depTram1, depTram2)),
                Platform("p3", "C", departures = listOf(depBus))
            )
        )
        val stop2 = Stop(
            id = "s2",
            name = "Bruselská",
            distanceMeters = 180,
            platforms = listOf(
                Platform("p4", "A", departures = listOf(depTram1))
            )
        )

        val state = ArrivalsViewState.Content(
            stops = listOf(stop1, stop2),
            activeStopIndex = 0,
            activePlatformCode = "M1"
        )

        val rows = state.stopPickerRows
        assertEquals(2, rows.size)

        assertEquals("I. P. Pavlova", rows[0].name)
        assertEquals(listOf(TransportMode.Metro, TransportMode.Tram, TransportMode.Bus), rows[0].transportModes)
        assertEquals(listOf("M", "T", "B"), rows[0].transportModeSymbols)
        assertEquals(25, rows[0].distanceMeters)
        assertTrue("First stop should be marked selected", rows[0].isSelected)

        assertEquals("Bruselská", rows[1].name)
        assertEquals(listOf(TransportMode.Tram), rows[1].transportModes)
        assertEquals(listOf("T"), rows[1].transportModeSymbols)
        assertEquals(180, rows[1].distanceMeters)
        assertTrue("Second stop should not be marked selected", !rows[1].isSelected)
    }

    @Test
    fun stateReducer_openPlatformPickerClosePickerAndSelectPlatform() {
        val stop = Stop(
            id = "s1",
            name = "I. P. Pavlova",
            platforms = listOf(
                Platform("p1", "A"),
                Platform("p2", "B")
            )
        )
        var state = ArrivalsViewState.Content(
            stops = listOf(stop),
            activeStopIndex = 0,
            activePlatformCode = "A"
        )
        assertEquals(PickerMode.Departures, state.pickerMode)

        // OpenPlatformPicker when multiple platforms exist -> transitions to PlatformPicker
        state = engine.reduce(state, ArrivalsAction.OpenPlatformPicker) as ArrivalsViewState.Content
        assertEquals(PickerMode.PlatformPicker, state.pickerMode)

        // ClosePicker from PlatformPicker -> returns to Departures without changing activePlatformCode
        state = engine.reduce(state, ArrivalsAction.ClosePicker) as ArrivalsViewState.Content
        assertEquals(PickerMode.Departures, state.pickerMode)
        assertEquals("A", state.activePlatformCode)

        // OpenPlatformPicker again and SelectPlatform("B") -> updates activePlatformCode and returns to Departures
        state = engine.reduce(state, ArrivalsAction.OpenPlatformPicker) as ArrivalsViewState.Content
        assertEquals(PickerMode.PlatformPicker, state.pickerMode)

        state = engine.reduce(state, ArrivalsAction.SelectPlatform("B")) as ArrivalsViewState.Content
        assertEquals(PickerMode.Departures, state.pickerMode)
        assertEquals("B", state.activePlatformCode)
    }

    @Test
    fun stateReducer_openPlatformPickerIsNoOpWhenSingleOrNoPlatform() {
        val singlePlatformStop = Stop(
            id = "s1",
            name = "Muzeum",
            platforms = listOf(Platform("p1", "A"))
        )
        val state = ArrivalsViewState.Content(
            stops = listOf(singlePlatformStop),
            activeStopIndex = 0,
            activePlatformCode = "A"
        )

        val reduced = engine.reduce(state, ArrivalsAction.OpenPlatformPicker) as ArrivalsViewState.Content
        assertEquals(PickerMode.Departures, reduced.pickerMode)
    }

    @Test
    fun platformPickerRows_derivesTransportModesTerminusSummariesFallbackDeduplicationAndEmptyPlaceholder() {
        val depTramBilaHora = Departure(
            id = "d1",
            line = Line("22", TransportMode.Tram),
            terminus = "Bílá Hora",
            scheduledTime = Instant.now(),
            predictedTime = Instant.now(),
            countdown = CountdownDisplay(2, "2 min", false),
            delay = Delay(0, false)
        )
        val depTramVypich = Departure(
            id = "d2",
            line = Line("25", TransportMode.Tram),
            terminus = "Vypich",
            scheduledTime = Instant.now(),
            predictedTime = Instant.now(),
            countdown = CountdownDisplay(4, "4 min", false),
            delay = Delay(0, false)
        )
        val depBusBilaHoraDuplicate = Departure(
            id = "d3",
            line = Line("135", TransportMode.Bus),
            terminus = "Bílá Hora",
            scheduledTime = Instant.now(),
            predictedTime = Instant.now(),
            countdown = CountdownDisplay(5, "5 min", false),
            delay = Delay(0, false)
        )
        val depFallbackArrowTerminus = Departure(
            id = "d4",
            line = Line("9", TransportMode.Tram),
            terminus = "→ Spojovací",
            scheduledTime = Instant.now(),
            predictedTime = Instant.now(),
            countdown = CountdownDisplay(3, "3 min", false),
            delay = Delay(0, false)
        )
        val depPlainSpojovaci = Departure(
            id = "d5",
            line = Line("5", TransportMode.Tram),
            terminus = "Spojovací",
            scheduledTime = Instant.now(),
            predictedTime = Instant.now(),
            countdown = CountdownDisplay(6, "6 min", false),
            delay = Delay(0, false)
        )

        val stop = Stop(
            id = "s1",
            name = "I. P. Pavlova",
            platforms = listOf(
                Platform(
                    id = "p_a",
                    code = "A",
                    distanceMeters = 12,
                    departures = listOf(depTramBilaHora, depTramVypich, depBusBilaHoraDuplicate)
                ),
                Platform(
                    id = "p_fallback",
                    code = "→ Spojovací",
                    distanceMeters = 35,
                    departures = listOf(depFallbackArrowTerminus, depPlainSpojovaci)
                ),
                Platform(
                    id = "p_empty",
                    code = "C",
                    distanceMeters = 60,
                    departures = emptyList()
                )
            )
        )

        val state = ArrivalsViewState.Content(
            stops = listOf(stop),
            activeStopIndex = 0,
            activePlatformCode = "A"
        )

        val rows = state.platformPickerRows
        assertEquals(3, rows.size)

        // Row 0: Platform A -> Tram & Bus, distinct Terminus names "Bílá Hora, Vypich", distance 12m, selected
        assertEquals("A", rows[0].code)
        assertEquals(listOf(TransportMode.Tram, TransportMode.Bus), rows[0].transportModes)
        assertEquals(listOf("T", "B"), rows[0].transportModeSymbols)
        assertEquals(listOf("Bílá Hora", "Vypich"), rows[0].distinctTerminuses)
        assertEquals("Bílá Hora, Vypich", rows[0].terminusSummary)
        assertEquals("Bílá Hora, Vypich", rows[0].displayTerminusText("Žádné nadcházející odjezdy"))
        assertEquals(12, rows[0].distanceMeters)
        assertTrue("Platform A should be selected", rows[0].isSelected)

        // Row 1: Terminus fallback platform "→ Spojovací" -> avoids duplicate "→" and deduplicates
        assertEquals("→ Spojovací", rows[1].code)
        assertEquals(listOf("T"), rows[1].transportModeSymbols)
        assertEquals(listOf("Spojovací"), rows[1].distinctTerminuses)
        assertEquals("Spojovací", rows[1].terminusSummary)
        assertEquals(35, rows[1].distanceMeters)
        assertTrue("Platform → Spojovací should not be selected", !rows[1].isSelected)

        // Row 2: Platform C with no departures -> null terminusSummary, uses localized placeholder
        assertEquals("C", rows[2].code)
        assertEquals(emptyList<String>(), rows[2].transportModeSymbols)
        assertNull(rows[2].terminusSummary)
        assertEquals("Žádné nadcházející odjezdy", rows[2].displayTerminusText("Žádné nadcházející odjezdy"))
        assertEquals(60, rows[2].distanceMeters)
    }

    @Test
    fun stateReducer_updateDataPreservesActiveStopPlatformAndOpenPicker() {
        val stopNear = Stop(
            id = "I. P. Pavlova",
            name = "I. P. Pavlova",
            lat = 50.0754,
            lon = 14.4301,
            platforms = listOf(
                Platform("p_a", "A", 50.0754, 14.4301),
                Platform("p_b", "B", 50.0758, 14.4306)
            )
        )
        val stopFar = Stop(
            id = "Karlovo náměstí",
            name = "Karlovo náměstí",
            lat = 50.0760,
            lon = 14.4200,
            platforms = listOf(
                Platform("p_1", "1", 50.0760, 14.4200),
                Platform("p_2", "2", 50.0765, 14.4210)
            )
        )
        val rawStops = listOf(
            GolemioStopDto("p_1", "Karlovo náměstí", "1", 50.0760, 14.4200),
            GolemioStopDto("p_2", "Karlovo náměstí", "2", 50.0765, 14.4210),
            GolemioStopDto("p_a", "I. P. Pavlova", "A", 50.0754, 14.4301),
            GolemioStopDto("p_b", "I. P. Pavlova", "B", 50.0758, 14.4306)
        )

        // User manually selected Karlovo náměstí (index 1) and Platform "2" (not the closest), with StopPicker open
        val currentState = ArrivalsViewState.Content(
            stops = listOf(stopNear, stopFar),
            activeStopIndex = 1,
            activePlatformCode = "2",
            userCoordinates = userCoords,
            pickerMode = PickerMode.StopPicker
        )

        val refreshedAfterStopPicker = engine.reduce(
            currentState,
            ArrivalsAction.IngestApiResponse(
                response = GolemioDepartureBoardResponse(stops = rawStops),
                userCoordinates = userCoords,
                referenceTime = now
            )
        ) as ArrivalsViewState.Content

        assertEquals("Karlovo náměstí", refreshedAfterStopPicker.activeStop.name)
        assertEquals(1, refreshedAfterStopPicker.activeStopIndex)
        assertEquals("2", refreshedAfterStopPicker.activePlatformCode)
        assertEquals(PickerMode.StopPicker, refreshedAfterStopPicker.pickerMode)

        // Also preserve PlatformPicker when open
        val platformPickerState = refreshedAfterStopPicker.copy(pickerMode = PickerMode.PlatformPicker)
        val refreshedAfterPlatformPicker = engine.reduce(
            platformPickerState,
            ArrivalsAction.IngestApiResponse(
                response = GolemioDepartureBoardResponse(stops = rawStops),
                userCoordinates = userCoords,
                referenceTime = now
            )
        ) as ArrivalsViewState.Content

        assertEquals("Karlovo náměstí", refreshedAfterPlatformPicker.activeStop.name)
        assertEquals("2", refreshedAfterPlatformPicker.activePlatformCode)
        assertEquals(PickerMode.PlatformPicker, refreshedAfterPlatformPicker.pickerMode)
    }


    @Test
    fun ingestApiResponse_whenDisplacementExceeds50Meters_snapsToClosestStopAndDefaultPlatform() {
        val stopNear = Stop(
            id = "I. P. Pavlova",
            name = "I. P. Pavlova",
            lat = 50.0754,
            lon = 14.4301,
            platforms = listOf(
                Platform("p_a", "A", 50.0754, 14.4301),
                Platform("p_b", "B", 50.0758, 14.4306)
            )
        )
        val stopFar = Stop(
            id = "Karlovo náměstí",
            name = "Karlovo náměstí",
            lat = 50.0760,
            lon = 14.4200,
            platforms = listOf(
                Platform("p_1", "1", 50.0760, 14.4200),
                Platform("p_2", "2", 50.0765, 14.4210)
            )
        )
        val rawStops = listOf(
            GolemioStopDto("p_1", "Karlovo náměstí", "1", 50.0760, 14.4200),
            GolemioStopDto("p_2", "Karlovo náměstí", "2", 50.0765, 14.4210),
            GolemioStopDto("p_a", "I. P. Pavlova", "A", 50.0754, 14.4301),
            GolemioStopDto("p_b", "I. P. Pavlova", "B", 50.0758, 14.4306)
        )

        val previousCoords = Coordinates(50.0760, 14.4200) // Near Karlovo náměstí
        val newCoords = Coordinates(50.0754, 14.4301) // Near I. P. Pavlova (~750m away, > 50m)

        val stateAtKarlovoNamesti = ArrivalsViewState.Content(
            stops = listOf(stopFar, stopNear),
            activeStopIndex = 0,
            activePlatformCode = "2",
            userCoordinates = previousCoords
        )

        val reduced = engine.reduce(
            stateAtKarlovoNamesti,
            ArrivalsAction.IngestApiResponse(
                response = GolemioDepartureBoardResponse(stops = rawStops),
                userCoordinates = newCoords,
                referenceTime = now
            )
        ) as ArrivalsViewState.Content

        assertEquals(0, reduced.activeStopIndex)
        assertEquals("I. P. Pavlova", reduced.activeStop.name)
        assertEquals("A", reduced.activePlatformCode)
    }

    @Test
    fun ingestApiResponse_whenDisplacementWithin50Meters_preservesSelectedStopAndPlatform() {
        val stopNear = Stop(
            id = "I. P. Pavlova",
            name = "I. P. Pavlova",
            lat = 50.0754,
            lon = 14.4301,
            platforms = listOf(
                Platform("p_a", "A", 50.0754, 14.4301),
                Platform("p_b", "B", 50.0758, 14.4306)
            )
        )
        val stopFar = Stop(
            id = "Karlovo náměstí",
            name = "Karlovo náměstí",
            lat = 50.0760,
            lon = 14.4200,
            platforms = listOf(
                Platform("p_1", "1", 50.0760, 14.4200),
                Platform("p_2", "2", 50.0765, 14.4210)
            )
        )
        val rawStops = listOf(
            GolemioStopDto("p_a", "I. P. Pavlova", "A", 50.0754, 14.4301),
            GolemioStopDto("p_b", "I. P. Pavlova", "B", 50.0758, 14.4306),
            GolemioStopDto("p_1", "Karlovo náměstí", "1", 50.0760, 14.4200),
            GolemioStopDto("p_2", "Karlovo náměstí", "2", 50.0765, 14.4210)
        )

        val previousCoords = Coordinates(50.0760, 14.4200)
        // Move ~24 meters (<= 50m)
        val slightlyMovedCoords = Coordinates(50.07622, 14.4200)
        assertTrue(
            "Displacement should be <= 50m",
            engine.calculateDistanceMeters(previousCoords, slightlyMovedCoords) <= 50
        )

        val stateAtKarlovoNamesti = ArrivalsViewState.Content(
            stops = listOf(stopNear, stopFar),
            activeStopIndex = 1,
            activePlatformCode = "2",
            userCoordinates = previousCoords
        )

        val reduced = engine.reduce(
            stateAtKarlovoNamesti,
            ArrivalsAction.IngestApiResponse(
                response = GolemioDepartureBoardResponse(stops = rawStops),
                userCoordinates = slightlyMovedCoords,
                referenceTime = now
            )
        ) as ArrivalsViewState.Content

        assertEquals("Karlovo náměstí", reduced.activeStop.name)
        assertEquals(0, reduced.activeStopIndex) // Karlovo náměstí is index 0 for coords at 50.07622
        assertEquals("2", reduced.activePlatformCode)
    }

    @Test
    fun ingestApiResponse_whenDisplacementExceeds50MetersAtSameStop_resetsPlatformToDefault() {
        val stop = Stop(
            id = "I. P. Pavlova",
            name = "I. P. Pavlova",
            lat = 50.0754,
            lon = 14.4301,
            platforms = listOf(
                Platform("p_a", "A", 50.0754, 14.4301),
                Platform("p_b", "B", 50.0758, 14.4306)
            )
        )
        val rawStops = listOf(
            GolemioStopDto("p_a", "I. P. Pavlova", "A", 50.0754, 14.4301),
            GolemioStopDto("p_b", "I. P. Pavlova", "B", 50.0758, 14.4306)
        )

        val previousCoords = Coordinates(50.0758, 14.4306) // Near Platform B
        // Move ~116 meters south towards and past Platform A (> 50m displacement, closer to Platform A)
        val movedCoords = Coordinates(50.0748, 14.4301)
        assertTrue(
            "Displacement should be > 50m",
            engine.calculateDistanceMeters(previousCoords, movedCoords) > 50
        )

        // User manually selected platform B previously
        val stateWithPlatformB = ArrivalsViewState.Content(
            stops = listOf(stop),
            activeStopIndex = 0,
            activePlatformCode = "B",
            userCoordinates = previousCoords
        )

        val reduced = engine.reduce(
            stateWithPlatformB,
            ArrivalsAction.IngestApiResponse(
                response = GolemioDepartureBoardResponse(stops = rawStops),
                userCoordinates = movedCoords,
                referenceTime = now
            )
        ) as ArrivalsViewState.Content

        assertEquals(0, reduced.activeStopIndex)
        // Since user moved > 50m, platform resets to default closest platform (Platform A)
        assertEquals("A", reduced.activePlatformCode)
    }

    @Test
    fun ingestApiResponse_whenPreviousUserCoordinatesNull_snapsToClosestStopAndDefaultPlatform() {
        val stopNear = Stop(
            id = "I. P. Pavlova",
            name = "I. P. Pavlova",
            lat = 50.0754,
            lon = 14.4301,
            platforms = listOf(
                Platform("p_a", "A", 50.0754, 14.4301),
                Platform("p_b", "B", 50.0758, 14.4306)
            )
        )
        val stopFar = Stop(
            id = "Karlovo náměstí",
            name = "Karlovo náměstí",
            lat = 50.0760,
            lon = 14.4200,
            platforms = listOf(
                Platform("p_1", "1", 50.0760, 14.4200),
                Platform("p_2", "2", 50.0765, 14.4210)
            )
        )
        val rawStops = listOf(
            GolemioStopDto("p_1", "Karlovo náměstí", "1", 50.0760, 14.4200),
            GolemioStopDto("p_2", "Karlovo náměstí", "2", 50.0765, 14.4210),
            GolemioStopDto("p_a", "I. P. Pavlova", "A", 50.0754, 14.4301),
            GolemioStopDto("p_b", "I. P. Pavlova", "B", 50.0758, 14.4306)
        )

        // Previous state has null userCoordinates, activeStopIndex = 1 ("Karlovo náměstí")
        val stateWithoutCoords = ArrivalsViewState.Content(
            stops = listOf(stopNear, stopFar),
            activeStopIndex = 1,
            activePlatformCode = "2",
            userCoordinates = null
        )

        val firstFixCoords = Coordinates(50.0754, 14.4301) // At I. P. Pavlova

        val reduced = engine.reduce(
            stateWithoutCoords,
            ArrivalsAction.IngestApiResponse(
                response = GolemioDepartureBoardResponse(stops = rawStops),
                userCoordinates = firstFixCoords,
                referenceTime = now
            )
        ) as ArrivalsViewState.Content

        assertEquals(0, reduced.activeStopIndex)
        assertEquals("I. P. Pavlova", reduced.activeStop.name)
        assertEquals("A", reduced.activePlatformCode)
    }


    @Test
    fun stateReducer_updateDataFallsBackWhenSelectedPlatformOrStopOrPickerPreconditionDisappears() {
        val stopNear = Stop(
            id = "I. P. Pavlova",
            name = "I. P. Pavlova",
            lat = 50.0754,
            lon = 14.4301,
            platforms = listOf(
                Platform("p_a", "A", 50.0754, 14.4301),
                Platform("p_b", "B", 50.0758, 14.4306)
            )
        )
        val stopFarSinglePlatform = Stop(
            id = "Karlovo náměstí",
            name = "Karlovo náměstí",
            lat = 50.0760,
            lon = 14.4200,
            platforms = listOf(
                Platform("p_1", "1", 50.0760, 14.4200)
            )
        )

        // 1. Selected Platform "2" disappears from Karlovo náměstí, and only 1 platform remains while PlatformPicker is open
        val stateWithMissingPlatform = ArrivalsViewState.Content(
            stops = listOf(
                stopNear,
                stopFarSinglePlatform.copy(
                    platforms = listOf(
                        Platform("p_1", "1", 50.0760, 14.4200),
                        Platform("p_2", "2", 50.0765, 14.4210)
                    )
                )
            ),
            activeStopIndex = 1,
            activePlatformCode = "2",
            userCoordinates = userCoords,
            pickerMode = PickerMode.PlatformPicker
        )

        val afterPlatformDisappears = engine.reduce(
            stateWithMissingPlatform,
            ArrivalsAction.IngestApiResponse(
                response = GolemioDepartureBoardResponse(
                    stops = listOf(
                        GolemioStopDto("p_a", "I. P. Pavlova", "A", 50.0754, 14.4301),
                        GolemioStopDto("p_b", "I. P. Pavlova", "B", 50.0758, 14.4306),
                        GolemioStopDto("p_1", "Karlovo náměstí", "1", 50.0760, 14.4200)
                    )
                ),
                userCoordinates = userCoords,
                referenceTime = now
            )
        ) as ArrivalsViewState.Content

        // Stop is still Karlovo náměstí, platform falls back to closest ("1"), PlatformPicker falls back to Departures
        assertEquals("Karlovo náměstí", afterPlatformDisappears.activeStop.name)
        assertEquals("1", afterPlatformDisappears.activePlatformCode)
        assertEquals(PickerMode.Departures, afterPlatformDisappears.pickerMode)

        // 2. Selected Stop ("Karlovo náměstí") disappears completely while StopPicker is open and only 1 stop remains
        val stateWithStopPicker = afterPlatformDisappears.copy(pickerMode = PickerMode.StopPicker)
        val afterStopDisappears = engine.reduce(
            stateWithStopPicker,
            ArrivalsAction.IngestApiResponse(
                response = GolemioDepartureBoardResponse(
                    stops = listOf(
                        GolemioStopDto("p_a", "I. P. Pavlova", "A", 50.0754, 14.4301),
                        GolemioStopDto("p_b", "I. P. Pavlova", "B", 50.0758, 14.4306)
                    )
                ),
                userCoordinates = userCoords,
                referenceTime = now
            )
        ) as ArrivalsViewState.Content

        assertEquals(0, afterStopDisappears.activeStopIndex)
        assertEquals("I. P. Pavlova", afterStopDisappears.activeStop.name)
        assertEquals("A", afterStopDisappears.activePlatformCode)
        assertEquals(PickerMode.Departures, afterStopDisappears.pickerMode)
    }

    @Test
    fun ingestApiResponse_mapsStopsPlatformsAndDeparturesCorrectly() {
        val response = GolemioDepartureBoardResponse(
            stops = listOf(
                GolemioStopDto("U100Z1P", "I. P. Pavlova", "A", 50.0755, 14.4302),
                GolemioStopDto("U100Z2P", "I. P. Pavlova", "B", 50.0756, 14.4303)
            ),
            departures = listOf(
                GolemioDepartureDto(
                    departureTimestamp = GolemioTimestampDto("2026-10-23T16:33:00+02:00", "2026-10-23T16:35:00+02:00"),
                    delay = GolemioDelayDto(isAvailable = true, minutes = 2),
                    route = GolemioRouteDto(shortName = "22", type = 0, isNight = false),
                    stop = GolemioDepartureStopDto(id = "U100Z1P", platformCode = "A", name = "I. P. Pavlova"),
                    trip = GolemioTripDto(headsign = "Bílá Hora")
                )
            )
        )

        val state = engine.reduce(
            ArrivalsViewState.Loading(),
            ArrivalsAction.IngestApiResponse(response, userCoords, now)
        ) as ArrivalsViewState.Content

        assertEquals(1, state.stops.size)
        val stop = state.stops[0]
        assertEquals("I. P. Pavlova", stop.name)
        assertEquals(2, stop.platforms.size)

        val platformA = stop.platforms.first { it.code == "A" }
        assertEquals(1, platformA.departures.size)

        val dep = platformA.departures[0]
        assertEquals("22", dep.line.name)
        assertEquals(TransportMode.Tram, dep.line.transportMode)
        assertEquals("Bílá Hora", dep.terminus)
        assertEquals(5, dep.countdown.minutesRemaining)
        assertEquals("5 min", dep.countdown.displayText)
        assertFalse(dep.countdown.isClockTime)
        assertEquals(2, dep.delay.minutes)
        assertTrue(dep.delay.isDelayed)
    }

    @Test
    fun ingestApiResponse_handlesLessThanOneMinuteCountdown() {
        val response = GolemioDepartureBoardResponse(
            stops = listOf(GolemioStopDto("U100Z1P", "Muzeum", "A", 50.07, 14.43)),
            departures = listOf(
                GolemioDepartureDto(
                    departureTimestamp = GolemioTimestampDto("2026-10-23T16:30:20+02:00", "2026-10-23T16:30:20+02:00"),
                    delay = GolemioDelayDto(isAvailable = true, minutes = 0),
                    route = GolemioRouteDto(shortName = "C", type = 1),
                    stop = GolemioDepartureStopDto(id = "U100Z1P", platformCode = "A"),
                    trip = GolemioTripDto(headsign = "Háje")
                )
            )
        )

        val state = engine.reduce(
            ArrivalsViewState.Loading(),
            ArrivalsAction.IngestApiResponse(response, userCoords, now)
        ) as ArrivalsViewState.Content

        val dep = state.stops[0].platforms[0].departures[0]
        assertEquals("<1 min", dep.countdown.displayText)
        assertFalse(dep.countdown.isClockTime)
        assertEquals(TransportMode.Metro, dep.line.transportMode)
    }

    @Test
    fun ingestApiResponse_handlesFarFutureDeparture_formatsAsClockTime() {
        val response = GolemioDepartureBoardResponse(
            stops = listOf(GolemioStopDto("U100Z1P", "Muzeum", "A", 50.07, 14.43)),
            departures = listOf(
                GolemioDepartureDto(
                    departureTimestamp = GolemioTimestampDto("2026-10-23T17:15:00+02:00", "2026-10-23T17:15:00+02:00"),
                    route = GolemioRouteDto(shortName = "S9", type = 2),
                    stop = GolemioDepartureStopDto(id = "U100Z1P", platformCode = "A"),
                    trip = GolemioTripDto(headsign = "Benešov u Prahy")
                )
            )
        )

        val state = engine.reduce(
            ArrivalsViewState.Loading(),
            ArrivalsAction.IngestApiResponse(response, userCoords, now)
        ) as ArrivalsViewState.Content

        val dep = state.stops[0].platforms[0].departures[0]
        assertTrue(dep.countdown.isClockTime)
        assertEquals("17:15", dep.countdown.displayText)
        assertEquals(TransportMode.Train, dep.line.transportMode)
    }

    @Test
    fun ingestApiResponse_resolvesTransportModesAndLetterSymbolsCorrectly() {
        val response = GolemioDepartureBoardResponse(
            stops = listOf(GolemioStopDto("S1", "Test Stop", "1", 50.07, 14.43)),
            departures = listOf(
                GolemioDepartureDto(
                    departureTimestamp = GolemioTimestampDto(scheduled = "2026-10-23T16:35:00+02:00"),
                    route = GolemioRouteDto(shortName = "22", type = 0),
                    stop = GolemioDepartureStopDto(id = "S1", platformCode = "1"),
                    trip = GolemioTripDto(headsign = "Tram")
                ),
                GolemioDepartureDto(
                    departureTimestamp = GolemioTimestampDto(scheduled = "2026-10-23T16:36:00+02:00"),
                    route = GolemioRouteDto(shortName = "A", type = 1),
                    stop = GolemioDepartureStopDto(id = "S1", platformCode = "1"),
                    trip = GolemioTripDto(headsign = "Metro")
                ),
                GolemioDepartureDto(
                    departureTimestamp = GolemioTimestampDto(scheduled = "2026-10-23T16:37:00+02:00"),
                    route = GolemioRouteDto(shortName = "S1", type = 2),
                    stop = GolemioDepartureStopDto(id = "S1", platformCode = "1"),
                    trip = GolemioTripDto(headsign = "Train")
                ),
                GolemioDepartureDto(
                    departureTimestamp = GolemioTimestampDto(scheduled = "2026-10-23T16:38:00+02:00"),
                    route = GolemioRouteDto(shortName = "135", type = 3),
                    stop = GolemioDepartureStopDto(id = "S1", platformCode = "1"),
                    trip = GolemioTripDto(headsign = "Bus")
                ),
                GolemioDepartureDto(
                    departureTimestamp = GolemioTimestampDto(scheduled = "2026-10-23T16:39:00+02:00"),
                    route = GolemioRouteDto(shortName = "58", type = 11),
                    stop = GolemioDepartureStopDto(id = "S1", platformCode = "1"),
                    trip = GolemioTripDto(headsign = "Trolleybus")
                )
            )
        )

        val state = engine.reduce(
            ArrivalsViewState.Loading(),
            ArrivalsAction.IngestApiResponse(response, userCoords, now)
        ) as ArrivalsViewState.Content

        val departures = state.stops[0].platforms[0].departures
        assertEquals(5, departures.size)
        assertEquals(TransportMode.Tram, departures[0].line.transportMode)
        assertEquals("T", departures[0].line.transportMode.symbol)
        assertEquals(TransportMode.Metro, departures[1].line.transportMode)
        assertEquals("M", departures[1].line.transportMode.symbol)
        assertEquals(TransportMode.Train, departures[2].line.transportMode)
        assertEquals("V", departures[2].line.transportMode.symbol)
        assertEquals(TransportMode.Bus, departures[3].line.transportMode)
        assertEquals("B", departures[3].line.transportMode.symbol)
        assertEquals(TransportMode.Trolleybus, departures[4].line.transportMode)
        assertEquals("R", departures[4].line.transportMode.symbol)
    }

    @Test
    fun ingestApiResponse_resolvesStopNameFromStopsArrayWhenDepartureStopNameIsAbsent() {
        val response = GolemioDepartureBoardResponse(
            stops = listOf(GolemioStopDto("U1072Z101P", "Chodov", "M1", 50.0312, 14.4908)),
            departures = listOf(
                GolemioDepartureDto(
                    departureTimestamp = GolemioTimestampDto("2026-10-23T16:34:00+02:00", "2026-10-23T16:34:00+02:00", "4"),
                    route = GolemioRouteDto(shortName = "C", type = 1),
                    stop = GolemioDepartureStopDto(id = "U1072Z101P", platformCode = "M1", name = ""),
                    trip = GolemioTripDto(headsign = "Letňany")
                )
            )
        )

        val state = engine.reduce(
            ArrivalsViewState.Loading(),
            ArrivalsAction.IngestApiResponse(response, userCoords, now)
        ) as ArrivalsViewState.Content

        assertEquals(1, state.stops.size)
        assertEquals("Chodov", state.stops[0].name)
        assertEquals(1, state.stops[0].platforms.size)
        assertEquals(1, state.stops[0].platforms[0].departures.size)
        assertEquals("Letňany", state.stops[0].platforms[0].departures[0].terminus)
    }

    @Test
    fun ingestApiResponse_handlesEmptyDeparturesWithStopsList() {
        val response = GolemioDepartureBoardResponse(
            stops = listOf(GolemioStopDto("U100Z1P", "Muzeum", "A", 50.07, 14.43)),
            departures = emptyList()
        )

        val state = engine.reduce(
            ArrivalsViewState.Loading(),
            ArrivalsAction.IngestApiResponse(response, userCoords, now)
        ) as ArrivalsViewState.Content

        assertEquals(1, state.stops.size)
        assertEquals("Muzeum", state.stops[0].name)
        assertTrue(state.stops[0].platforms[0].departures.isEmpty())
    }

    @Test
    fun ingestApiResponse_retainsExistingStopsWithWarningWhenEmptyResponseArrivesDuringRefresh() {
        val existingContent = engine.reduce(
            ArrivalsViewState.Loading(),
            ArrivalsAction.IngestApiResponse(
                response = GolemioDepartureBoardResponse(
                    stops = listOf(GolemioStopDto("U100Z1P", "Muzeum", "A", 50.07, 14.43))
                ),
                userCoordinates = userCoords,
                referenceTime = now
            )
        ) as ArrivalsViewState.Content

        val afterEmptyRefresh = engine.reduce(
            existingContent.copy(isRefreshing = true),
            ArrivalsAction.IngestApiResponse(
                response = GolemioDepartureBoardResponse(stops = emptyList(), departures = emptyList()),
                userCoordinates = userCoords,
                referenceTime = now
            )
        ) as ArrivalsViewState.Content

        assertEquals(1, afterEmptyRefresh.stops.size)
        assertEquals("Muzeum", afterEmptyRefresh.stops[0].name)
        assertFalse(afterEmptyRefresh.isRefreshing)
        assertNotNull(afterEmptyRefresh.cachedWarning)
    }

    @Test
    fun tickCountdown_decrementsMinutesAndRemovesDepartedVehiclesWithoutRefetching() {
        val response = GolemioDepartureBoardResponse(
            stops = listOf(GolemioStopDto("U100Z1P", "Muzeum", "A", 50.07, 14.43)),
            departures = listOf(
                GolemioDepartureDto(
                    departureTimestamp = GolemioTimestampDto("2026-10-23T16:30:10+02:00", "2026-10-23T16:30:10+02:00"),
                    route = GolemioRouteDto(shortName = "22", type = 0),
                    stop = GolemioDepartureStopDto(id = "U100Z1P", platformCode = "A"),
                    trip = GolemioTripDto(headsign = "Soon")
                ),
                GolemioDepartureDto(
                    departureTimestamp = GolemioTimestampDto("2026-10-23T16:33:00+02:00", "2026-10-23T16:33:00+02:00"),
                    route = GolemioRouteDto(shortName = "14", type = 0),
                    stop = GolemioDepartureStopDto(id = "U100Z1P", platformCode = "A"),
                    trip = GolemioTripDto(headsign = "Later")
                )
            )
        )

        val initial = engine.reduce(
            ArrivalsViewState.Loading(),
            ArrivalsAction.IngestApiResponse(response, userCoords, now)
        ) as ArrivalsViewState.Content
        assertEquals(2, initial.stops[0].platforms[0].departures.size)

        val ticked = engine.reduce(
            initial,
            ArrivalsAction.TickCountdown(now.plusSeconds(60))
        ) as ArrivalsViewState.Content

        val remaining = ticked.stops[0].platforms[0].departures
        assertEquals(1, remaining.size)
        assertEquals("Later", remaining[0].terminus)
        assertEquals(2, remaining[0].countdown.minutesRemaining)
        assertEquals("2 min", remaining[0].countdown.displayText)
    }

    @Test
    fun ingestApiResponse_whenSearchRadiusProvidedAndNoStopsInResponse_transitionsToEmptyWithRadiusMessage() {
        val previousState = ArrivalsViewState.Content(
            stops = listOf(
                Stop("Lipanska", "Lipanská", platforms = listOf(Platform("p1", "A")))
            ),
            activeStopIndex = 0,
            activePlatformCode = "A"
        )

        val emptyResponse = GolemioDepartureBoardResponse(stops = emptyList(), departures = emptyList())
        val searchRadiusMeters = 300

        val nextState = engine.reduce(
            previousState,
            ArrivalsAction.IngestApiResponse(
                response = emptyResponse,
                userCoordinates = Coordinates(50.0520, 14.2950),
                referenceTime = now,
                searchRadius = searchRadiusMeters
            )
        )

        assertTrue("State must transition to Empty when no stops found within search radius", nextState is ArrivalsViewState.Empty)
        val empty = nextState as ArrivalsViewState.Empty
        assertEquals("No transit stops found within 300m", empty.message)
        assertEquals(300, empty.searchRadiusMeters)
    }

    @Test
    fun ingestApiResponse_whenSearchRadiusProvidedAndAllStopsFartherThanSearchRadius_transitionsToEmptyWithRadiusMessage() {
        val previousState = ArrivalsViewState.Content(
            stops = listOf(
                Stop("Lipanska", "Lipanská", platforms = listOf(Platform("p1", "A")))
            ),
            activeStopIndex = 0,
            activePlatformCode = "A"
        )

        // User at (50.0520, 14.2950). Response has Lipanská at (50.0839, 14.4534), ~11km away.
        val responseWithDistantStop = GolemioDepartureBoardResponse(
            stops = listOf(
                GolemioStopDto(stopId = "Lipanska", stopName = "Lipanská", platformCode = "A", stopLat = 50.0839, stopLon = 14.4534)
            )
        )
        val searchRadiusMeters = 300

        val nextState = engine.reduce(
            previousState,
            ArrivalsAction.IngestApiResponse(
                response = responseWithDistantStop,
                userCoordinates = Coordinates(50.0520, 14.2950),
                referenceTime = now,
                searchRadius = searchRadiusMeters
            )
        )

        assertTrue("State must transition to Empty when all stops exceed search radius", nextState is ArrivalsViewState.Empty)
        val empty = nextState as ArrivalsViewState.Empty
        assertEquals("No transit stops found within 300m", empty.message)
    }

    @Test
    fun ingestApiResponse_whenSearchRadiusProvidedAndStopsFoundWithinRadius_transitionsToContent() {
        val userAtLipanska = Coordinates(50.0839, 14.4534)
        val responseWithNearbyStop = GolemioDepartureBoardResponse(
            stops = listOf(
                GolemioStopDto(stopId = "Lipanska", stopName = "Lipanská", platformCode = "A", stopLat = 50.0839, stopLon = 14.4534)
            )
        )
        val searchRadiusMeters = 300

        val nextState = engine.reduce(
            ArrivalsViewState.Loading(),
            ArrivalsAction.IngestApiResponse(
                response = responseWithNearbyStop,
                userCoordinates = userAtLipanska,
                referenceTime = now,
                searchRadius = searchRadiusMeters
            )
        )

        assertTrue("State must transition to Content when stop is within search radius", nextState is ArrivalsViewState.Content)
        val content = nextState as ArrivalsViewState.Content
        assertEquals("Lipanská", content.activeStop.name)
    }
}

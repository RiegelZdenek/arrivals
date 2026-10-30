package cz.riegel.arrivals.domain

import cz.riegel.arrivals.domain.model.Coordinates
import cz.riegel.arrivals.domain.model.CountdownDisplay
import cz.riegel.arrivals.domain.model.Delay
import cz.riegel.arrivals.domain.model.Departure
import cz.riegel.arrivals.domain.model.Line
import cz.riegel.arrivals.domain.model.Platform
import cz.riegel.arrivals.domain.model.Stop
import cz.riegel.arrivals.domain.model.TransportMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class ArrivalsEngineTest {

    private val engine = ArrivalsEngine()

    private val userCoords = Coordinates(lat = 50.0755, lon = 14.4302)

    private fun sampleDeparture(headsign: String) = Departure(
        id = "dep_1",
        line = Line("22", TransportMode.Tram),
        headsign = headsign,
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
            headsign = "Háje",
            scheduledTime = Instant.now(),
            predictedTime = Instant.now(),
            countdown = CountdownDisplay(1, "1 min", false),
            delay = Delay(0, false)
        )
        val depTram1 = Departure(
            id = "d2",
            line = Line("22", TransportMode.Tram),
            headsign = "Bílá Hora",
            scheduledTime = Instant.now(),
            predictedTime = Instant.now(),
            countdown = CountdownDisplay(2, "2 min", false),
            delay = Delay(0, false)
        )
        val depTram2 = Departure(
            id = "d3",
            line = Line("10", TransportMode.Tram),
            headsign = "Sídliště Řepy",
            scheduledTime = Instant.now(),
            predictedTime = Instant.now(),
            countdown = CountdownDisplay(4, "4 min", false),
            delay = Delay(0, false)
        )
        val depBus = Departure(
            id = "d4",
            line = Line("135", TransportMode.Bus),
            headsign = "Florenc",
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
            headsign = "Bílá Hora",
            scheduledTime = Instant.now(),
            predictedTime = Instant.now(),
            countdown = CountdownDisplay(2, "2 min", false),
            delay = Delay(0, false)
        )
        val depTramVypich = Departure(
            id = "d2",
            line = Line("25", TransportMode.Tram),
            headsign = "Vypich",
            scheduledTime = Instant.now(),
            predictedTime = Instant.now(),
            countdown = CountdownDisplay(4, "4 min", false),
            delay = Delay(0, false)
        )
        val depBusBilaHoraDuplicate = Departure(
            id = "d3",
            line = Line("135", TransportMode.Bus),
            headsign = "Bílá Hora",
            scheduledTime = Instant.now(),
            predictedTime = Instant.now(),
            countdown = CountdownDisplay(5, "5 min", false),
            delay = Delay(0, false)
        )
        val depFallbackArrowTerminus = Departure(
            id = "d4",
            line = Line("9", TransportMode.Tram),
            headsign = "→ Spojovací",
            scheduledTime = Instant.now(),
            predictedTime = Instant.now(),
            countdown = CountdownDisplay(3, "3 min", false),
            delay = Delay(0, false)
        )
        val depPlainSpojovaci = Departure(
            id = "d5",
            line = Line("5", TransportMode.Tram),
            headsign = "Spojovací",
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
            id = "s_near",
            name = "I. P. Pavlova",
            lat = 50.0754,
            lon = 14.4301,
            platforms = listOf(
                Platform("p_a", "A", 50.0754, 14.4301),
                Platform("p_b", "B", 50.0758, 14.4306)
            )
        )
        val stopFar = Stop(
            id = "s_far",
            name = "Karlovo náměstí",
            lat = 50.0760,
            lon = 14.4200,
            platforms = listOf(
                Platform("p_1", "1", 50.0760, 14.4200),
                Platform("p_2", "2", 50.0765, 14.4210)
            )
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
            ArrivalsAction.UpdateData(
                stops = listOf(stopFar, stopNear),
                userCoordinates = userCoords
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
            ArrivalsAction.UpdateData(
                stops = listOf(stopNear, stopFar),
                userCoordinates = userCoords
            )
        ) as ArrivalsViewState.Content

        assertEquals("Karlovo náměstí", refreshedAfterPlatformPicker.activeStop.name)
        assertEquals("2", refreshedAfterPlatformPicker.activePlatformCode)
        assertEquals(PickerMode.PlatformPicker, refreshedAfterPlatformPicker.pickerMode)
    }

    @Test
    fun stateReducer_updateDataFallsBackWhenSelectedPlatformOrStopOrPickerPreconditionDisappears() {
        val stopNear = Stop(
            id = "s_near",
            name = "I. P. Pavlova",
            lat = 50.0754,
            lon = 14.4301,
            platforms = listOf(
                Platform("p_a", "A", 50.0754, 14.4301),
                Platform("p_b", "B", 50.0758, 14.4306)
            )
        )
        val stopFarSinglePlatform = Stop(
            id = "s_far",
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
            ArrivalsAction.UpdateData(
                stops = listOf(stopNear, stopFarSinglePlatform),
                userCoordinates = userCoords
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
            ArrivalsAction.UpdateData(
                stops = listOf(stopNear),
                userCoordinates = userCoords
            )
        ) as ArrivalsViewState.Content

        assertEquals(0, afterStopDisappears.activeStopIndex)
        assertEquals("I. P. Pavlova", afterStopDisappears.activeStop.name)
        assertEquals("A", afterStopDisappears.activePlatformCode)
        assertEquals(PickerMode.Departures, afterStopDisappears.pickerMode)
    }

    private fun assertTrue(message: String, condition: Boolean) {
        org.junit.Assert.assertTrue(message, condition)
    }
}

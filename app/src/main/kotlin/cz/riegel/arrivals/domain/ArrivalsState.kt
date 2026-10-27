package cz.riegel.arrivals.domain

import cz.riegel.arrivals.domain.model.Coordinates
import cz.riegel.arrivals.domain.model.Departure
import cz.riegel.arrivals.domain.model.Platform
import cz.riegel.arrivals.domain.model.Stop

sealed interface ArrivalsViewState {
    data class Loading(val message: String = "Locating nearest stop…") : ArrivalsViewState

    data class SetupRequired(
        val title: String = "Setup Required",
        val message: String = "Tap to configure Golemio API key"
    ) : ArrivalsViewState

    data class Empty(
        val message: String = "No transit stops found nearby"
    ) : ArrivalsViewState

    data class Error(
        val message: String,
        val canRetry: Boolean = true
    ) : ArrivalsViewState

    data class Content(
        val stops: List<Stop>,
        val activeStopIndex: Int = 0,
        val activePlatformCode: String = "",
        val userCoordinates: Coordinates? = null,
        val cachedWarning: String? = null,
        val isRefreshing: Boolean = false
    ) : ArrivalsViewState {
        val activeStop: Stop
            get() = stops.getOrElse(activeStopIndex) { stops.first() }

        val activePlatform: Platform?
            get() = activeStop.platforms.find { it.code == activePlatformCode }
                ?: activeStop.platforms.firstOrNull()

        val activeDepartures: List<Departure>
            get() = activePlatform?.departures.orEmpty()

        val hasMultipleStops: Boolean
            get() = stops.size > 1
    }
}

sealed interface ArrivalsAction {
    data object NextStop : ArrivalsAction
    data object PreviousStop : ArrivalsAction
    data class SelectPlatform(val code: String) : ArrivalsAction
    data object Refresh : ArrivalsAction
    data class SetRefreshing(val isRefreshing: Boolean) : ArrivalsAction
    data class UpdateData(
        val stops: List<Stop>,
        val userCoordinates: Coordinates? = null,
        val cachedWarning: String? = null
    ) : ArrivalsAction
}

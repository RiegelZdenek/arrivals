package cz.riegel.arrivals.domain

import cz.riegel.arrivals.data.model.GolemioDepartureBoardResponse
import cz.riegel.arrivals.domain.model.Coordinates
import cz.riegel.arrivals.domain.model.Departure
import cz.riegel.arrivals.domain.model.Platform
import cz.riegel.arrivals.domain.model.Stop
import cz.riegel.arrivals.domain.model.TransportMode
import java.time.Instant

enum class PickerMode {
    Departures,
    StopPicker,
    PlatformPicker
}

data class StopPickerRow(
    val stopIndex: Int,
    val stopId: String,
    val name: String,
    val transportModes: List<TransportMode>,
    val transportModeSymbols: List<String>,
    val distanceMeters: Int?,
    val isSelected: Boolean
)

data class PlatformPickerRow(
    val platformId: String,
    val code: String,
    val transportModes: List<TransportMode>,
    val transportModeSymbols: List<String>,
    val distinctTerminuses: List<String>,
    val terminusSummary: String?,
    val distanceMeters: Int?,
    val isSelected: Boolean
) {
    fun displayTerminusText(noDeparturesPlaceholder: String = "No upcoming departures"): String =
        terminusSummary ?: noDeparturesPlaceholder
}

sealed interface ArrivalsViewState {
    data class Loading(val message: String = "Locating nearest stop…") : ArrivalsViewState

    data class SetupRequired(
        val title: String = "Setup Required",
        val message: String = "Tap to configure Golemio API key"
    ) : ArrivalsViewState

    data class Empty(
        val message: String = "No transit stops found nearby",
        val searchRadiusMeters: Int? = null
    ) : ArrivalsViewState {
        companion object {
            fun forRadius(radiusMeters: Int): Empty =
                Empty(message = "No transit stops found within ${radiusMeters}m", searchRadiusMeters = radiusMeters)
        }
    }

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
        val isRefreshing: Boolean = false,
        val isLoadingDepartures: Boolean = false,
        val pickerMode: PickerMode = PickerMode.Departures
    ) : ArrivalsViewState {
        val activeStop: Stop
            get() = stops.getOrElse(activeStopIndex) { stops.first() }

        val activePlatform: Platform?
            get() = activeStop.platforms.find { it.code == activePlatformCode }
                ?: activeStop.platforms.firstOrNull()

        val activeDistanceMeters: Int?
            get() = activePlatform?.distanceMeters ?: activeStop.distanceMeters

        val activeDepartures: List<Departure>
            get() = activePlatform?.departures.orEmpty()

        val hasMultipleStops: Boolean
            get() = stops.size > 1

        val headerStopNameText: String
            get() = if (hasMultipleStops) "${activeStop.name} ▾" else activeStop.name

        val hasMultiplePlatforms: Boolean
            get() = activeStop.platforms.size > 1

        val showHeaderPlatformPill: Boolean
            get() {
                val code = activePlatform?.code.orEmpty()
                if (code.isBlank()) return false
                return hasMultiplePlatforms || !code.startsWith("→")
            }

        val headerPlatformPillText: String?
            get() {
                if (!showHeaderPlatformPill) return null
                val code = activePlatform?.code ?: return null
                return if (hasMultiplePlatforms) "$code ▾" else code
            }

        val activeNavigationCoordinates: Coordinates
            get() {
                val platform = activePlatform
                val lat = platform?.lat?.takeIf { it != 0.0 } ?: activeStop.lat
                val lon = platform?.lon?.takeIf { it != 0.0 } ?: activeStop.lon
                return Coordinates(lat, lon)
            }

        val stopPickerRows: List<StopPickerRow>
            get() = stops.mapIndexed { index, stop ->
                StopPickerRow(
                    stopIndex = index,
                    stopId = stop.id,
                    name = stop.name,
                    transportModes = stop.distinctTransportModes,
                    transportModeSymbols = stop.transportModeSymbols,
                    distanceMeters = stop.distanceMeters,
                    isSelected = index == activeStopIndex
                )
            }

        val platformPickerRows: List<PlatformPickerRow>
            get() {
                val currentCode = activePlatform?.code ?: activePlatformCode
                return activeStop.platforms.map { platform ->
                    PlatformPickerRow(
                        platformId = platform.id,
                        code = platform.code,
                        transportModes = platform.distinctTransportModes,
                        transportModeSymbols = platform.transportModeSymbols,
                        distinctTerminuses = platform.distinctTerminuses,
                        terminusSummary = platform.terminusSummary,
                        distanceMeters = platform.distanceMeters,
                        isSelected = platform.code == currentCode
                    )
                }
            }

        fun visibleDepartures(isCompact: Boolean): List<Departure> =
            activeDepartures.take(if (isCompact) COMPACT_MAX_DEPARTURES else STANDARD_MAX_DEPARTURES)

        companion object {
            const val COMPACT_MAX_DEPARTURES = 4
            const val STANDARD_MAX_DEPARTURES = 7
            const val COMPACT_SKELETON_COUNT = 2
            const val STANDARD_SKELETON_COUNT = 4
        }
    }
}

sealed interface ArrivalsAction {
    data object OpenStopPicker : ArrivalsAction
    data object OpenPlatformPicker : ArrivalsAction
    data object ClosePicker : ArrivalsAction
    data class SelectStop(val stopIndex: Int) : ArrivalsAction
    data class SelectPlatform(val code: String) : ArrivalsAction
    data object Refresh : ArrivalsAction
    data class SetRefreshing(val isRefreshing: Boolean) : ArrivalsAction
    data class IngestApiResponse(
        val response: GolemioDepartureBoardResponse,
        val userCoordinates: Coordinates? = null,
        val referenceTime: Instant = Instant.now(),
        val cachedWarning: String? = null,
        val searchRadius: Int? = null
    ) : ArrivalsAction
    data class UpdateActiveStopDepartures(
        val response: GolemioDepartureBoardResponse,
        val referenceTime: Instant = Instant.now(),
        val userCoordinates: Coordinates? = null,
        val warningBanner: String? = null
    ) : ArrivalsAction
    data class TickCountdown(
        val referenceTime: Instant = Instant.now()
    ) : ArrivalsAction
}

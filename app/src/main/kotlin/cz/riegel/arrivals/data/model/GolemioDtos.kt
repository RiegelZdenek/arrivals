package cz.riegel.arrivals.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class GolemioDepartureBoardResponse(
    val stops: List<GolemioStopDto> = emptyList(),
    val departures: List<GolemioDepartureDto> = emptyList()
)

@Serializable
data class GolemioStopDto(
    @SerialName("stop_id") val stopId: String = "",
    @SerialName("stop_name") val stopName: String = "",
    @SerialName("platform_code") val platformCode: String? = null,
    @SerialName("stop_lat") val stopLat: Double = 0.0,
    @SerialName("stop_lon") val stopLon: Double = 0.0,
    @SerialName("zone_id") val zoneId: String? = null
)

@Serializable
data class GolemioDepartureDto(
    @SerialName("departure_timestamp") val departureTimestamp: GolemioTimestampDto = GolemioTimestampDto(),
    val stop: GolemioDepartureStopDto = GolemioDepartureStopDto(),
    val route: GolemioRouteDto = GolemioRouteDto(),
    val trip: GolemioTripDto = GolemioTripDto(),
    val delay: GolemioDelayDto = GolemioDelayDto()
)

@Serializable
data class GolemioTimestampDto(
    val scheduled: String = "",
    val predicted: String? = null,
    val minutes: Int? = null
)

@Serializable
data class GolemioDepartureStopDto(
    val id: String = "",
    val name: String = "",
    @SerialName("platform_code") val platformCode: String? = null
)

@Serializable
data class GolemioRouteDto(
    @SerialName("short_name") val shortName: String = "",
    val type: Int = 0,
    @SerialName("is_night") val isNight: Boolean? = null
)

@Serializable
data class GolemioTripDto(
    val headsign: String = "",
    @SerialName("is_canceled") val isCanceled: Boolean? = null,
    @SerialName("is_wheelchair_accessible") val isWheelchairAccessible: Boolean? = null
)

@Serializable
data class GolemioDelayDto(
    @SerialName("is_available") val isAvailable: Boolean = false,
    val minutes: Int? = null,
    val seconds: Int? = null
)

sealed class GolemioException(message: String) : Exception(message) {
    object MissingApiKey : GolemioException("Golemio API key is missing. Please configure it in settings.")
    object Unauthorized : GolemioException("Invalid Golemio API token (HTTP 401).")
    object RateLimited : GolemioException("Golemio rate limit reached (HTTP 429).")
    data class NetworkError(val causeThrowable: Throwable) : GolemioException("Network connection error: ${causeThrowable.message}")
    data class HttpError(val code: Int, val body: String) : GolemioException("HTTP Error $code: $body")
}

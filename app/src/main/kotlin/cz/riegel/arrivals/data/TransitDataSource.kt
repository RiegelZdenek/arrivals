package cz.riegel.arrivals.data

import android.content.Context
import cz.riegel.arrivals.data.model.GolemioDepartureBoardResponse
import cz.riegel.arrivals.domain.model.Coordinates

interface TransitDataSource {
    suspend fun fetchNearbyDepartures(
        coordinates: Coordinates,
        radiusMeters: Int,
        limit: Int = 60
    ): Result<GolemioDepartureBoardResponse>

    suspend fun fetchDeparturesForStops(
        stopNames: List<String>,
        limit: Int = 60
    ): Result<GolemioDepartureBoardResponse>

    companion object {
        fun create(
            context: Context,
            apiKeyProvider: () -> String?
        ): TransitDataSource = GolemioApiClient.create(context, apiKeyProvider)
    }
}

class FakeTransitDataSource(
    private var nearbyHandler: (suspend (Coordinates, Int, Int) -> Result<GolemioDepartureBoardResponse>)? = null,
    private var stopsHandler: (suspend (List<String>, Int) -> Result<GolemioDepartureBoardResponse>)? = null
) : TransitDataSource {

    data class NearbyCall(val coordinates: Coordinates, val radiusMeters: Int, val limit: Int)
    data class StopsCall(val stopNames: List<String>, val limit: Int)

    private val _nearbyCalls = mutableListOf<NearbyCall>()
    val nearbyCalls: List<NearbyCall> get() = _nearbyCalls.toList()

    private val _stopsCalls = mutableListOf<StopsCall>()
    val stopsCalls: List<StopsCall> get() = _stopsCalls.toList()

    private val responsesByStopName = mutableMapOf<String, Result<GolemioDepartureBoardResponse>>()
    private val responsesByRadius = mutableMapOf<Int, Result<GolemioDepartureBoardResponse>>()
    var defaultResult: Result<GolemioDepartureBoardResponse> = Result.success(GolemioDepartureBoardResponse())

    fun setResponseForStop(stopName: String, result: Result<GolemioDepartureBoardResponse>) {
        responsesByStopName[stopName] = result
    }

    fun setResponseForRadius(radiusMeters: Int, result: Result<GolemioDepartureBoardResponse>) {
        responsesByRadius[radiusMeters] = result
    }

    override suspend fun fetchNearbyDepartures(
        coordinates: Coordinates,
        radiusMeters: Int,
        limit: Int
    ): Result<GolemioDepartureBoardResponse> {
        _nearbyCalls.add(NearbyCall(coordinates, radiusMeters, limit))
        nearbyHandler?.let { return it(coordinates, radiusMeters, limit) }
        responsesByRadius[radiusMeters]?.let { return it }
        return defaultResult
    }

    override suspend fun fetchDeparturesForStops(
        stopNames: List<String>,
        limit: Int
    ): Result<GolemioDepartureBoardResponse> {
        _stopsCalls.add(StopsCall(stopNames, limit))
        stopsHandler?.let { return it(stopNames, limit) }
        for (name in stopNames) {
            responsesByStopName[name]?.let { return it }
        }
        return defaultResult
    }
}

typealias InMemoryTransitDataSource = FakeTransitDataSource

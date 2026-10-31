package cz.riegel.arrivals.data

import android.content.Context
import cz.riegel.arrivals.data.model.GolemioDepartureBoardResponse
import cz.riegel.arrivals.data.model.GolemioException
import cz.riegel.arrivals.domain.model.Coordinates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

class GolemioApiClient(
    private val baseUrl: String = "https://api.golemio.cz/v2/pid/departureboards",
    private val apiKeyProvider: () -> String?,
    private val okHttpClient: OkHttpClient = defaultOkHttpClient(),
    private val stopsStreamProvider: (() -> InputStream)? = null
) : TransitDataSource {

    private val stopRegistry = StopRegistry(stopsStreamProvider)

    fun findClosestStopDistance(coordinates: Coordinates): Pair<String, Int>? {
        return stopRegistry.findClosestStop(coordinates.lat, coordinates.lon)?.let { (stop, dist) ->
            stop.n to dist
        }
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    override suspend fun fetchDeparturesForStops(
        stopNames: List<String>,
        limit: Int
    ): Result<GolemioDepartureBoardResponse> = withContext(Dispatchers.IO) {
        val apiKey = apiKeyProvider()
        if (apiKey.isNullOrBlank()) {
            return@withContext Result.failure(GolemioException.MissingApiKey)
        }

        if (stopNames.isEmpty()) {
            return@withContext Result.success(GolemioDepartureBoardResponse())
        }

        val baseHttpUrl = baseUrl.toHttpUrlOrNull()
            ?: return@withContext Result.failure(IllegalArgumentException("Invalid base URL: $baseUrl"))

        val urlBuilder = baseHttpUrl.newBuilder()
            .addQueryParameter("limit", limit.toString())

        stopNames.forEach { name ->
            urlBuilder.addQueryParameter("names", name)
        }

        val request = Request.Builder()
            .url(urlBuilder.build())
            .addHeader("X-Access-Token", apiKey)
            .addHeader("Accept", "application/json")
            .build()

        executeRequest(request)
    }

    override suspend fun fetchNearbyDepartures(
        coordinates: Coordinates,
        radiusMeters: Int,
        limit: Int
    ): Result<GolemioDepartureBoardResponse> = withContext(Dispatchers.IO) {
        val apiKey = apiKeyProvider()
        if (apiKey.isNullOrBlank()) {
            return@withContext Result.failure(GolemioException.MissingApiKey)
        }

        val nearbyStopNames = stopRegistry.findNearbyStopNames(
            userLat = coordinates.lat,
            userLon = coordinates.lon,
            radiusMeters = radiusMeters
        )
        if (nearbyStopNames.isEmpty()) {
            val baseHttpUrl = baseUrl.toHttpUrlOrNull()
                ?: return@withContext Result.failure(IllegalArgumentException("Invalid base URL: $baseUrl"))

            val request = Request.Builder()
                .url(
                    baseHttpUrl.newBuilder()
                        .addQueryParameter("lat", coordinates.lat.toString())
                        .addQueryParameter("lng", coordinates.lon.toString())
                        .addQueryParameter("range", radiusMeters.toString())
                        .addQueryParameter("limit", limit.toString())
                        .build()
                )
                .addHeader("X-Access-Token", apiKey)
                .addHeader("Accept", "application/json")
                .build()

            return@withContext executeRequest(request)
        }

        fetchDeparturesForStops(nearbyStopNames, limit)
    }

    private fun executeRequest(request: Request): Result<GolemioDepartureBoardResponse> {
        return try {
            val response = okHttpClient.newCall(request).execute()
            response.use { res ->
                val code = res.code
                val responseBody = res.body?.string().orEmpty()

                when (code) {
                    200 -> {
                        val parsed = json.decodeFromString<GolemioDepartureBoardResponse>(responseBody)
                        Result.success(parsed)
                    }
                    401, 403 -> Result.failure(GolemioException.Unauthorized)
                    429 -> Result.failure(GolemioException.RateLimited)
                    else -> Result.failure(GolemioException.HttpError(code, responseBody))
                }
            }
        } catch (e: IOException) {
            Result.failure(GolemioException.NetworkError(e))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    companion object {
        fun create(
            context: Context,
            apiKeyProvider: () -> String?,
            baseUrl: String = "https://api.golemio.cz/v2/pid/departureboards",
            okHttpClient: OkHttpClient = defaultOkHttpClient()
        ): GolemioApiClient {
            val appContext = context.applicationContext ?: context
            return GolemioApiClient(
                baseUrl = baseUrl,
                apiKeyProvider = apiKeyProvider,
                okHttpClient = okHttpClient,
                stopsStreamProvider = { appContext.assets.open("stops_compact.json") }
            )
        }

        fun isWithinPragueCoverage(coordinates: Coordinates): Boolean {
            return StopRegistry.isWithinPragueCoverage(coordinates.lat, coordinates.lon)
        }

        fun defaultOkHttpClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()
        }
    }
}

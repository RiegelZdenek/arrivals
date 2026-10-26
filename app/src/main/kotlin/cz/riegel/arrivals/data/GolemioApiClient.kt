package cz.riegel.arrivals.data

import cz.riegel.arrivals.data.model.GolemioDepartureBoardResponse
import cz.riegel.arrivals.data.model.GolemioException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

class GolemioApiClient(
    private val baseUrl: String = "https://api.golemio.cz/v2/pid/departureboards",
    private val apiKeyProvider: () -> String?,
    private val okHttpClient: OkHttpClient = defaultOkHttpClient(),
    private val stopRegistry: StopRegistry = StopRegistry()
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    suspend fun getDeparturesByNames(
        names: List<String>,
        limit: Int = 20
    ): Result<GolemioDepartureBoardResponse> = withContext(Dispatchers.IO) {
        val apiKey = apiKeyProvider()
        if (apiKey.isNullOrBlank()) {
            return@withContext Result.failure(GolemioException.MissingApiKey)
        }

        if (names.isEmpty()) {
            return@withContext Result.success(GolemioDepartureBoardResponse())
        }

        val baseHttpUrl = baseUrl.toHttpUrlOrNull()
            ?: return@withContext Result.failure(IllegalArgumentException("Invalid base URL: $baseUrl"))

        val urlBuilder = baseHttpUrl.newBuilder()
            .addQueryParameter("limit", limit.toString())

        names.forEach { name ->
            urlBuilder.addQueryParameter("names", name)
        }

        val request = Request.Builder()
            .url(urlBuilder.build())
            .addHeader("X-Access-Token", apiKey)
            .addHeader("Accept", "application/json")
            .build()

        executeRequest(request)
    }

    suspend fun getDepartures(
        lat: Double,
        lon: Double,
        radiusMeters: Int = 300,
        limit: Int = 20
    ): Result<GolemioDepartureBoardResponse> = withContext(Dispatchers.IO) {
        val apiKey = apiKeyProvider()
        if (apiKey.isNullOrBlank()) {
            return@withContext Result.failure(GolemioException.MissingApiKey)
        }

        val nearbyStopNames = stopRegistry.findNearbyStopNames(lat, lon, radiusMeters)
        if (nearbyStopNames.isEmpty()) {
            // Also attempt direct lat/lng parameters if supported
            val baseHttpUrl = baseUrl.toHttpUrlOrNull()
                ?: return@withContext Result.failure(IllegalArgumentException("Invalid base URL: $baseUrl"))

            val request = Request.Builder()
                .url(
                    baseHttpUrl.newBuilder()
                        .addQueryParameter("lat", lat.toString())
                        .addQueryParameter("lng", lon.toString())
                        .addQueryParameter("range", radiusMeters.toString())
                        .addQueryParameter("limit", limit.toString())
                        .build()
                )
                .addHeader("X-Access-Token", apiKey)
                .addHeader("Accept", "application/json")
                .build()

            return@withContext executeRequest(request)
        }

        getDeparturesByNames(nearbyStopNames, limit)
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
        fun defaultOkHttpClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()
        }
    }
}

package cz.riegel.arrivals.widget

import android.annotation.SuppressLint
import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.android.gms.location.LocationServices
import cz.riegel.arrivals.data.CacheResilience
import cz.riegel.arrivals.data.DeparturesCacheRepository
import cz.riegel.arrivals.data.GolemioApiClient
import cz.riegel.arrivals.data.LocationFallbackStrategy
import cz.riegel.arrivals.data.SettingsRepository
import cz.riegel.arrivals.data.UserCoordinates
import cz.riegel.arrivals.domain.ArrivalsAction
import cz.riegel.arrivals.domain.ArrivalsEngine
import cz.riegel.arrivals.domain.ArrivalsViewState
import cz.riegel.arrivals.domain.DepartureNormalizer
import kotlinx.coroutines.tasks.await
import java.time.Instant
import java.util.concurrent.TimeUnit

class ArrivalsUpdateWorker(
    private val appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    private val settingsRepository = SettingsRepository(appContext)
    private val cacheRepository = DeparturesCacheRepository(appContext)
    private val normalizer = DepartureNormalizer()
    private val engine = ArrivalsEngine()
    private val locationStrategy = LocationFallbackStrategy(maxStaleDurationMinutes = 30)

    override suspend fun doWork(): Result {
        val apiKey = settingsRepository.getApiKey()
        if (apiKey.isNullOrBlank()) {
            WidgetStateManager.setState(appContext, ArrivalsViewState.SetupRequired())
            WidgetStateManager.updateAllWidgets(appContext)
            return Result.success()
        }

        val apiClient = GolemioApiClient(apiKeyProvider = { apiKey })
        val now = Instant.now()

        // 1. Resolve coordinates (GPS or 30-min underground fallback)
        val freshLocation = getFreshLocation()
        val cachedCoords = cacheRepository.getLastCoordinates()
        val resolvedCoords = locationStrategy.resolveCoordinates(
            freshLocation = freshLocation,
            cachedLocation = cachedCoords,
            currentTime = now
        )

        if (resolvedCoords == null) {
            handleLocationFailure(now)
            return Result.success()
        }

        // Persist fresh fix
        if (!resolvedCoords.isStaleUndergroundFallback) {
            cacheRepository.saveLastCoordinates(resolvedCoords)
        }

        // 2. Fetch live departures with adaptive radius expansion
        val preferredRadius = settingsRepository.getSearchRadius()
        val searchRadii = listOf(preferredRadius) + engine.adaptiveSearchRadii.filter { it != preferredRadius }

        var apiSuccessResponse: cz.riegel.arrivals.data.model.GolemioDepartureBoardResponse? = null
        var lastError: Throwable? = null

        for (radius in searchRadii) {
            val responseResult = apiClient.getDepartures(
                lat = resolvedCoords.lat,
                lon = resolvedCoords.lon,
                radiusMeters = radius,
                limit = 20
            )

            if (responseResult.isSuccess) {
                val data = responseResult.getOrThrow()
                if (data.departures.isNotEmpty() || data.stops.isNotEmpty()) {
                    apiSuccessResponse = data
                    break
                } else if (apiSuccessResponse == null) {
                    apiSuccessResponse = data
                }
            } else {
                lastError = responseResult.exceptionOrNull()
                break // Don't loop radius on network / auth errors
            }
        }

        if (apiSuccessResponse != null) {
            cacheRepository.saveLastResponse(apiSuccessResponse, now)
            val stops = normalizer.normalize(apiSuccessResponse, referenceTime = now)

            val cachedBanner = if (resolvedCoords.isStaleUndergroundFallback) {
                "Underground GPS (30m fix)"
            } else null

            val updatedState = engine.reduce(
                WidgetStateManager.getState(appContext),
                ArrivalsAction.UpdateData(
                    stops = stops,
                    userLat = resolvedCoords.lat,
                    userLon = resolvedCoords.lon,
                    cachedWarning = cachedBanner
                )
            )

            WidgetStateManager.setState(appContext, updatedState)
            WidgetStateManager.updateAllWidgets(appContext)
            return Result.success()
        }

        // 3. Network or Rate Limit failure resilience: fall back to cached departures
        handleNetworkFailure(now, lastError)
        return Result.success()
    }

    private suspend fun handleNetworkFailure(now: Instant, error: Throwable?) {
        val cached = cacheRepository.getLastResponse()
        if (cached != null) {
            val (cachedResponse, cachedTime) = cached
            val stops = normalizer.normalize(cachedResponse, referenceTime = now)
            val warningBanner = CacheResilience.formatCachedWarning(cachedTime)

            val lastCoords = cacheRepository.getLastCoordinates()
            val lat = lastCoords?.lat ?: 50.08
            val lon = lastCoords?.lon ?: 14.43

            val updatedState = engine.reduce(
                WidgetStateManager.getState(appContext),
                ArrivalsAction.UpdateData(
                    stops = stops,
                    userLat = lat,
                    userLon = lon,
                    cachedWarning = warningBanner
                )
            )
            WidgetStateManager.setState(appContext, updatedState)
            WidgetStateManager.updateAllWidgets(appContext)
        } else {
            val msg = error?.message ?: "Unable to fetch transit departures"
            WidgetStateManager.setState(appContext, ArrivalsViewState.Error(msg, canRetry = true))
            WidgetStateManager.updateAllWidgets(appContext)
        }
    }

    private suspend fun handleLocationFailure(now: Instant) {
        val cached = cacheRepository.getLastResponse()
        if (cached != null) {
            val (cachedResponse, cachedTime) = cached
            val stops = normalizer.normalize(cachedResponse, referenceTime = now)
            val warningBanner = CacheResilience.formatCachedWarning(cachedTime)

            val updatedState = engine.reduce(
                WidgetStateManager.getState(appContext),
                ArrivalsAction.UpdateData(
                    stops = stops,
                    userLat = 50.08,
                    userLon = 14.43,
                    cachedWarning = warningBanner
                )
            )
            WidgetStateManager.setState(appContext, updatedState)
            WidgetStateManager.updateAllWidgets(appContext)
        } else {
            WidgetStateManager.setState(
                appContext,
                ArrivalsViewState.Error("No location available. Turn on GPS or connect to network.", canRetry = true)
            )
            WidgetStateManager.updateAllWidgets(appContext)
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun getFreshLocation(): UserCoordinates? {
        return try {
            val fusedClient = LocationServices.getFusedLocationProviderClient(appContext)
            val androidLocation = fusedClient.lastLocation.await()
            if (androidLocation != null) {
                UserCoordinates(
                    lat = androidLocation.latitude,
                    lon = androidLocation.longitude,
                    timestamp = Instant.ofEpochMilli(androidLocation.time)
                )
            } else null
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        private const val WORK_NAME_PERIODIC = "arrivals_periodic_update"
        private const val WORK_NAME_ONE_TIME = "arrivals_one_time_update"

        fun enqueueOneTimeWork(context: Context) {
            val request = OneTimeWorkRequestBuilder<ArrivalsUpdateWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME_ONE_TIME,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }

        fun schedulePeriodicWork(context: Context) {
            val request = PeriodicWorkRequestBuilder<ArrivalsUpdateWorker>(15, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME_PERIODIC,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}

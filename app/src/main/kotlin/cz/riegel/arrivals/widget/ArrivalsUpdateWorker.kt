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
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import cz.riegel.arrivals.data.CacheResilience
import cz.riegel.arrivals.data.DeparturesCacheRepository
import cz.riegel.arrivals.data.GolemioApiClient
import cz.riegel.arrivals.data.LocationFallbackStrategy
import cz.riegel.arrivals.data.SettingsRepository
import cz.riegel.arrivals.data.StopRegistry
import cz.riegel.arrivals.data.UserCoordinates
import cz.riegel.arrivals.domain.ArrivalsAction
import cz.riegel.arrivals.domain.ArrivalsEngine
import cz.riegel.arrivals.domain.ArrivalsViewState
import cz.riegel.arrivals.domain.DepartureNormalizer
import cz.riegel.arrivals.domain.model.Coordinates
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
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

        // Show loading/refreshing visual indication immediately
        val currentState = WidgetStateManager.getState(appContext)
        if (currentState is ArrivalsViewState.Content && !currentState.isRefreshing) {
            WidgetStateManager.setState(appContext, currentState.copy(isRefreshing = true))
            WidgetStateManager.updateAllWidgets(appContext)
        }

        val apiClient = GolemioApiClient(
            apiKeyProvider = { apiKey },
            stopRegistry = StopRegistry(appContext)
        )
        val now = Instant.now()

        // 1. Resolve coordinates (High-Accuracy GPS or 30-min underground fallback per ADR-0003)
        val freshLocation = getFreshLocation()
        val cachedCoords = cacheRepository.getLastCoordinates()
        val resolvedCoords = locationStrategy.resolveCoordinates(
            freshLocation = freshLocation,
            cachedLocation = cachedCoords,
            currentTime = now
        )

        if (resolvedCoords == null) {
            handleNoValidLocation(now)
            return Result.success()
        }

        // Persist fresh fix
        if (!resolvedCoords.isStaleUndergroundFallback) {
            cacheRepository.saveLastCoordinates(resolvedCoords)
        }

        val userCoords = Coordinates(lat = resolvedCoords.lat, lon = resolvedCoords.lon)

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
                break
            }
        }

        if (apiSuccessResponse != null) {
            cacheRepository.saveLastResponse(apiSuccessResponse, now)
            val stops = normalizer.normalize(apiSuccessResponse, referenceTime = now)

            val warningBanner = if (resolvedCoords.isStaleUndergroundFallback) {
                "Underground GPS fallback • Last known location"
            } else null

            val updatedState = engine.reduce(
                WidgetStateManager.getState(appContext),
                ArrivalsAction.UpdateData(
                    stops = stops,
                    userCoordinates = userCoords,
                    cachedWarning = warningBanner
                )
            )

            val finalState = if (updatedState is ArrivalsViewState.Content) {
                updatedState.copy(isRefreshing = false)
            } else {
                updatedState
            }

            WidgetStateManager.setState(appContext, finalState)
            WidgetStateManager.updateAllWidgets(appContext)
            return Result.success()
        }

        // 3. Network or Rate Limit failure resilience: fall back to cached departures
        restoreCachedDepartures(now = now, userCoords = userCoords, fallbackErrorMessage = lastError?.message)
        return Result.success()
    }

    private suspend fun handleNoValidLocation(now: Instant) {
        val hasFine = appContext.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val hasCoarse = appContext.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val errorMsg = if (!hasFine && !hasCoarse) {
            "Location permission required. Tap to open Settings."
        } else {
            "No GPS fix available. Ensure location is enabled or select a test stop in Settings."
        }
        restoreCachedDepartures(
            now = now,
            userCoords = null,
            fallbackErrorMessage = errorMsg
        )
    }

    private suspend fun restoreCachedDepartures(
        now: Instant,
        userCoords: Coordinates?,
        fallbackErrorMessage: String?
    ) {
        val cached = cacheRepository.getLastResponse()
        if (cached != null) {
            val (cachedResponse, cachedTime) = cached
            val stops = normalizer.normalize(cachedResponse, referenceTime = now)
            val warningBanner = CacheResilience.formatCachedWarning(cachedTime)

            val updatedState = engine.reduce(
                WidgetStateManager.getState(appContext),
                ArrivalsAction.UpdateData(
                    stops = stops,
                    userCoordinates = userCoords,
                    cachedWarning = warningBanner
                )
            )
            val finalState = if (updatedState is ArrivalsViewState.Content) {
                updatedState.copy(isRefreshing = false)
            } else {
                updatedState
            }
            WidgetStateManager.setState(appContext, finalState)
            WidgetStateManager.updateAllWidgets(appContext)
        } else {
            val msg = fallbackErrorMessage ?: "Unable to fetch transit departures"
            WidgetStateManager.setState(appContext, ArrivalsViewState.Error(msg, canRetry = true))
            WidgetStateManager.updateAllWidgets(appContext)
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun getFreshLocation(): UserCoordinates? {
        val simulatedHub = settingsRepository.getSelectedSimulationHub()
        if (simulatedHub != null) {
            return UserCoordinates(
                lat = simulatedHub.lat,
                lon = simulatedHub.lon,
                timestamp = Instant.now(),
                accuracyMeters = 1.0f
            )
        }

        val hasFine = appContext.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val hasCoarse = appContext.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!hasFine && !hasCoarse) {
            return null
        }

        return try {
            val fusedClient = LocationServices.getFusedLocationProviderClient(appContext)

            // 1. Actively query high-accuracy GPS fix with 8s timeout
            val cts = CancellationTokenSource()
            val freshLocation = withTimeoutOrNull(8_000) {
                fusedClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token).await()
            }

            if (freshLocation != null) {
                return UserCoordinates(
                    lat = freshLocation.latitude,
                    lon = freshLocation.longitude,
                    timestamp = Instant.ofEpochMilli(freshLocation.time),
                    accuracyMeters = freshLocation.accuracy
                )
            }

            // 2. Fallback to lastLocation if fresh query timed out
            val lastLocation = fusedClient.lastLocation.await()
            if (lastLocation != null) {
                UserCoordinates(
                    lat = lastLocation.latitude,
                    lon = lastLocation.longitude,
                    timestamp = Instant.ofEpochMilli(lastLocation.time),
                    accuracyMeters = lastLocation.accuracy
                )
            } else {
                null
            }
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

        fun schedulePeriodicWork(context: Context, intervalMinutes: Long = 15) {
            val request = PeriodicWorkRequestBuilder<ArrivalsUpdateWorker>(intervalMinutes, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME_PERIODIC,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }
    }
}

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
import cz.riegel.arrivals.domain.model.Coordinates
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

        // 1. Resolve coordinates (GPS or 30-min underground fallback per ADR-0003)
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

            val updatedState = engine.reduce(
                WidgetStateManager.getState(appContext),
                ArrivalsAction.UpdateData(
                    stops = stops,
                    userCoordinates = userCoords,
                    cachedWarning = null
                )
            )

            WidgetStateManager.setState(appContext, updatedState)
            WidgetStateManager.updateAllWidgets(appContext)
            return Result.success()
        }

        // 3. Network or Rate Limit failure resilience: fall back to cached departures
        restoreCachedDepartures(now = now, userCoords = userCoords, fallbackErrorMessage = lastError?.message)
        return Result.success()
    }

    private suspend fun handleNoValidLocation(now: Instant) {
        // GPS failed and no cached location under 30 minutes old
        restoreCachedDepartures(
            now = now,
            userCoords = null,
            fallbackErrorMessage = "No GPS fix available. Ensure location permissions are enabled."
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
            WidgetStateManager.setState(appContext, updatedState)
            WidgetStateManager.updateAllWidgets(appContext)
        } else {
            val msg = fallbackErrorMessage ?: "Unable to fetch transit departures"
            WidgetStateManager.setState(appContext, ArrivalsViewState.Error(msg, canRetry = true))
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

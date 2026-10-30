package cz.riegel.arrivals.widget

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
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
            handleNoValidLocation(now, cachedCoords)
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
                limit = 60
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

            val ageMins = java.time.Duration.between(resolvedCoords.timestamp, now).toMinutes()
            val warningBanner = if (resolvedCoords.isStaleUndergroundFallback && ageMins >= 5) {
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

    private suspend fun handleNoValidLocation(now: Instant, cachedCoords: UserCoordinates?) {
        val hasFine = appContext.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val hasCoarse = appContext.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val errorMsg = if (!hasFine && !hasCoarse) {
            "Location permission required. Tap to open Settings."
        } else {
            "No GPS fix available. Ensure location is enabled or select a test stop in Settings."
        }
        restoreCachedDepartures(
            now = now,
            userCoords = cachedCoords?.let { Coordinates(it.lat, it.lon) },
            fallbackErrorMessage = errorMsg
        )
    }

    private suspend fun restoreCachedDepartures(
        now: Instant,
        userCoords: Coordinates?,
        fallbackErrorMessage: String?
    ) {
        restoreCachedDepartures(
            context = appContext,
            now = now,
            userCoords = userCoords,
            fallbackErrorMessage = fallbackErrorMessage
        )
    }

    @SuppressLint("MissingPermission")
    private suspend fun getFreshLocation(): UserCoordinates? {
        val now = Instant.now()
        val simulatedHub = settingsRepository.getSelectedSimulationHub()
        if (simulatedHub != null) {
            return locationStrategy.fromProviderReading(
                lat = simulatedHub.lat,
                lon = simulatedHub.lon,
                accuracyMeters = 1.0f,
                observedAt = now
            )
        }

        val hasFine = appContext.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val hasCoarse = appContext.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!hasFine && !hasCoarse) {
            return null
        }

        return try {
            val fusedClient = LocationServices.getFusedLocationProviderClient(appContext)

            // 1. Actively query high-accuracy GPS fix with 4s timeout
            val cts = CancellationTokenSource()
            val freshLocation = try {
                withTimeoutOrNull(4_000) {
                    fusedClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token).await()
                }
            } finally {
                cts.cancel()
            }

            if (freshLocation != null) {
                return locationStrategy.fromProviderReading(
                    lat = freshLocation.latitude,
                    lon = freshLocation.longitude,
                    accuracyMeters = freshLocation.accuracy,
                    observedAt = now
                )
            }

            // 2. Fallback to Fused lastLocation if fresh query timed out
            val lastLocation = try {
                fusedClient.lastLocation.await()
            } catch (_: Exception) {
                null
            }
            if (lastLocation != null) {
                return locationStrategy.fromProviderReading(
                    lat = lastLocation.latitude,
                    lon = lastLocation.longitude,
                    accuracyMeters = lastLocation.accuracy,
                    observedAt = now
                )
            }

            // 3. Fallback to Android LocationManager providers (reliable on emulators)
            val lmLocation = getLocationFromLocationManager(appContext)
            if (lmLocation != null) {
                locationStrategy.fromProviderReading(
                    lat = lmLocation.latitude,
                    lon = lmLocation.longitude,
                    accuracyMeters = if (lmLocation.hasAccuracy()) lmLocation.accuracy else null,
                    observedAt = now
                )
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    @SuppressLint("MissingPermission")
    private fun getLocationFromLocationManager(context: Context): Location? {
        return try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
            val providers = listOf(
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER
            )
            providers.mapNotNull { provider ->
                try {
                    if (lm.isProviderEnabled(provider)) lm.getLastKnownLocation(provider) else null
                } catch (_: Exception) {
                    null
                }
            }.maxByOrNull { it.time }
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        const val WORK_NAME_PERIODIC = "arrivals_periodic_update"
        const val WORK_NAME_ONE_TIME = "arrivals_one_time_update"

        suspend fun restoreCachedDepartures(
            context: Context,
            now: Instant = Instant.now(),
            userCoords: Coordinates? = null,
            fallbackErrorMessage: String? = null
        ) {
            val cacheRepository = DeparturesCacheRepository(context)
            val normalizer = DepartureNormalizer()
            val engine = ArrivalsEngine()
            val cached = cacheRepository.getLastResponse()
            val current = WidgetStateManager.getState(context)
            val targetStop = (current as? ArrivalsViewState.Content)?.activeStop

            if (cached != null) {
                val (cachedResponse, cachedTime) = cached
                val stops = normalizer.normalize(cachedResponse, referenceTime = now)
                val matchedStop = if (targetStop != null) {
                    stops.find { it.id == targetStop.id || it.name == targetStop.name }
                } else {
                    stops.firstOrNull()
                }

                val hasCachedDeparturesForStop = matchedStop != null &&
                    matchedStop.platforms.any { it.departures.isNotEmpty() }

                if (hasCachedDeparturesForStop) {
                    val warningBanner = CacheResilience.formatCachedWarning(cachedTime)
                    val updatedState = engine.reduce(
                        current,
                        ArrivalsAction.UpdateData(
                            stops = stops,
                            userCoordinates = userCoords,
                            cachedWarning = warningBanner
                        )
                    )
                    val finalState = if (updatedState is ArrivalsViewState.Content) {
                        updatedState.copy(isRefreshing = false, isLoadingDepartures = false)
                    } else {
                        updatedState
                    }
                    WidgetStateManager.setState(context, finalState)
                    WidgetStateManager.updateAllWidgets(context)
                    return
                }
            }

            val stopName = targetStop?.name
            val msg = if (stopName != null) {
                "Unable to fetch departures for $stopName. Check connection."
            } else {
                fallbackErrorMessage ?: "Unable to fetch transit departures"
            }
            WidgetStateManager.setState(context, ArrivalsViewState.Error(msg, canRetry = true))
            WidgetStateManager.updateAllWidgets(context)
        }

        fun enqueueOneTimeWork(context: Context) {
            try {
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
            } catch (e: Exception) {
                // Ignore in environments without WorkManager initialization
            }
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

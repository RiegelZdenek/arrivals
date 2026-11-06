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
import cz.riegel.arrivals.data.DeparturesRepository
import cz.riegel.arrivals.data.GolemioApiClient
import cz.riegel.arrivals.data.LocationResolution
import cz.riegel.arrivals.data.SettingsRepository
import cz.riegel.arrivals.data.TransitDataSource
import cz.riegel.arrivals.data.model.GolemioDepartureBoardResponse
import cz.riegel.arrivals.domain.ArrivalsAction
import cz.riegel.arrivals.domain.ArrivalsEngine
import cz.riegel.arrivals.domain.ArrivalsViewState
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
    private val departuresRepository = DeparturesRepository.create(appContext)
    private val engine = ArrivalsEngine()

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

        val transitDataSource = TransitDataSource.create(appContext) { apiKey }
        val now = Instant.now()

        // 1. Resolve coordinates (High-Accuracy GPS or 30-min underground fallback per ADR-0003)
        val locationResolution = departuresRepository.resolveLocation(
            freshCoordinates = getFreshLocation(),
            currentTime = now
        )

        if (locationResolution == null) {
            handleNoValidLocation(now)
            return Result.success()
        }

        val userCoords = locationResolution.coordinates

        // 2. Fetch live departures with adaptive radius expansion
        val preferredRadius = settingsRepository.getSearchRadius()
        val fetchResult = fetchWithAdaptiveRadius(
            transitDataSource = transitDataSource,
            userCoordinates = userCoords,
            preferredRadius = preferredRadius,
            adaptiveRadii = engine.adaptiveSearchRadii,
            limit = 60
        )

        if (fetchResult.isSuccess) {
            val apiSuccessResponse = fetchResult.getOrThrow()
            departuresRepository.saveDepartures(apiSuccessResponse, now)

            val warningBanner = (locationResolution as? LocationResolution.UndergroundFallback)?.warningBanner

            val finalState = engine.reduce(
                WidgetStateManager.getState(appContext),
                ArrivalsAction.IngestApiResponse(
                    response = apiSuccessResponse,
                    userCoordinates = userCoords,
                    referenceTime = now,
                    cachedWarning = warningBanner
                )
            )

            WidgetStateManager.setState(appContext, finalState)
            WidgetStateManager.updateAllWidgets(appContext)
            return Result.success()
        }

        // 3. Network or Rate Limit failure resilience: fall back to cached departures
        restoreCachedDepartures(
            now = now,
            userCoords = userCoords,
            fallbackErrorMessage = fetchResult.exceptionOrNull()?.message
        )
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
        restoreCachedDepartures(
            context = appContext,
            now = now,
            userCoords = userCoords,
            fallbackErrorMessage = fallbackErrorMessage,
            repository = departuresRepository
        )
    }

    @SuppressLint("MissingPermission")
    private suspend fun getFreshLocation(): Coordinates? {
        val simulatedHub = settingsRepository.getSelectedSimulationHub()
        if (simulatedHub != null) {
            return Coordinates(lat = simulatedHub.lat, lon = simulatedHub.lon)
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
                return Coordinates(lat = freshLocation.latitude, lon = freshLocation.longitude)
            }

            // 2. Fallback to Fused lastLocation if fresh query timed out
            val lastLocation = try {
                fusedClient.lastLocation.await()
            } catch (_: Exception) {
                null
            }
            if (lastLocation != null) {
                return Coordinates(lat = lastLocation.latitude, lon = lastLocation.longitude)
            }

            // 3. Fallback to Android LocationManager providers (reliable on emulators)
            val lmLocation = getLocationFromLocationManager(appContext)
            if (lmLocation != null) {
                Coordinates(lat = lmLocation.latitude, lon = lmLocation.longitude)
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

        suspend fun fetchWithAdaptiveRadius(
            transitDataSource: TransitDataSource,
            userCoordinates: Coordinates,
            preferredRadius: Int,
            adaptiveRadii: List<Int> = ArrivalsEngine().adaptiveSearchRadii,
            limit: Int = 60
        ): kotlin.Result<GolemioDepartureBoardResponse> {
            val searchRadii = listOf(preferredRadius) + adaptiveRadii.filter { it != preferredRadius }
            var apiSuccessResponse: GolemioDepartureBoardResponse? = null
            var lastError: Throwable? = null

            for (radius in searchRadii) {
                val responseResult = transitDataSource.fetchNearbyDepartures(
                    coordinates = userCoordinates,
                    radiusMeters = radius,
                    limit = limit
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

            return if (apiSuccessResponse != null) {
                kotlin.Result.success(apiSuccessResponse)
            } else {
                kotlin.Result.failure(lastError ?: Exception("Unable to fetch transit departures"))
            }
        }

        suspend fun restoreCachedDepartures(
            context: Context,
            now: Instant = Instant.now(),
            userCoords: Coordinates? = null,
            fallbackErrorMessage: String? = null,
            repository: DeparturesRepository = DeparturesRepository.create(context)
        ) {
            val engine = ArrivalsEngine()
            val cached = repository.getCachedDepartures()
            val current = WidgetStateManager.getState(context, now, repository)
            val targetStop = (current as? ArrivalsViewState.Content)?.activeStop

            if (cached != null && (cached.response.stops.isNotEmpty() || cached.response.departures.isNotEmpty())) {
                val updatedState = engine.reduce(
                    current,
                    ArrivalsAction.IngestApiResponse(
                        response = cached.response,
                        userCoordinates = userCoords ?: cached.coordinates,
                        referenceTime = now,
                        cachedWarning = cached.warningBanner
                    )
                )
                if (updatedState is ArrivalsViewState.Content) {
                    val matchedStop = if (targetStop != null) {
                        updatedState.stops.find { it.id == targetStop.id || it.name == targetStop.name }
                            ?: updatedState.activeStop
                    } else {
                        updatedState.activeStop
                    }

                    val hasCachedDeparturesForStop = matchedStop.platforms.any { it.departures.isNotEmpty() }
                    val finalState = if (hasCachedDeparturesForStop && updatedState.activeDepartures.isNotEmpty()) {
                        updatedState
                    } else {
                        updatedState.copy(cachedWarning = "No upcoming departures • Offline • Tap to retry")
                    }
                    WidgetStateManager.setState(context, finalState, repository)
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
            WidgetStateManager.setState(context, ArrivalsViewState.Error(msg, canRetry = true), repository)
            WidgetStateManager.updateAllWidgets(context)
        }

        internal var oneTimeWorkEnqueuedCount = 0

        fun enqueueOneTimeWork(context: Context) {
            oneTimeWorkEnqueuedCount++
            try {
                val request = OneTimeWorkRequestBuilder<ArrivalsUpdateWorker>()
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

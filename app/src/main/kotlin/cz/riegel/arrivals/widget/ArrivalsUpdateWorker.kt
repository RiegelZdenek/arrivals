package cz.riegel.arrivals.widget

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import cz.riegel.arrivals.R
import cz.riegel.arrivals.data.DeparturesRepository
import cz.riegel.arrivals.data.LocationResolution
import cz.riegel.arrivals.data.LocationResolver
import cz.riegel.arrivals.data.SettingsRepository
import cz.riegel.arrivals.data.TransitDataSource
import cz.riegel.arrivals.data.model.GolemioDepartureBoardResponse
import cz.riegel.arrivals.domain.ArrivalsAction
import cz.riegel.arrivals.domain.ArrivalsEngine
import cz.riegel.arrivals.domain.ArrivalsViewState
import cz.riegel.arrivals.domain.model.Coordinates
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.util.concurrent.TimeUnit

class ArrivalsUpdateWorker(
    private val appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    private val settingsRepository = SettingsRepository(appContext)
    internal var departuresRepository: DeparturesRepository = DeparturesRepository.create(appContext)
    internal var transitDataSource: TransitDataSource? = null
    internal var engine: ArrivalsEngine = ArrivalsEngine()
    internal var locationResolver: LocationResolver = defaultLocationResolverProvider?.invoke(appContext) ?: LocationResolver.create(appContext)
    internal var clock: () -> Instant = { Instant.now() }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val channelId = "arrivals_widget_updates"
        val notificationManager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && notificationManager != null) {
            val channel = NotificationChannel(
                channelId,
                appContext.getString(R.string.app_name),
                NotificationManager.IMPORTANCE_LOW
            )
            notificationManager.createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(appContext, channelId)
            .setContentTitle("Updating arrivals...")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    override suspend fun doWork(): Result {
        executeUpdate(
            appContext = appContext,
            settingsRepository = settingsRepository,
            departuresRepository = departuresRepository,
            locationResolver = locationResolver,
            engine = engine,
            currentTime = clock(),
            transitDataSource = transitDataSource,
            force = true
        )
        return Result.success()
    }

    companion object {
        const val WORK_NAME_PERIODIC = "arrivals_periodic_update"
        const val WORK_NAME_ONE_TIME = "arrivals_one_time_update"
        private const val NOTIFICATION_ID = 1001

        const val MIN_UPDATE_INTERVAL_MS = 5_000L
        private val updateMutex = Mutex()
        internal var lastSuccessfulUpdateMillis: Long = 0L

        internal var oneTimeWorkEnqueuedCount = 0
        internal var lastEnqueuedWorkRequest: OneTimeWorkRequest? = null
        internal var defaultTransitDataSourceProvider: ((Context, String) -> TransitDataSource)? = null
        internal var defaultLocationResolverProvider: ((Context) -> LocationResolver)? = null

        fun buildOneTimeWorkRequest(expedited: Boolean = true): OneTimeWorkRequest {
            val builder = OneTimeWorkRequestBuilder<ArrivalsUpdateWorker>()
            if (expedited) {
                builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            }
            return builder.build()
        }

        fun enqueueExpeditedOneTimeWork(context: Context) {
            enqueueOneTimeWork(context, expedited = true)
        }

        fun enqueueOneTimeWork(context: Context, expedited: Boolean = true) {
            oneTimeWorkEnqueuedCount++
            val request = buildOneTimeWorkRequest(expedited)
            lastEnqueuedWorkRequest = request
            try {
                WorkManager.getInstance(context).enqueueUniqueWork(
                    WORK_NAME_ONE_TIME,
                    ExistingWorkPolicy.REPLACE,
                    request
                )
            } catch (_: Exception) {
                // Ignore in environments without WorkManager initialization
            }
        }

        suspend fun executeUpdate(
            appContext: Context,
            settingsRepository: SettingsRepository = SettingsRepository(appContext),
            departuresRepository: DeparturesRepository = DeparturesRepository.create(appContext),
            locationResolver: LocationResolver = defaultLocationResolverProvider?.invoke(appContext) ?: LocationResolver.create(appContext),
            engine: ArrivalsEngine = ArrivalsEngine(),
            currentTime: Instant = Instant.now(),
            transitDataSource: TransitDataSource? = null,
            force: Boolean = false
        ): Boolean = updateMutex.withLock {
            val nowMillis = currentTime.toEpochMilli()
            if (!force && (nowMillis - lastSuccessfulUpdateMillis) < MIN_UPDATE_INTERVAL_MS) {
                return true
            }

            val apiKey = settingsRepository.getApiKey()
            if (apiKey.isNullOrBlank()) {
                WidgetStateManager.setState(appContext, ArrivalsViewState.SetupRequired(), departuresRepository)
                WidgetStateManager.updateAllWidgets(appContext)
                return false
            }

            // Show loading/refreshing visual indication immediately
            val currentState = WidgetStateManager.getState(appContext, currentTime, departuresRepository)
            if (currentState is ArrivalsViewState.Content && !currentState.isRefreshing) {
                WidgetStateManager.setState(appContext, currentState.copy(isRefreshing = true), departuresRepository)
                WidgetStateManager.updateAllWidgets(appContext)
            }

            val locationResolution = departuresRepository.resolveLocation(
                freshCoordinates = getFreshLocation(appContext, settingsRepository, locationResolver),
                currentTime = currentTime
            )

            if (locationResolution == null) {
                val currentWidgetState = WidgetStateManager.getState(appContext, currentTime, departuresRepository)
                val baseState = currentWidgetState as? ArrivalsViewState.Content
                    ?: WidgetStateManager.rehydrateFromCache(currentTime, departuresRepository)
                val activeStop = baseState?.activeStop

                if (activeStop != null) {
                    val dataSource = transitDataSource ?: defaultTransitDataSourceProvider?.invoke(appContext, apiKey) ?: TransitDataSource.create(appContext) { apiKey }
                    val fetchResult = dataSource.fetchDeparturesForStops(listOf(activeStop.name))
                    if (fetchResult.isSuccess) {
                        val apiSuccessResponse = fetchResult.getOrThrow()
                        if (apiSuccessResponse.departures.isNotEmpty() || apiSuccessResponse.stops.isNotEmpty()) {
                            departuresRepository.saveDepartures(apiSuccessResponse, currentTime)
                            val warningBanner = "GPS unavailable • Showing ${activeStop.name}"
                            val finalState = engine.reduce(
                                baseState,
                                ArrivalsAction.IngestApiResponse(
                                    response = apiSuccessResponse,
                                    userCoordinates = null,
                                    referenceTime = currentTime,
                                    cachedWarning = warningBanner
                                )
                            )
                            WidgetStateManager.setState(appContext, finalState, departuresRepository)
                            WidgetStateManager.updateAllWidgets(appContext)
                            lastSuccessfulUpdateMillis = nowMillis
                            return true
                        }
                    }

                    // If active stop query fails or returns empty, fall back to restoreCachedDepartures
                    restoreCachedDepartures(
                        context = appContext,
                        now = currentTime,
                        userCoords = null,
                        fallbackErrorMessage = fetchResult.exceptionOrNull()?.message,
                        repository = departuresRepository
                    )
                    return false
                }

                handleNoValidLocation(appContext, currentTime, departuresRepository)
                return false
            }

            val userCoords = locationResolution.coordinates
            val dataSource = transitDataSource ?: defaultTransitDataSourceProvider?.invoke(appContext, apiKey) ?: TransitDataSource.create(appContext) { apiKey }
            val preferredRadius = settingsRepository.getSearchRadius()
            val fetchResult = fetchWithAdaptiveRadius(
                transitDataSource = dataSource,
                userCoordinates = userCoords,
                preferredRadius = preferredRadius,
                adaptiveRadii = engine.adaptiveSearchRadii,
                limit = 60
            )

            if (fetchResult.isSuccess) {
                lastSuccessfulUpdateMillis = nowMillis
                val apiSuccessResponse = fetchResult.getOrThrow()
                departuresRepository.saveDepartures(apiSuccessResponse, currentTime)

                val warningBanner = (locationResolution as? LocationResolution.UndergroundFallback)?.warningBanner

                val finalState = engine.reduce(
                    WidgetStateManager.getState(appContext, currentTime, departuresRepository),
                    ArrivalsAction.IngestApiResponse(
                        response = apiSuccessResponse,
                        userCoordinates = userCoords,
                        referenceTime = currentTime,
                        cachedWarning = warningBanner
                    )
                )

                WidgetStateManager.setState(appContext, finalState, departuresRepository)
                WidgetStateManager.updateAllWidgets(appContext)
                return true
            }

            restoreCachedDepartures(
                context = appContext,
                now = currentTime,
                userCoords = userCoords,
                fallbackErrorMessage = fetchResult.exceptionOrNull()?.message,
                repository = departuresRepository
            )
            return false
        }

        private suspend fun handleNoValidLocation(
            context: Context,
            now: Instant,
            repository: DeparturesRepository
        ) {
            val hasFine = context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
            val hasCoarse = context.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
            val errorMsg = if (!hasFine && !hasCoarse) {
                "Location permission required. Tap to open Settings."
            } else {
                "No GPS fix available. Ensure location is enabled or select a test stop in Settings."
            }
            restoreCachedDepartures(
                context = context,
                now = now,
                userCoords = null,
                fallbackErrorMessage = errorMsg,
                repository = repository
            )
        }

        @SuppressLint("MissingPermission")
        private suspend fun getFreshLocation(
            context: Context,
            settingsRepository: SettingsRepository,
            locationResolver: LocationResolver
        ): Coordinates? {
            val simulatedHub = settingsRepository.getSelectedSimulationHub()
            if (simulatedHub != null) {
                return Coordinates(lat = simulatedHub.lat, lon = simulatedHub.lon)
            }

            val hasFine = context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
            val hasCoarse = context.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!hasFine && !hasCoarse) {
                return null
            }

            return try {
                val location = locationResolver.resolveLocation(
                    highAccuracyTimeoutMs = LocationResolver.DEFAULT_HIGH_ACCURACY_TIMEOUT_MS,
                    balancedTimeoutMs = LocationResolver.DEFAULT_BALANCED_TIMEOUT_MS
                )
                location?.let { Coordinates(lat = it.latitude, lon = it.longitude) }
            } catch (_: Exception) {
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

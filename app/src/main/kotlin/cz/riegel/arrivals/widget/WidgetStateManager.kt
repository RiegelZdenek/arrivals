package cz.riegel.arrivals.widget

import android.content.Context
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import cz.riegel.arrivals.data.DeparturesRepository
import cz.riegel.arrivals.domain.ArrivalsAction
import cz.riegel.arrivals.domain.ArrivalsEngine
import cz.riegel.arrivals.domain.ArrivalsViewState
import cz.riegel.arrivals.domain.PickerMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant

object WidgetStateManager {
    private val engine = ArrivalsEngine()
    private val _stateFlow = MutableStateFlow<ArrivalsViewState>(ArrivalsViewState.Loading())
    val stateFlow = _stateFlow.asStateFlow()
    private val GLANCE_STATE_VERSION_KEY = longPreferencesKey("glance_state_version")
    private var versionCounter = 0L

    internal var refreshScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun getState(
        context: Context,
        referenceTime: Instant = Instant.now(),
        repository: DeparturesRepository = DeparturesRepository.create(context)
    ): ArrivalsViewState {
        WidgetCountdownTicker.ensureRegistered(context)
        val current = _stateFlow.value
        if (current is ArrivalsViewState.Loading) {
            val rehydrated = rehydrateFromCache(referenceTime, repository)
            if (rehydrated != null) {
                _stateFlow.value = rehydrated
                return rehydrated
            }
        } else if (current is ArrivalsViewState.Content) {
            val refreshed = engine.reduce(current, ArrivalsAction.TickCountdown(referenceTime))
            _stateFlow.value = refreshed
            return refreshed
        }
        return current
    }

    suspend fun tickCountdowns(
        context: Context,
        referenceTime: Instant = Instant.now(),
        repository: DeparturesRepository = DeparturesRepository.create(context)
    ) {
        val state = getState(context, referenceTime, repository)
        if (state is ArrivalsViewState.Content) {
            updateAllWidgets(context)
        }
    }

    internal fun rehydrateFromCache(
        referenceTime: Instant = Instant.now(),
        repository: DeparturesRepository
    ): ArrivalsViewState.Content? {
        return try {
            val cached = repository.getCachedDepartures() ?: return null

            val reduced = engine.reduce(
                ArrivalsViewState.Loading(),
                ArrivalsAction.IngestApiResponse(
                    response = cached.response,
                    userCoordinates = cached.coordinates,
                    referenceTime = referenceTime,
                    cachedWarning = cached.warningBanner
                )
            )
            if (reduced is ArrivalsViewState.Content) {
                val savedStopIdx = repository.getActiveStopIndex().coerceIn(0, (reduced.stops.size - 1).coerceAtLeast(0))
                val savedStop = reduced.stops[savedStopIdx]
                val savedPlatform = repository.getActivePlatformCode()
                val validPlatform = if (savedPlatform != null && savedStop.platforms.any { it.code == savedPlatform }) {
                    savedPlatform
                } else {
                    engine.findClosestPlatform(savedStop, cached.coordinates)?.code
                        ?: savedStop.platforms.firstOrNull()?.code.orEmpty()
                }
                val rehydrated = reduced.copy(
                    activeStopIndex = savedStopIdx,
                    activePlatformCode = validPlatform,
                    pickerMode = PickerMode.Departures
                )
                if (rehydrated.activeDepartures.isEmpty()) {
                    rehydrated.copy(cachedWarning = "No upcoming departures • Offline • Tap to retry")
                } else {
                    rehydrated
                }
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    fun setState(
        context: Context,
        newState: ArrivalsViewState,
        repository: DeparturesRepository = DeparturesRepository.create(context)
    ) {
        val previousState = _stateFlow.value
        _stateFlow.value = newState
        if (newState is ArrivalsViewState.Content) {
            val isTransientPickerToggle = previousState is ArrivalsViewState.Content &&
                previousState.activeStopIndex == newState.activeStopIndex &&
                previousState.activePlatformCode == newState.activePlatformCode

            if (!isTransientPickerToggle) {
                try {
                    repository.saveActiveSelection(
                        newState.activeStopIndex,
                        newState.activePlatformCode
                    )
                } catch (e: Exception) {
                    // Ignore in non-Android test environments if needed
                }
            }
        } else if (newState is ArrivalsViewState.Empty) {
            try {
                repository.saveActiveSelection(0, "")
            } catch (e: Exception) {
            }
        }
    }

    private suspend fun dispatch(
        context: Context,
        action: ArrivalsAction,
        repository: DeparturesRepository = DeparturesRepository.create(context)
    ) {
        val current = getState(context, repository = repository)
        val next = engine.reduce(current, action)
        setState(context, next, repository)
        updateAllWidgets(context)
    }

    suspend fun onOpenStopPicker(
        context: Context,
        repository: DeparturesRepository = DeparturesRepository.create(context)
    ) = dispatch(context, ArrivalsAction.OpenStopPicker, repository)

    suspend fun onOpenPlatformPicker(
        context: Context,
        repository: DeparturesRepository = DeparturesRepository.create(context)
    ) = dispatch(context, ArrivalsAction.OpenPlatformPicker, repository)

    suspend fun onClosePicker(
        context: Context,
        repository: DeparturesRepository = DeparturesRepository.create(context)
    ) = dispatch(context, ArrivalsAction.ClosePicker, repository)

    suspend fun onSelectStop(
        context: Context,
        stopIndex: Int,
        repository: DeparturesRepository = DeparturesRepository.create(context)
    ) {
        val current = getState(context, repository = repository)
        val isDifferentStop = current is ArrivalsViewState.Content && current.activeStopIndex != stopIndex
        dispatch(context, ArrivalsAction.SelectStop(stopIndex), repository)
        if (isDifferentStop) {
            ArrivalsUpdateWorker.enqueueOneTimeWork(context)
        }
    }

    suspend fun onSelectPlatform(
        context: Context,
        platformCode: String,
        repository: DeparturesRepository = DeparturesRepository.create(context)
    ) = dispatch(context, ArrivalsAction.SelectPlatform(platformCode), repository)

    suspend fun onRefresh(
        context: Context,
        repository: DeparturesRepository = DeparturesRepository.create(context),
        scope: CoroutineScope = refreshScope
    ): Job {
        val current = getState(context, repository = repository)
        val next = engine.reduce(current, ArrivalsAction.Refresh)
        setState(context, next, repository)
        updateAllWidgets(context)

        ArrivalsUpdateWorker.enqueueExpeditedOneTimeWork(context)

        return scope.launch {
            try {
                ArrivalsUpdateWorker.executeUpdate(
                    appContext = context.applicationContext,
                    departuresRepository = repository,
                    force = true
                )
            } catch (_: Exception) {
            }
        }
    }

    suspend fun updateAllWidgets(context: Context) {
        try {
            val manager = GlanceAppWidgetManager(context)
            val glanceIds = manager.getGlanceIds(ArrivalsWidget::class.java)
            val nextVersion = ++versionCounter + System.currentTimeMillis()
            glanceIds.forEach { id ->
                try {
                    updateAppWidgetState(context, id) { prefs ->
                        prefs[GLANCE_STATE_VERSION_KEY] = nextVersion
                    }
                } catch (_: Exception) {
                }
                ArrivalsWidget().update(context, id)
            }
        } catch (e: Exception) {
            // No-op in environments without AppWidgetManager host
        }
    }
}

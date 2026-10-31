package cz.riegel.arrivals.widget

import android.content.Context
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import cz.riegel.arrivals.data.DeparturesCacheRepository
import cz.riegel.arrivals.domain.ArrivalsAction
import cz.riegel.arrivals.domain.ArrivalsEngine
import cz.riegel.arrivals.domain.ArrivalsViewState
import cz.riegel.arrivals.domain.PickerMode
import cz.riegel.arrivals.domain.model.Coordinates
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant

object WidgetStateManager {
    private val engine = ArrivalsEngine()
    private val _stateFlow = MutableStateFlow<ArrivalsViewState>(ArrivalsViewState.Loading())
    val stateFlow = _stateFlow.asStateFlow()
    private val GLANCE_STATE_VERSION_KEY = longPreferencesKey("glance_state_version")
    private var versionCounter = 0L

    fun getState(context: Context, referenceTime: Instant = Instant.now()): ArrivalsViewState {
        WidgetCountdownTicker.ensureRegistered(context)
        val current = _stateFlow.value
        if (current is ArrivalsViewState.Loading) {
            val rehydrated = rehydrateFromCache(context, referenceTime)
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

    suspend fun tickCountdowns(context: Context, referenceTime: Instant = Instant.now()) {
        val state = getState(context, referenceTime)
        if (state is ArrivalsViewState.Content) {
            updateAllWidgets(context)
        }
    }

    private fun rehydrateFromCache(
        context: Context,
        referenceTime: Instant = Instant.now()
    ): ArrivalsViewState.Content? {
        return try {
            val cacheRepo = DeparturesCacheRepository(context)
            val cached = cacheRepo.getLastResponse() ?: return null
            val (dto, _) = cached

            val cachedCoords = cacheRepo.getLastCoordinates()?.let {
                Coordinates(it.lat, it.lon)
            }
            val reduced = engine.reduce(
                ArrivalsViewState.Loading(),
                ArrivalsAction.IngestApiResponse(
                    response = dto,
                    userCoordinates = cachedCoords,
                    referenceTime = referenceTime,
                    cachedWarning = null
                )
            )
            if (reduced is ArrivalsViewState.Content) {
                val savedStopIdx = cacheRepo.getActiveStopIndex().coerceIn(0, (reduced.stops.size - 1).coerceAtLeast(0))
                val savedStop = reduced.stops[savedStopIdx]
                val savedPlatform = cacheRepo.getActivePlatformCode()
                val validPlatform = if (savedPlatform != null && savedStop.platforms.any { it.code == savedPlatform }) {
                    savedPlatform
                } else {
                    engine.findClosestPlatform(savedStop, cachedCoords)?.code
                        ?: savedStop.platforms.firstOrNull()?.code.orEmpty()
                }
                reduced.copy(
                    activeStopIndex = savedStopIdx,
                    activePlatformCode = validPlatform,
                    pickerMode = PickerMode.Departures
                )
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    fun setState(context: Context, newState: ArrivalsViewState) {
        val previousState = _stateFlow.value
        _stateFlow.value = newState
        if (newState is ArrivalsViewState.Content) {
            val isTransientPickerToggle = previousState is ArrivalsViewState.Content &&
                previousState.activeStopIndex == newState.activeStopIndex &&
                previousState.activePlatformCode == newState.activePlatformCode

            if (!isTransientPickerToggle) {
                try {
                    DeparturesCacheRepository(context).saveActiveSelection(
                        newState.activeStopIndex,
                        newState.activePlatformCode
                    )
                } catch (e: Exception) {
                    // Ignore in non-Android test environments if needed
                }
            }
        }
    }

    private suspend fun dispatch(context: Context, action: ArrivalsAction) {
        val current = getState(context)
        val next = engine.reduce(current, action)
        setState(context, next)
        updateAllWidgets(context)
    }

    suspend fun onOpenStopPicker(context: Context) =
        dispatch(context, ArrivalsAction.OpenStopPicker)

    suspend fun onOpenPlatformPicker(context: Context) =
        dispatch(context, ArrivalsAction.OpenPlatformPicker)

    suspend fun onClosePicker(context: Context) =
        dispatch(context, ArrivalsAction.ClosePicker)

    suspend fun onSelectStop(context: Context, stopIndex: Int) {
        val current = getState(context)
        val isDifferentStop = current is ArrivalsViewState.Content && current.activeStopIndex != stopIndex
        dispatch(context, ArrivalsAction.SelectStop(stopIndex))
        if (isDifferentStop) {
            ArrivalsUpdateWorker.enqueueOneTimeWork(context)
        }
    }

    suspend fun onSelectPlatform(context: Context, platformCode: String) =
        dispatch(context, ArrivalsAction.SelectPlatform(platformCode))

    suspend fun onRefresh(context: Context) {
        val current = getState(context)
        val next = engine.reduce(current, ArrivalsAction.Refresh)
        setState(context, next)
        updateAllWidgets(context)

        ArrivalsUpdateWorker.enqueueOneTimeWork(context)
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

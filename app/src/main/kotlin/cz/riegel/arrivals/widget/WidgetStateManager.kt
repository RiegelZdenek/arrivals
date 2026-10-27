package cz.riegel.arrivals.widget

import android.content.Context
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import cz.riegel.arrivals.data.DeparturesCacheRepository
import cz.riegel.arrivals.domain.ArrivalsAction
import cz.riegel.arrivals.domain.ArrivalsEngine
import cz.riegel.arrivals.domain.ArrivalsViewState
import cz.riegel.arrivals.domain.DepartureNormalizer
import cz.riegel.arrivals.domain.model.Coordinates
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant

object WidgetStateManager {
    private val engine = ArrivalsEngine()
    private val normalizer = DepartureNormalizer()
    private val _stateFlow = MutableStateFlow<ArrivalsViewState>(ArrivalsViewState.Loading())
    val stateFlow = _stateFlow.asStateFlow()
    private val GLANCE_STATE_VERSION_KEY = longPreferencesKey("glance_state_version")
    private var versionCounter = 0L

    fun getState(context: Context): ArrivalsViewState {
        val current = _stateFlow.value
        if (current is ArrivalsViewState.Loading) {
            val rehydrated = rehydrateFromCache(context)
            if (rehydrated != null) {
                _stateFlow.value = rehydrated
                return rehydrated
            }
        }
        return current
    }

    private fun rehydrateFromCache(context: Context): ArrivalsViewState.Content? {
        return try {
            val cacheRepo = DeparturesCacheRepository(context)
            val cached = cacheRepo.getLastResponse() ?: return null
            val (dto, _) = cached
            val stops = normalizer.normalize(dto, referenceTime = Instant.now())
            if (stops.isEmpty()) return null

            val cachedCoords = cacheRepo.getLastCoordinates()?.let {
                Coordinates(it.lat, it.lon)
            }
            val reduced = engine.reduce(
                ArrivalsViewState.Loading(),
                ArrivalsAction.UpdateData(
                    stops = stops,
                    userCoordinates = cachedCoords,
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
                reduced.copy(activeStopIndex = savedStopIdx, activePlatformCode = validPlatform)
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    fun setState(context: Context, newState: ArrivalsViewState) {
        _stateFlow.value = newState
        if (newState is ArrivalsViewState.Content) {
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

    suspend fun onNextStop(context: Context) {
        val current = getState(context)
        val next = engine.reduce(current, ArrivalsAction.NextStop)
        setState(context, next)
        updateAllWidgets(context)
    }

    suspend fun onPrevStop(context: Context) {
        val current = getState(context)
        val prev = engine.reduce(current, ArrivalsAction.PreviousStop)
        setState(context, prev)
        updateAllWidgets(context)
    }

    suspend fun onSelectPlatform(context: Context, platformCode: String) {
        val current = getState(context)
        val selected = engine.reduce(current, ArrivalsAction.SelectPlatform(platformCode))
        setState(context, selected)
        updateAllWidgets(context)
    }

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

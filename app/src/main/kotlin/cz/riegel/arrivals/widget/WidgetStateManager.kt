package cz.riegel.arrivals.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import cz.riegel.arrivals.domain.ArrivalsAction
import cz.riegel.arrivals.domain.ArrivalsEngine
import cz.riegel.arrivals.domain.ArrivalsViewState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object WidgetStateManager {
    private val engine = ArrivalsEngine()
    private val _stateFlow = MutableStateFlow<ArrivalsViewState>(ArrivalsViewState.Loading())
    val stateFlow = _stateFlow.asStateFlow()

    fun getState(context: Context): ArrivalsViewState {
        return _stateFlow.value
    }

    fun setState(context: Context, newState: ArrivalsViewState) {
        _stateFlow.value = newState
    }

    suspend fun onNextStop(context: Context) {
        val next = engine.reduce(_stateFlow.value, ArrivalsAction.NextStop)
        _stateFlow.value = next
        updateAllWidgets(context)
    }

    suspend fun onPrevStop(context: Context) {
        val prev = engine.reduce(_stateFlow.value, ArrivalsAction.PreviousStop)
        _stateFlow.value = prev
        updateAllWidgets(context)
    }

    suspend fun onSelectPlatform(context: Context, platformCode: String) {
        val selected = engine.reduce(_stateFlow.value, ArrivalsAction.SelectPlatform(platformCode))
        _stateFlow.value = selected
        updateAllWidgets(context)
    }

    suspend fun onRefresh(context: Context) {
        // Trigger widget refresh workflow
        ArrivalsUpdateWorker.enqueueOneTimeWork(context)
    }

    suspend fun updateAllWidgets(context: Context) {
        try {
            val manager = GlanceAppWidgetManager(context)
            val glanceIds = manager.getGlanceIds(ArrivalsWidget::class.java)
            glanceIds.forEach { id ->
                ArrivalsWidget().update(context, id)
            }
        } catch (e: Exception) {
            // No-op in environments without AppWidgetManager host
        }
    }
}

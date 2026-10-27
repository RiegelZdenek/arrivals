package cz.riegel.arrivals.widget

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.CircularProgressIndicator
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import cz.riegel.arrivals.R
import cz.riegel.arrivals.domain.ArrivalsViewState
import cz.riegel.arrivals.domain.model.Departure
import cz.riegel.arrivals.domain.model.Platform
import cz.riegel.arrivals.domain.model.TransportMode
import cz.riegel.arrivals.ui.settings.SettingsActivity

class ArrivalsWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Responsive(
        setOf(
            DpSize(180.dp, 110.dp), // Compact 2x2
            DpSize(260.dp, 180.dp), // Standard 3x2
            DpSize(340.dp, 250.dp)  // Large
        )
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        WidgetStateManager.getState(context)
        provideContent {
            // Subscribe to both Glance Preferences state and StateFlow so all session updates recompose immediately
            currentState<Preferences>()
            val state by WidgetStateManager.stateFlow.collectAsState()
            ArrivalsWidgetContent(state = state)
        }
    }
}

class ArrivalsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ArrivalsWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        ArrivalsUpdateWorker.schedulePeriodicWork(context)
        ArrivalsUpdateWorker.enqueueOneTimeWork(context)
    }
}

// Widget action callbacks
val PlatformCodeKey = ActionParameters.Key<String>("platform_code")

class NextStopActionCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        WidgetStateManager.onNextStop(context)
    }
}

class PrevStopActionCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        WidgetStateManager.onPrevStop(context)
    }
}

class SelectPlatformActionCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val code = parameters[PlatformCodeKey] ?: return
        WidgetStateManager.onSelectPlatform(context, code)
    }
}

class RefreshActionCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        WidgetStateManager.onRefresh(context)
    }
}

@Composable
fun ArrivalsWidgetContent(state: ArrivalsViewState) {
    val context = LocalContext.current
    val size = LocalSize.current
    val darkBackground = Color(0xFF16151A)

    // Responsive departures count: compact (height < 180dp) displays 3, larger displays 5-6 (Spec 15)
    val maxDepartures = if (size.height < 180.dp) 3 else 6

    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(darkBackground)
            .cornerRadius(16.dp)
            .padding(12.dp)
    ) {
        when (state) {
            is ArrivalsViewState.Loading -> {
                Box(
                    modifier = GlanceModifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = GlanceModifier.size(32.dp),
                            color = androidx.glance.unit.ColorProvider(Color(0xFF81B0FF))
                        )
                        Spacer(modifier = GlanceModifier.height(10.dp))
                        Text(
                            text = state.message,
                            style = TextStyle(color = androidx.glance.unit.ColorProvider(Color.LightGray), fontSize = 13.sp)
                        )
                    }
                }
            }
            is ArrivalsViewState.SetupRequired -> {
                Column(
                    modifier = GlanceModifier
                        .fillMaxSize()
                        .padding(8.dp)
                        .clickable(actionStartActivity(Intent(context, SettingsActivity::class.java))),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = context.getString(R.string.setup_required_title),
                        style = TextStyle(color = androidx.glance.unit.ColorProvider(Color.White), fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    )
                    Spacer(modifier = GlanceModifier.height(4.dp))
                    Text(
                        text = context.getString(R.string.setup_required),
                        style = TextStyle(color = androidx.glance.unit.ColorProvider(Color(0xFFE5A93C)), fontSize = 13.sp)
                    )
                }
            }
            is ArrivalsViewState.Empty -> {
                Column(
                    modifier = GlanceModifier.fillMaxSize().padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = context.getString(R.string.no_stops_found),
                        style = TextStyle(color = androidx.glance.unit.ColorProvider(Color.LightGray), fontSize = 14.sp)
                    )
                    Spacer(modifier = GlanceModifier.height(8.dp))
                    Text(
                        text = context.getString(R.string.tap_to_refresh),
                        modifier = GlanceModifier.clickable(actionRunCallback<RefreshActionCallback>()),
                        style = TextStyle(color = androidx.glance.unit.ColorProvider(Color(0xFF81B0FF)), fontSize = 13.sp)
                    )
                }
            }
            is ArrivalsViewState.Error -> {
                Column(
                    modifier = GlanceModifier.fillMaxSize().padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = state.message,
                        style = TextStyle(color = androidx.glance.unit.ColorProvider(Color(0xFFFF8B8B)), fontSize = 13.sp)
                    )
                    if (state.canRetry) {
                        Spacer(modifier = GlanceModifier.height(6.dp))
                        Text(
                            text = context.getString(R.string.retry),
                            modifier = GlanceModifier.clickable(actionRunCallback<RefreshActionCallback>()),
                            style = TextStyle(color = androidx.glance.unit.ColorProvider(Color.White), fontSize = 13.sp)
                        )
                    }
                }
            }
            is ArrivalsViewState.Content -> {
                Column(modifier = GlanceModifier.fillMaxSize()) {
                    // Refreshing banner if update is currently in progress
                    if (state.isRefreshing) {
                        Row(
                            modifier = GlanceModifier
                                .fillMaxWidth()
                                .background(Color(0x3381B0FF))
                                .cornerRadius(6.dp)
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                modifier = GlanceModifier.size(12.dp),
                                color = androidx.glance.unit.ColorProvider(Color(0xFF81B0FF))
                            )
                            Spacer(modifier = GlanceModifier.width(6.dp))
                            Text(
                                text = "Aktualizace odjezdů…",
                                style = TextStyle(color = androidx.glance.unit.ColorProvider(Color(0xFF81B0FF)), fontSize = 11.sp)
                            )
                        }
                        Spacer(modifier = GlanceModifier.height(4.dp))
                    }

                    // Cached warning banner if present (Spec 14)
                    if (!state.cachedWarning.isNullOrBlank()) {
                        Row(
                            modifier = GlanceModifier
                                .fillMaxWidth()
                                .background(Color(0x33FFB300))
                                .cornerRadius(6.dp)
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                                .clickable(actionRunCallback<RefreshActionCallback>()),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "⚠ ${state.cachedWarning}",
                                style = TextStyle(color = androidx.glance.unit.ColorProvider(Color(0xFFFFCA28)), fontSize = 11.sp)
                            )
                        }
                        Spacer(modifier = GlanceModifier.height(4.dp))
                    }

                    // Header Row: Stop name, Distance badge, Refresh & Pagination
                    HeaderRow(state = state)

                    Spacer(modifier = GlanceModifier.height(6.dp))

                    // Platform selector pills (if platforms exist)
                    if (state.activeStop.platforms.size > 1) {
                        PlatformPillsRow(
                            platforms = state.activeStop.platforms,
                            selectedCode = state.activePlatformCode
                        )
                        Spacer(modifier = GlanceModifier.height(6.dp))
                    }

                    // Departures List (responsive compact vs standard)
                    val departures = state.activeDepartures.take(maxDepartures)
                    if (departures.isEmpty()) {
                        Text(
                            text = context.getString(R.string.no_departures),
                            style = TextStyle(color = androidx.glance.unit.ColorProvider(Color.Gray), fontSize = 12.sp),
                            modifier = GlanceModifier.padding(vertical = 8.dp)
                        )
                    } else {
                        departures.forEach { departure ->
                            DepartureRowItem(departure = departure)
                            Spacer(modifier = GlanceModifier.height(4.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun HeaderRow(state: ArrivalsViewState.Content) {
    val activeStop = state.activeStop
    val mapIntent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:${activeStop.lat},${activeStop.lon}?q=${Uri.encode(activeStop.name)}"))

    Row(
        modifier = GlanceModifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Stop Name (clickable to map)
        Text(
            text = activeStop.name,
            modifier = GlanceModifier.clickable(actionStartActivity(mapIntent)),
            style = TextStyle(
                color = androidx.glance.unit.ColorProvider(Color.White),
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp
            )
        )

        // Walking distance badge
        if (activeStop.distanceMeters != null) {
            Spacer(modifier = GlanceModifier.width(6.dp))
            Box(
                modifier = GlanceModifier
                    .background(Color(0xFF2E2C36))
                    .cornerRadius(8.dp)
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "${activeStop.distanceMeters}m",
                    style = TextStyle(color = androidx.glance.unit.ColorProvider(Color(0xFFB0B0C0)), fontSize = 11.sp)
                )
            }
        }

        Spacer(modifier = GlanceModifier.defaultWeight())

        // Stop cycling controls (◀ ▶)
        if (state.hasMultipleStops) {
            Text(
                text = "◀",
                modifier = GlanceModifier
                    .padding(horizontal = 4.dp)
                    .clickable(actionRunCallback<PrevStopActionCallback>()),
                style = TextStyle(color = androidx.glance.unit.ColorProvider(Color.LightGray), fontSize = 14.sp)
            )
            Text(
                text = "▶",
                modifier = GlanceModifier
                    .padding(horizontal = 4.dp)
                    .clickable(actionRunCallback<NextStopActionCallback>()),
                style = TextStyle(color = androidx.glance.unit.ColorProvider(Color.LightGray), fontSize = 14.sp)
            )
            Spacer(modifier = GlanceModifier.width(4.dp))
        }

        // Refresh icon (↻) or spinner
        if (state.isRefreshing) {
            CircularProgressIndicator(
                modifier = GlanceModifier
                    .size(16.dp)
                    .padding(start = 4.dp),
                color = androidx.glance.unit.ColorProvider(Color(0xFF81B0FF))
            )
        } else {
            Text(
                text = "↻",
                modifier = GlanceModifier
                    .padding(start = 4.dp)
                    .clickable(actionRunCallback<RefreshActionCallback>()),
                style = TextStyle(color = androidx.glance.unit.ColorProvider(Color(0xFF81B0FF)), fontWeight = FontWeight.Bold, fontSize = 16.sp)
            )
        }
    }
}

@Composable
fun PlatformPillsRow(platforms: List<Platform>, selectedCode: String) {
    Row(
        modifier = GlanceModifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        platforms.forEachIndexed { index, platform ->
            val isSelected = platform.code == selectedCode
            val pillBg = if (isSelected) Color.White else Color(0xFF2C2A34)
            val textColor = if (isSelected) Color.Black else Color(0xFFCCCCCC)
            val selectAction = actionRunCallback<SelectPlatformActionCallback>(
                actionParametersOf(PlatformCodeKey to platform.code)
            )

            Box(
                modifier = GlanceModifier
                    .background(pillBg)
                    .cornerRadius(10.dp)
                    .padding(horizontal = 10.dp, vertical = 4.dp)
                    .clickable(selectAction),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = platform.code,
                    modifier = GlanceModifier.clickable(selectAction),
                    style = TextStyle(
                        color = androidx.glance.unit.ColorProvider(textColor),
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        fontSize = 11.sp
                    )
                )
            }
            if (index < platforms.lastIndex) {
                Spacer(modifier = GlanceModifier.width(6.dp))
            }
        }
    }
}

@Composable
fun DepartureRowItem(departure: Departure) {
    val modeSymbol = when (departure.line.transportMode) {
        TransportMode.Metro -> "Ⓜ"
        TransportMode.Tram -> "🚊"
        TransportMode.Bus -> "🚌"
        TransportMode.Trolleybus -> "🚎"
    }

    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .background(Color(0xFF1E1D24))
            .cornerRadius(8.dp)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Mode symbol
        Text(
            text = modeSymbol,
            style = TextStyle(color = androidx.glance.unit.ColorProvider(Color.LightGray), fontSize = 13.sp)
        )
        Spacer(modifier = GlanceModifier.width(6.dp))

        // Line number
        Text(
            text = departure.line.name,
            style = TextStyle(
                color = androidx.glance.unit.ColorProvider(Color.White),
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
        )
        Spacer(modifier = GlanceModifier.width(8.dp))

        // Headsign
        Text(
            text = departure.headsign,
            style = TextStyle(color = androidx.glance.unit.ColorProvider(Color(0xFFE0E0E0)), fontSize = 13.sp)
        )

        Spacer(modifier = GlanceModifier.defaultWeight())

        // Delay chip if delayed
        if (departure.delay.isDelayed) {
            Box(
                modifier = GlanceModifier
                    .background(Color(0x33FFB300))
                    .cornerRadius(4.dp)
                    .padding(horizontal = 4.dp, vertical = 1.dp)
            ) {
                Text(
                    text = departure.delay.badgeText.orEmpty(),
                    style = TextStyle(color = androidx.glance.unit.ColorProvider(Color(0xFFFFB300)), fontSize = 11.sp)
                )
            }
            Spacer(modifier = GlanceModifier.width(4.dp))
        }

        // Countdown or Clock Time
        Text(
            text = departure.countdown.displayText,
            style = TextStyle(
                color = androidx.glance.unit.ColorProvider(Color.White),
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp
            )
        )
    }
}

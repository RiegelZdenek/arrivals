package cz.riegel.arrivals.widget

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.Action
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
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
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
import cz.riegel.arrivals.domain.PickerMode
import cz.riegel.arrivals.domain.model.Departure
import cz.riegel.arrivals.domain.model.Platform
import cz.riegel.arrivals.domain.model.Stop
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
            LaunchedEffect(Unit) {
                while (true) {
                    val nowMillis = System.currentTimeMillis()
                    val delayToNextMinute = 60_000L - (nowMillis % 60_000L) + 250L
                    delay(delayToNextMinute.coerceIn(1_000L, 60_500L))
                    WidgetStateManager.tickCountdowns(context)
                }
            }
            ArrivalsWidgetContent(state = state)
        }
    }
}

class ArrivalsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ArrivalsWidget()

    override fun onReceive(context: Context, intent: Intent) {
        WidgetCountdownTicker.ensureRegistered(context)
        if (intent.action == WidgetCountdownTicker.ACTION_TICK_COUNTDOWN) {
            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.Default).launch {
                try {
                    WidgetStateManager.tickCountdowns(context.applicationContext)
                } finally {
                    pendingResult.finish()
                }
            }
            return
        }
        super.onReceive(context, intent)
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        WidgetCountdownTicker.ensureRegistered(context)
        ArrivalsUpdateWorker.schedulePeriodicWork(context)
        ArrivalsUpdateWorker.enqueueOneTimeWork(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        WidgetCountdownTicker.cancelMinuteAlarm(context)
    }
}

// Widget action callbacks
val StopIndexKey = ActionParameters.Key<Int>("stop_index")
val PlatformCodeKey = ActionParameters.Key<String>("platform_code")

class OpenStopPickerActionCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        WidgetStateManager.onOpenStopPicker(context)
    }
}

class OpenPlatformPickerActionCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        WidgetStateManager.onOpenPlatformPicker(context)
    }
}

class ClosePickerActionCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        WidgetStateManager.onClosePicker(context)
    }
}

class SelectStopActionCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val stopIndex = parameters[StopIndexKey] ?: return
        WidgetStateManager.onSelectStop(context, stopIndex)
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

fun maxDeparturesForHeight(height: Dp): Int =
    if (height < 180.dp) {
        ArrivalsViewState.Content.COMPACT_MAX_DEPARTURES
    } else {
        ArrivalsViewState.Content.STANDARD_MAX_DEPARTURES
    }

@Composable
fun ArrivalsWidgetContent(state: ArrivalsViewState) {
    val context = LocalContext.current
    val size = LocalSize.current
    val darkBackground = Color(0xFF16151A)

    val isCompact = size.height < 180.dp

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

                    when (state.pickerMode) {
                        PickerMode.StopPicker -> {
                            PickerHeaderRow(state = state)
                            Spacer(modifier = GlanceModifier.height(8.dp))
                            StopPickerList(state = state)
                        }
                        PickerMode.PlatformPicker -> {
                            PickerHeaderRow(state = state)
                            Spacer(modifier = GlanceModifier.height(8.dp))
                            PlatformPickerList(state = state)
                        }
                        PickerMode.Departures -> {
                            // Unified single-row header: Stop name, active Platform pill, Distance badge & Refresh
                            HeaderRow(state = state)

                            Spacer(modifier = GlanceModifier.height(8.dp))

                            // Departures List (responsive compact vs standard)
                            val departures = state.visibleDepartures(isCompact = isCompact)
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
    }
}

@Composable
fun HeaderRow(state: ArrivalsViewState.Content) {
    val activeStop = state.activeStop
    val navCoords = state.activeNavigationCoordinates
    val mapIntent = Intent(
        Intent.ACTION_VIEW,
        Uri.parse("geo:${navCoords.lat},${navCoords.lon}?q=${Uri.encode(activeStop.name)}")
    )
    val mapAction = actionStartActivity(mapIntent)

    Row(
        modifier = GlanceModifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Stop Name (opens Stop Picker when multiple stops exist)
        val stopNameModifier = if (state.hasMultipleStops) {
            GlanceModifier.clickable(actionRunCallback<OpenStopPickerActionCallback>())
        } else {
            GlanceModifier
        }
        Text(
            text = state.headerStopNameText,
            modifier = stopNameModifier,
            style = TextStyle(
                color = androidx.glance.unit.ColorProvider(Color.White),
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp
            ),
            maxLines = 1
        )

        // Active Platform pill (when visible per multi/single platform rules)
        val pillText = state.headerPlatformPillText
        if (pillText != null) {
            val openPlatformPickerAction = actionRunCallback<OpenPlatformPickerActionCallback>()
            val pillBoxModifier = GlanceModifier
                .background(Color(0xFF2C2A34))
                .cornerRadius(8.dp)
                .padding(horizontal = 8.dp, vertical = 3.dp)
                .let { if (state.hasMultiplePlatforms) it.clickable(openPlatformPickerAction) else it }
            val pillTextModifier = if (state.hasMultiplePlatforms) {
                GlanceModifier.clickable(openPlatformPickerAction)
            } else {
                GlanceModifier
            }
            Spacer(modifier = GlanceModifier.width(6.dp))
            Box(
                modifier = pillBoxModifier,
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = pillText,
                    modifier = pillTextModifier,
                    style = TextStyle(
                        color = androidx.glance.unit.ColorProvider(Color.White),
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    ),
                    maxLines = 1
                )
            }
        }

        // Walking distance badge to the selected platform (falling back to stop distance), launches map intent
        val displayDistance = state.activeDistanceMeters
        if (displayDistance != null) {
            Spacer(modifier = GlanceModifier.width(6.dp))
            DistanceBadge(distanceMeters = displayDistance, action = mapAction)
        }

        Spacer(modifier = GlanceModifier.defaultWeight())

        RefreshButtonOrSpinner(isRefreshing = state.isRefreshing)
    }
}

@Composable
fun PickerHeaderRow(state: ArrivalsViewState.Content) {
    val closeAction = actionRunCallback<ClosePickerActionCallback>()
    Row(
        modifier = GlanceModifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = GlanceModifier
                .background(Color(0xFF2C2A34))
                .cornerRadius(10.dp)
                .padding(horizontal = 11.dp, vertical = 5.dp)
                .clickable(closeAction),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "←",
                modifier = GlanceModifier.clickable(closeAction),
                style = TextStyle(
                    color = androidx.glance.unit.ColorProvider(Color.White),
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp
                )
            )
        }

        Spacer(modifier = GlanceModifier.defaultWeight())

        RefreshButtonOrSpinner(isRefreshing = state.isRefreshing)
    }
}

@Composable
fun RefreshButtonOrSpinner(isRefreshing: Boolean) {
    if (isRefreshing) {
        Box(
            modifier = GlanceModifier
                .background(Color(0xFF2C2A34))
                .cornerRadius(10.dp)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(
                modifier = GlanceModifier.size(20.dp),
                color = androidx.glance.unit.ColorProvider(Color(0xFF81B0FF))
            )
        }
    } else {
        val refreshAction = actionRunCallback<RefreshActionCallback>()
        Box(
            modifier = GlanceModifier
                .background(Color(0xFF2C2A34))
                .cornerRadius(10.dp)
                .padding(horizontal = 11.dp, vertical = 5.dp)
                .clickable(refreshAction),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "↻",
                modifier = GlanceModifier.clickable(refreshAction),
                style = TextStyle(
                    color = androidx.glance.unit.ColorProvider(Color(0xFF81B0FF)),
                    fontWeight = FontWeight.Bold,
                    fontSize = 19.sp
                )
            )
        }
    }
}

@Composable
fun StopPickerList(state: ArrivalsViewState.Content) {
    LazyColumn(modifier = GlanceModifier.fillMaxSize()) {
        items(state.stopPickerRows) { row ->
            val rowBg = if (row.isSelected) Color(0xFF2C3548) else Color(0xFF1E1D24)
            val nameColor = if (row.isSelected) Color(0xFF81B0FF) else Color.White
            val selectAction = actionRunCallback<SelectStopActionCallback>(
                actionParametersOf(StopIndexKey to row.stopIndex)
            )

            Box(modifier = GlanceModifier.fillMaxWidth().padding(bottom = 4.dp)) {
                Row(
                    modifier = GlanceModifier
                        .fillMaxWidth()
                        .background(rowBg)
                        .cornerRadius(8.dp)
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                        .clickable(selectAction),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = row.name,
                        modifier = GlanceModifier.clickable(selectAction),
                        style = TextStyle(
                            color = androidx.glance.unit.ColorProvider(nameColor),
                            fontWeight = if (row.isSelected) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 14.sp
                        ),
                        maxLines = 1
                    )

                    if (row.transportModeSymbols.isNotEmpty()) {
                        Spacer(modifier = GlanceModifier.width(6.dp))
                        Text(
                            text = row.transportModeSymbols.joinToString(" "),
                            modifier = GlanceModifier.clickable(selectAction),
                            style = TextStyle(
                                color = androidx.glance.unit.ColorProvider(Color.LightGray),
                                fontSize = 12.sp
                            ),
                            maxLines = 1
                        )
                    }

                    Spacer(modifier = GlanceModifier.defaultWeight())

                    if (row.distanceMeters != null) {
                        DistanceBadge(distanceMeters = row.distanceMeters, action = selectAction)
                    }
                }
            }
        }
    }
}

@Composable
fun PlatformPickerList(state: ArrivalsViewState.Content) {
    val context = LocalContext.current
    val noDeparturesText = context.getString(R.string.no_departures)

    LazyColumn(modifier = GlanceModifier.fillMaxSize()) {
        items(state.platformPickerRows) { row ->
            val rowBg = if (row.isSelected) Color(0xFF2C3548) else Color(0xFF1E1D24)
            val pillBg = if (row.isSelected) Color.White else Color(0xFF2C2A34)
            val pillTextColor = if (row.isSelected) Color.Black else Color.White
            val terminusColor = if (row.terminusSummary != null) Color(0xFFE0E0E0) else Color.Gray
            val selectAction = actionRunCallback<SelectPlatformActionCallback>(
                actionParametersOf(PlatformCodeKey to row.code)
            )

            Box(modifier = GlanceModifier.fillMaxWidth().padding(bottom = 4.dp)) {
                Row(
                    modifier = GlanceModifier
                        .fillMaxWidth()
                        .background(rowBg)
                        .cornerRadius(8.dp)
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                        .clickable(selectAction),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = GlanceModifier
                            .background(pillBg)
                            .cornerRadius(8.dp)
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                            .clickable(selectAction),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = row.code,
                            modifier = GlanceModifier.clickable(selectAction),
                            style = TextStyle(
                                color = androidx.glance.unit.ColorProvider(pillTextColor),
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            ),
                            maxLines = 1
                        )
                    }

                    if (row.transportModeSymbols.isNotEmpty()) {
                        Spacer(modifier = GlanceModifier.width(6.dp))
                        Text(
                            text = row.transportModeSymbols.joinToString(" "),
                            modifier = GlanceModifier.clickable(selectAction),
                            style = TextStyle(
                                color = androidx.glance.unit.ColorProvider(Color.LightGray),
                                fontSize = 12.sp
                            ),
                            maxLines = 1
                        )
                    }

                    Spacer(modifier = GlanceModifier.width(6.dp))

                    Text(
                        text = row.displayTerminusText(noDeparturesText),
                        modifier = GlanceModifier.defaultWeight().clickable(selectAction),
                        style = TextStyle(
                            color = androidx.glance.unit.ColorProvider(terminusColor),
                            fontSize = 12.sp
                        ),
                        maxLines = 1
                    )

                    if (row.distanceMeters != null) {
                        Spacer(modifier = GlanceModifier.width(6.dp))
                        DistanceBadge(distanceMeters = row.distanceMeters, action = selectAction)
                    }
                }
            }
        }
    }
}

@Composable
fun DistanceBadge(distanceMeters: Int, action: Action) {
    Box(
        modifier = GlanceModifier
            .background(Color(0xFF2E2C36))
            .cornerRadius(8.dp)
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .clickable(action)
    ) {
        Text(
            text = "${distanceMeters}m",
            modifier = GlanceModifier.clickable(action),
            style = TextStyle(
                color = androidx.glance.unit.ColorProvider(Color(0xFFB0B0C0)),
                fontSize = 11.sp
            )
        )
    }
}

@Composable
fun DepartureRowItem(departure: Departure) {
    val modeSymbol = departure.line.transportMode.symbol

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

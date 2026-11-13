package cz.riegel.arrivals.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

object WidgetCountdownTicker {
    const val ACTION_TICK_COUNTDOWN = "cz.riegel.arrivals.action.TICK_COUNTDOWN"
    const val SCREEN_ON_THROTTLE_MS = 180_000L // 3 minutes
    private const val ALARM_REQUEST_CODE = 4201

    private val isRegistered = AtomicBoolean(false)
    internal var scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    internal var clock: () -> Long = { System.currentTimeMillis() }
    internal var lastScreenOnUpdateMillis: Long = 0L
    internal var lastJob: Job? = null

    suspend fun handleUserPresent(context: Context) {
        WidgetStateManager.tickCountdowns(context)
        scheduleNextMinuteAlarm(context)
        ArrivalsUpdateWorker.enqueueExpeditedOneTimeWork(context)
    }

    suspend fun handleScreenOn(context: Context) {
        WidgetStateManager.tickCountdowns(context)
        scheduleNextMinuteAlarm(context)
        val now = clock()
        if (lastScreenOnUpdateMillis == 0L || (now - lastScreenOnUpdateMillis) >= SCREEN_ON_THROTTLE_MS) {
            lastScreenOnUpdateMillis = now
            ArrivalsUpdateWorker.enqueueExpeditedOneTimeWork(context)
        }
    }

    suspend fun handleTimeTick(context: Context) {
        WidgetStateManager.tickCountdowns(context)
        scheduleNextMinuteAlarm(context)
    }

    internal val timeTickReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            val appCtx = context.applicationContext
            when (intent?.action) {
                Intent.ACTION_USER_PRESENT -> {
                    lastJob = scope.launch {
                        handleUserPresent(appCtx)
                    }
                }
                Intent.ACTION_SCREEN_ON -> {
                    lastJob = scope.launch {
                        handleScreenOn(appCtx)
                    }
                }
                else -> {
                    lastJob = scope.launch {
                        handleTimeTick(appCtx)
                    }
                }
            }
        }
    }

    fun ensureRegistered(context: Context) {
        val appCtx = context.applicationContext
        if (isRegistered.compareAndSet(false, true)) {
            try {
                val filter = IntentFilter().apply {
                    addAction(Intent.ACTION_TIME_TICK)
                    addAction(Intent.ACTION_TIME_CHANGED)
                    addAction(Intent.ACTION_TIMEZONE_CHANGED)
                    addAction(Intent.ACTION_SCREEN_ON)
                    addAction(Intent.ACTION_USER_PRESENT)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    appCtx.registerReceiver(timeTickReceiver, filter, Context.RECEIVER_EXPORTED)
                } else {
                    appCtx.registerReceiver(timeTickReceiver, filter)
                }
            } catch (_: Exception) {
                isRegistered.set(false)
            }
        }
        scheduleNextMinuteAlarm(appCtx)
    }

    fun unregister(context: Context) {
        val appCtx = context.applicationContext
        if (isRegistered.compareAndSet(true, false)) {
            try {
                appCtx.unregisterReceiver(timeTickReceiver)
            } catch (_: Exception) {}
        }
        cancelMinuteAlarm(appCtx)
    }

    fun resetForTesting(context: Context? = null) {
        if (context != null && isRegistered.get()) {
            try {
                context.applicationContext.unregisterReceiver(timeTickReceiver)
            } catch (_: Exception) {}
        }
        isRegistered.set(false)
        lastScreenOnUpdateMillis = 0L
        clock = { System.currentTimeMillis() }
        lastJob = null
    }

    fun scheduleNextMinuteAlarm(context: Context) {
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val intent = Intent(context, ArrivalsWidgetReceiver::class.java).apply {
                action = ACTION_TICK_COUNTDOWN
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                ALARM_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val nowMillis = System.currentTimeMillis()
            val nextMinuteMillis = (nowMillis / 60_000L + 1L) * 60_000L + 500L
            // Non-wakeup RTC window alarm so we don't wake the CPU when the screen is off
            alarmManager.setWindow(
                AlarmManager.RTC,
                nextMinuteMillis,
                5_000L,
                pendingIntent
            )
        } catch (_: Exception) {
            // Ignore in test environments
        }
    }

    fun cancelMinuteAlarm(context: Context) {
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val intent = Intent(context, ArrivalsWidgetReceiver::class.java).apply {
                action = ACTION_TICK_COUNTDOWN
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                ALARM_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
            if (pendingIntent != null) {
                alarmManager.cancel(pendingIntent)
            }
        } catch (_: Exception) {
        }
    }
}

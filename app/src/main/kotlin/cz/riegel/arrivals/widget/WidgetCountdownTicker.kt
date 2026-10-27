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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

object WidgetCountdownTicker {
    const val ACTION_TICK_COUNTDOWN = "cz.riegel.arrivals.action.TICK_COUNTDOWN"
    private const val ALARM_REQUEST_CODE = 4201

    private val isRegistered = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val timeTickReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            val appCtx = context.applicationContext
            scope.launch {
                WidgetStateManager.tickCountdowns(appCtx)
                scheduleNextMinuteAlarm(appCtx)
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
                    appCtx.registerReceiver(timeTickReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    appCtx.registerReceiver(timeTickReceiver, filter)
                }
            } catch (_: Exception) {
                isRegistered.set(false)
            }
        }
        scheduleNextMinuteAlarm(appCtx)
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

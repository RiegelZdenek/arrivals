package cz.riegel.arrivals.widget

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import cz.riegel.arrivals.domain.ArrivalsViewState
import cz.riegel.arrivals.domain.model.Coordinates
import cz.riegel.arrivals.domain.model.CountdownDisplay
import cz.riegel.arrivals.domain.model.Delay
import cz.riegel.arrivals.domain.model.Departure
import cz.riegel.arrivals.domain.model.Line
import cz.riegel.arrivals.domain.model.Platform
import cz.riegel.arrivals.domain.model.Stop
import cz.riegel.arrivals.domain.model.TransportMode
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScreenUnlockBroadcastTriggerTest {

    private lateinit var context: Context

    private fun sampleDeparture(predictedTime: Instant) = Departure(
        id = "dep_test",
        line = Line("22", TransportMode.Tram),
        terminus = "Bílá Hora",
        scheduledTime = predictedTime,
        predictedTime = predictedTime,
        countdown = CountdownDisplay(5, "5 min", false),
        delay = Delay(0, false)
    )

    private fun sampleContentState(predictedTime: Instant): ArrivalsViewState.Content {
        val platform = Platform(
            id = "s1_A",
            code = "A",
            departures = listOf(sampleDeparture(predictedTime))
        )
        val stop = Stop(
            id = "s1",
            name = "I. P. Pavlova",
            lat = 50.075,
            lon = 14.430,
            platforms = listOf(platform)
        )
        return ArrivalsViewState.Content(
            stops = listOf(stop),
            activeStopIndex = 0,
            activePlatformCode = "A",
            userCoordinates = Coordinates(50.075, 14.430)
        )
    }

    private suspend fun sendBroadcastAndAwait(intent: Intent) {
        context.sendBroadcast(intent)
        ShadowLooper.idleMainLooper()
        WidgetCountdownTicker.lastJob?.join()
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        ArrivalsUpdateWorker.oneTimeWorkEnqueuedCount = 0
        ArrivalsUpdateWorker.lastEnqueuedWorkRequest = null
        WidgetCountdownTicker.resetForTesting(context)
        WidgetCountdownTicker.ensureRegistered(context)
    }

    @After
    fun tearDown() {
        WidgetCountdownTicker.resetForTesting(context)
    }

    @Test
    fun userPresent_triggersImmediateExpeditedUpdateWork_andTicksCountdowns() = runBlocking {
        val nowInstant = Instant.now()
        var currentTimeMillis = nowInstant.toEpochMilli()
        WidgetCountdownTicker.clock = { currentTimeMillis }
        val targetDepartureTime = nowInstant.plusSeconds(300) // 5 min future
        WidgetStateManager.setState(context, sampleContentState(targetDepartureTime))

        // Broadcast ACTION_USER_PRESENT (device unlocked)
        sendBroadcastAndAwait(Intent(Intent.ACTION_USER_PRESENT))

        // Verify expedited work is enqueued immediately
        assertEquals("ACTION_USER_PRESENT must enqueue expedited update work", 1, ArrivalsUpdateWorker.oneTimeWorkEnqueuedCount)
        assertNotNull("Work request must not be null", ArrivalsUpdateWorker.lastEnqueuedWorkRequest)
        assertTrue("Work request must be expedited", ArrivalsUpdateWorker.lastEnqueuedWorkRequest!!.workSpec.expedited)

        // Verify countdown is ticked
        val state = WidgetStateManager.getState(context, nowInstant) as ArrivalsViewState.Content
        assertEquals("5 min", state.activePlatform!!.departures.first().countdown.displayText)
    }

    @Test
    fun screenOn_firstWakeup_triggersExpeditedUpdateWork_andTicksCountdowns() = runBlocking {
        var currentTimeMillis = 1_000_000L
        WidgetCountdownTicker.clock = { currentTimeMillis }
        val targetDepartureTime = Instant.ofEpochMilli(currentTimeMillis + 4 * 60_000L)
        WidgetStateManager.setState(context, sampleContentState(targetDepartureTime))

        // First ACTION_SCREEN_ON
        sendBroadcastAndAwait(Intent(Intent.ACTION_SCREEN_ON))

        assertEquals("First ACTION_SCREEN_ON must enqueue expedited update work", 1, ArrivalsUpdateWorker.oneTimeWorkEnqueuedCount)
        assertNotNull(ArrivalsUpdateWorker.lastEnqueuedWorkRequest)
        assertTrue(ArrivalsUpdateWorker.lastEnqueuedWorkRequest!!.workSpec.expedited)
    }

    @Test
    fun screenOn_enforcesThreeMinuteCooldown_ticksCountdownsWithoutEnqueuingWork() = runBlocking {
        var currentTimeMillis = 1_000_000L
        WidgetCountdownTicker.clock = { currentTimeMillis }
        val targetDepartureTime = Instant.ofEpochMilli(currentTimeMillis + 10 * 60_000L)
        WidgetStateManager.setState(context, sampleContentState(targetDepartureTime))

        // First screen on triggers update work
        sendBroadcastAndAwait(Intent(Intent.ACTION_SCREEN_ON))
        assertEquals(1, ArrivalsUpdateWorker.oneTimeWorkEnqueuedCount)

        // Screen turns on again 1 minute later (within 3 minute cooldown)
        currentTimeMillis += 60_000L
        sendBroadcastAndAwait(Intent(Intent.ACTION_SCREEN_ON))

        // Still 1 (rate-limited!)
        assertEquals("ACTION_SCREEN_ON within 3 minutes must NOT trigger update work", 1, ArrivalsUpdateWorker.oneTimeWorkEnqueuedCount)

        // Screen turns on 2 minutes 59 seconds after the first (still within cooldown: 179s elapsed)
        currentTimeMillis = 1_000_000L + 179_000L
        sendBroadcastAndAwait(Intent(Intent.ACTION_SCREEN_ON))
        assertEquals("ACTION_SCREEN_ON at 179s must NOT trigger update work", 1, ArrivalsUpdateWorker.oneTimeWorkEnqueuedCount)

        // Screen turns on exactly 3 minutes (180s) after first screen on -> cooldown elapsed!
        currentTimeMillis = 1_000_000L + 180_000L
        sendBroadcastAndAwait(Intent(Intent.ACTION_SCREEN_ON))
        assertEquals("ACTION_SCREEN_ON after 3 minutes MUST trigger expedited update work", 2, ArrivalsUpdateWorker.oneTimeWorkEnqueuedCount)
    }

    @Test
    fun userPresent_ignoresCooldown_alwaysTriggersImmediately() = runBlocking {
        var currentTimeMillis = 1_000_000L
        WidgetCountdownTicker.clock = { currentTimeMillis }
        WidgetStateManager.setState(context, sampleContentState(Instant.ofEpochMilli(currentTimeMillis + 300_000L)))

        // Screen turns on -> 1 update work enqueued
        sendBroadcastAndAwait(Intent(Intent.ACTION_SCREEN_ON))
        assertEquals(1, ArrivalsUpdateWorker.oneTimeWorkEnqueuedCount)

        // 2 seconds later user unlocks -> MUST trigger immediately regardless of recent screen on
        currentTimeMillis += 2_000L
        sendBroadcastAndAwait(Intent(Intent.ACTION_USER_PRESENT))
        assertEquals("ACTION_USER_PRESENT must trigger immediately even right after screen on", 2, ArrivalsUpdateWorker.oneTimeWorkEnqueuedCount)

        // Another unlock 5 seconds later -> MUST trigger immediately again
        currentTimeMillis += 5_000L
        sendBroadcastAndAwait(Intent(Intent.ACTION_USER_PRESENT))
        assertEquals("ACTION_USER_PRESENT must trigger on every unlock", 3, ArrivalsUpdateWorker.oneTimeWorkEnqueuedCount)
    }

    @Test
    fun minuteTicks_performStrictlyLocalCountdownTicks_withoutTriggeringUpdateWork() = runBlocking {
        val currentTimeMillis = 1_000_000L
        val targetDepartureTime = Instant.ofEpochMilli(currentTimeMillis + 5 * 60_000L)
        WidgetStateManager.setState(context, sampleContentState(targetDepartureTime))

        // Time tick broadcast
        sendBroadcastAndAwait(Intent(Intent.ACTION_TIME_TICK))

        // Countdown tick alarm action
        sendBroadcastAndAwait(Intent(context, ArrivalsWidgetReceiver::class.java).apply {
            action = WidgetCountdownTicker.ACTION_TICK_COUNTDOWN
        })

        assertEquals("Minute ticks must NOT enqueue update work", 0, ArrivalsUpdateWorker.oneTimeWorkEnqueuedCount)
    }

    @Test
    fun arrivalsWidgetReceiver_directDispatch_handlesUserPresentAndScreenOn() = runBlocking {
        var currentTimeMillis = 1_000_000L
        WidgetCountdownTicker.clock = { currentTimeMillis }
        WidgetStateManager.setState(context, sampleContentState(Instant.ofEpochMilli(currentTimeMillis + 300_000L)))

        val receiver = ArrivalsWidgetReceiver()

        // Directly invoke onReceive with ACTION_USER_PRESENT
        receiver.onReceive(context, Intent(Intent.ACTION_USER_PRESENT))
        WidgetCountdownTicker.lastJob?.join()
        assertEquals("ArrivalsWidgetReceiver must handle ACTION_USER_PRESENT", 1, ArrivalsUpdateWorker.oneTimeWorkEnqueuedCount)

        // Directly invoke onReceive with ACTION_SCREEN_ON (after cooldown)
        currentTimeMillis += 200_000L
        receiver.onReceive(context, Intent(Intent.ACTION_SCREEN_ON))
        WidgetCountdownTicker.lastJob?.join()
        assertEquals("ArrivalsWidgetReceiver must handle ACTION_SCREEN_ON", 2, ArrivalsUpdateWorker.oneTimeWorkEnqueuedCount)
    }
}

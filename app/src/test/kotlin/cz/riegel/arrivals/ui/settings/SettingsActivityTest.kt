package cz.riegel.arrivals.ui.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import cz.riegel.arrivals.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import android.location.Location
import cz.riegel.arrivals.data.DeparturesRepository
import cz.riegel.arrivals.data.LocationResolver
import cz.riegel.arrivals.domain.model.Coordinates
import java.time.Instant
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsActivityTest {

    @Test
    fun manifestDeclaresBackgroundLocationAndForegroundServicePermissions() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val packageManager = context.packageManager
        val packageInfo = packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS
        )
        val permissions = packageInfo.requestedPermissions?.toList() ?: emptyList()

        assertTrue(
            "Manifest must declare ACCESS_BACKGROUND_LOCATION",
            permissions.contains(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        )
        assertTrue(
            "Manifest must declare FOREGROUND_SERVICE",
            permissions.contains("android.permission.FOREGROUND_SERVICE")
        )
        assertTrue(
            "Manifest must declare FOREGROUND_SERVICE_LOCATION",
            permissions.contains("android.permission.FOREGROUND_SERVICE_LOCATION")
        )
    }

    @Test
    @Config(sdk = [34])
    fun educationalPromptShownWhenBackgroundLocationNotGrantedOnSdk34() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val shadowApp = shadowOf(app as android.app.Application)
        shadowApp.grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        shadowApp.denyPermissions(Manifest.permission.ACCESS_BACKGROUND_LOCATION)

        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val activity = controller.get()

        val prompt = activity.findViewById<View>(R.id.layoutBackgroundLocationEducation)
        val promptTitle = activity.findViewById<TextView>(R.id.textBackgroundLocationTitle)
        val promptExplanation = activity.findViewById<TextView>(R.id.textBackgroundLocationExplanation)
        val btnOpenSettings = activity.findViewById<Button>(R.id.btnOpenLocationSettings)

        assertEquals("Prompt should be visible when background location is not granted", View.VISIBLE, prompt.visibility)
        assertTrue(promptTitle.text.isNotEmpty())
        assertTrue(promptExplanation.text.isNotEmpty())
        assertEquals(View.VISIBLE, btnOpenSettings.visibility)
    }

    @Test
    @Config(sdk = [34])
    fun educationalPromptHiddenWhenBackgroundLocationGrantedOnSdk34() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val shadowApp = shadowOf(app as android.app.Application)
        shadowApp.grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION
        )

        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val activity = controller.get()

        val prompt = activity.findViewById<View>(R.id.layoutBackgroundLocationEducation)
        assertEquals("Prompt should be hidden when background location is granted", View.GONE, prompt.visibility)
    }

    @Test
    @Config(sdk = [34])
    fun tappingOpenSettingsButtonLaunchesApplicationDetailsSettings() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val shadowApp = shadowOf(app as android.app.Application)
        shadowApp.grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        shadowApp.denyPermissions(Manifest.permission.ACCESS_BACKGROUND_LOCATION)

        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val activity = controller.get()

        val btnOpenSettings = activity.findViewById<Button>(R.id.btnOpenLocationSettings)
        btnOpenSettings.performClick()

        val shadowActivity = shadowOf(activity)
        val intent = shadowActivity.nextStartedActivity
        assertNotNull("Clicking open settings should launch an intent", intent)
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intent.action)
        assertEquals(Uri.parse("package:${activity.packageName}"), intent.data)
    }

    @Test
    @Config(sdk = [34])
    fun tappingPromptCardLaunchesApplicationDetailsSettings() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val shadowApp = shadowOf(app as android.app.Application)
        shadowApp.grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        shadowApp.denyPermissions(Manifest.permission.ACCESS_BACKGROUND_LOCATION)

        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val activity = controller.get()

        val promptCard = activity.findViewById<View>(R.id.layoutBackgroundLocationEducation)
        promptCard.performClick()

        val shadowActivity = shadowOf(activity)
        val intent = shadowActivity.nextStartedActivity
        assertNotNull("Clicking prompt card should launch an intent", intent)
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intent.action)
        assertEquals(Uri.parse("package:${activity.packageName}"), intent.data)
    }

    @Test
    @Config(sdk = [28])
    fun educationalPromptHiddenOnSdk28WhenFineLocationGranted() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val shadowApp = shadowOf(app as android.app.Application)
        shadowApp.grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)

        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val activity = controller.get()

        val prompt = activity.findViewById<View>(R.id.layoutBackgroundLocationEducation)
        assertEquals(
            "On SDK < 29, coarse/fine permission is sufficient and prompt should be GONE",
            View.GONE,
            prompt.visibility
        )
    }

    @Test
    @Config(sdk = [34])
    fun promptUpdatesOnResumeWhenPermissionGrantedInSettings() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val shadowApp = shadowOf(app as android.app.Application)
        shadowApp.grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        shadowApp.denyPermissions(Manifest.permission.ACCESS_BACKGROUND_LOCATION)

        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val activity = controller.get()

        val prompt = activity.findViewById<View>(R.id.layoutBackgroundLocationEducation)
        assertEquals(View.VISIBLE, prompt.visibility)

        // User opens system settings, grants background permission, and returns
        shadowApp.grantPermissions(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        controller.pause().resume()

        assertEquals(
            "Prompt should update to GONE after permission granted and activity resumed",
            View.GONE,
            prompt.visibility
        )
    }

    @Test
    @Config(sdk = [34])
    fun openingSettingsActivity_immediatelyDisplaysLastKnownLocationFromRepository() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val shadowApp = shadowOf(app as android.app.Application)
        shadowApp.grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)

        val pragueCoords = Coordinates(lat = 50.0755, lon = 14.4302)
        DeparturesRepository.create(app).saveLocation(pragueCoords, Instant.now())

        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val activity = controller.get()

        val textLocationStatus = activity.findViewById<TextView>(R.id.textLocationStatus)
        val progressLocation = activity.findViewById<View>(R.id.progressLocation)
        val btnRefreshLocation = activity.findViewById<Button>(R.id.btnRefreshLocation)

        assertEquals("Progress spinner should not be visible when cached location is present", View.GONE, progressLocation.visibility)
        assertTrue("Refresh button should be enabled", btnRefreshLocation.isEnabled)
        assertTrue("Location status should display coordinates immediately", textLocationStatus.text.contains("50.0755"))
        assertFalse("Should not display progress text", textLocationStatus.text.contains("Zjišťování"))
    }

    @Test
    @Config(sdk = [34])
    fun manualRefreshLocation_callsResolverWith10SecondsTimeout() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val shadowApp = shadowOf(app as android.app.Application)
        shadowApp.grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)

        var requestedTimeout: Long? = null
        val fakeResolver = object : LocationResolver {
            override suspend fun resolveLocation(highAccuracyTimeoutMs: Long, balancedTimeoutMs: Long): Location? {
                requestedTimeout = highAccuracyTimeoutMs
                return Location("gps").apply {
                    latitude = 50.0755
                    longitude = 14.4302
                    time = System.currentTimeMillis()
                }
            }
            override suspend fun getLastKnownLocation(): Location? = null
        }

        val controller = Robolectric.buildActivity(SettingsActivity::class.java)
        val activity = controller.get()
        activity.locationResolver = fakeResolver
        controller.setup()

        val btnRefresh = activity.findViewById<Button>(R.id.btnRefreshLocation)
        btnRefresh.performClick()

        assertEquals("Manual refresh must pass 10-second timeout for satellite Location Fix", 10_000L, requestedTimeout)
    }
}

package cz.riegel.arrivals

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppVersionTest {
    @Test
    fun packageInfo_containsValidVersion() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        assertNotNull(packageInfo.versionName)
        assertTrue("versionName should not be empty", !packageInfo.versionName.isNullOrEmpty())
        @Suppress("DEPRECATION")
        assertTrue("versionCode should be positive", packageInfo.versionCode > 0)
    }
}

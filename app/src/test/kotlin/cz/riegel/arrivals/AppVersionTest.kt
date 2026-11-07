package cz.riegel.arrivals

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppVersionTest {
    @Test
    fun packageInfo_matchesDefaultVersion() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        assertEquals("0.1.0-beta", packageInfo.versionName)
        @Suppress("DEPRECATION")
        assertEquals(1, packageInfo.versionCode)
    }
}

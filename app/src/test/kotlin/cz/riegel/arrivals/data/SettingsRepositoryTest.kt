package cz.riegel.arrivals.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cz.riegel.arrivals.data.model.GolemioDepartureBoardResponse
import cz.riegel.arrivals.data.model.GolemioException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsRepositoryTest {

    private lateinit var context: Context
    private lateinit var settingsRepository: SettingsRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        settingsRepository = SettingsRepository(context)
        settingsRepository.clear()
    }

    @Test
    fun saveAndGetApiKey_persistsKeyCorrectly() {
        assertNull(settingsRepository.getApiKey())
        assertFalse(settingsRepository.isConfigured())

        settingsRepository.saveApiKey("secret-golemio-token")

        assertEquals("secret-golemio-token", settingsRepository.getApiKey())
        assertTrue(settingsRepository.isConfigured())
    }

    @Test
    fun searchRadius_defaultsTo300AndCanBeUpdated() {
        assertEquals(300, settingsRepository.getSearchRadius())

        settingsRepository.saveSearchRadius(500)
        assertEquals(500, settingsRepository.getSearchRadius())
    }

    @Test
    fun refreshInterval_defaultsTo15AndCanBeUpdated() {
        assertEquals(15L, settingsRepository.getRefreshIntervalMinutes())

        settingsRepository.saveRefreshIntervalMinutes(30L)
        assertEquals(30L, settingsRepository.getRefreshIntervalMinutes())
    }

    @Test
    fun validateApiKey_failsOnBlankKey() = runBlocking {
        val result = settingsRepository.validateApiKey("   ")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is GolemioException.MissingApiKey)
    }
}

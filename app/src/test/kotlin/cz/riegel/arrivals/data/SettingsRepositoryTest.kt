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
        val fakeDataSource = FakeTransitDataSource()
        val result = settingsRepository.validateApiKey("   ") { fakeDataSource }
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is GolemioException.MissingApiKey)
        assertTrue(fakeDataSource.stopsCalls.isEmpty())
    }

    @Test
    fun validateApiKey_queriesHlavniNadraziThroughTransitDataSource_andSucceedsWhenValid() = runBlocking {
        val fakeDataSource = FakeTransitDataSource()
        fakeDataSource.setResponseForStop("Hlavní nádraží", Result.success(GolemioDepartureBoardResponse()))
        var passedToken: String? = null

        val result = settingsRepository.validateApiKey("valid-token") { token ->
            passedToken = token
            fakeDataSource
        }

        assertTrue(result.isSuccess)
        assertEquals(true, result.getOrNull())
        assertEquals("valid-token", passedToken)
        assertEquals(1, fakeDataSource.stopsCalls.size)
        assertEquals(listOf("Hlavní nádraží"), fakeDataSource.stopsCalls.first().stopNames)
        assertEquals(1, fakeDataSource.stopsCalls.first().limit)
    }

    @Test
    fun validateApiKey_propagatesUnauthorizedFailureFromTransitDataSource() = runBlocking {
        val fakeDataSource = FakeTransitDataSource()
        fakeDataSource.setResponseForStop("Hlavní nádraží", Result.failure(GolemioException.Unauthorized))

        val result = settingsRepository.validateApiKey("bad-token") { fakeDataSource }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is GolemioException.Unauthorized)
    }
}

package cz.riegel.arrivals.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import cz.riegel.arrivals.data.model.GolemioException

class SettingsRepository(context: Context) {

    private val prefs: SharedPreferences = createEncryptedSharedPreferences(context)

    fun getApiKey(): String? {
        val key = prefs.getString(KEY_API_TOKEN, null)
        return if (key.isNullOrBlank()) null else key.trim()
    }

    fun saveApiKey(key: String) {
        prefs.edit().putString(KEY_API_TOKEN, key.trim()).apply()
    }

    fun isConfigured(): Boolean {
        return !getApiKey().isNullOrBlank()
    }

    fun getSearchRadius(): Int {
        return prefs.getInt(KEY_SEARCH_RADIUS, DEFAULT_SEARCH_RADIUS)
    }

    fun saveSearchRadius(radiusMeters: Int) {
        val clamped = radiusMeters.coerceIn(100, 500)
        prefs.edit().putInt(KEY_SEARCH_RADIUS, clamped).apply()
    }

    fun getRefreshIntervalMinutes(): Long {
        return prefs.getLong(KEY_REFRESH_INTERVAL, DEFAULT_REFRESH_INTERVAL)
    }

    fun saveRefreshIntervalMinutes(intervalMinutes: Long) {
        val clamped = intervalMinutes.coerceIn(15, 60)
        prefs.edit().putLong(KEY_REFRESH_INTERVAL, clamped).apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    suspend fun validateApiKey(key: String): Result<Boolean> {
        if (key.isBlank()) {
            return Result.failure(GolemioException.MissingApiKey)
        }
        val testClient = GolemioApiClient(apiKeyProvider = { key })
        // Test query against Golemio with a well-known Prague stop
        val result = testClient.getDeparturesByNames(listOf("Hlavní nádraží"), limit = 1)
        return if (result.isSuccess) {
            Result.success(true)
        } else {
            val err = result.exceptionOrNull() ?: Exception("Unknown error")
            Result.failure(err)
        }
    }

    private fun createEncryptedSharedPreferences(context: Context): SharedPreferences {
        return try {
            val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            EncryptedSharedPreferences.create(
                PREFS_FILENAME,
                masterKeyAlias,
                context,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            context.getSharedPreferences(PREFS_FILENAME + "_fallback", Context.MODE_PRIVATE)
        }
    }

    companion object {
        private const val PREFS_FILENAME = "arrivals_secure_prefs"
        private const val KEY_API_TOKEN = "golemio_api_token"
        private const val KEY_SEARCH_RADIUS = "search_radius_meters"
        private const val KEY_REFRESH_INTERVAL = "refresh_interval_minutes"
        const val DEFAULT_SEARCH_RADIUS = 300
        const val DEFAULT_REFRESH_INTERVAL = 15L
    }
}

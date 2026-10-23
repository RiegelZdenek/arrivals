package cz.riegel.arrivals.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import cz.riegel.arrivals.data.model.GolemioException
import okhttp3.OkHttpClient

interface ApiValidator {
    suspend fun testKey(key: String): Result<Boolean>
}

class GolemioApiValidator(
    private val client: GolemioApiClient = GolemioApiClient(apiKeyProvider = { null })
) : ApiValidator {
    override suspend fun testKey(key: String): Result<Boolean> {
        val testClient = GolemioApiClient(
            apiKeyProvider = { key }
        )
        // Ping Prague center (Wenceslas Square: 50.0813, 14.4280) with limit 1
        val result = testClient.getDepartures(lat = 50.0813, lon = 14.4280, radiusMeters = 300, limit = 1)
        return if (result.isSuccess) {
            Result.success(true)
        } else {
            val err = result.exceptionOrNull() ?: Exception("Unknown error")
            Result.failure(err)
        }
    }
}

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

    fun clear() {
        prefs.edit().clear().apply()
    }

    suspend fun validateApiKey(
        key: String,
        validator: ApiValidator = GolemioApiValidator()
    ): Result<Boolean> {
        if (key.isBlank()) {
            return Result.failure(GolemioException.MissingApiKey)
        }
        return validator.testKey(key)
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
            // Fallback for Robolectric or environments lacking Android KeyStore
            context.getSharedPreferences(PREFS_FILENAME + "_fallback", Context.MODE_PRIVATE)
        }
    }

    companion object {
        private const val PREFS_FILENAME = "arrivals_secure_prefs"
        private const val KEY_API_TOKEN = "golemio_api_token"
        private const val KEY_SEARCH_RADIUS = "search_radius_meters"
        const val DEFAULT_SEARCH_RADIUS = 300
    }
}

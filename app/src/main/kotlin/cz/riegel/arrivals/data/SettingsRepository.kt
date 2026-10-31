package cz.riegel.arrivals.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import cz.riegel.arrivals.data.model.GolemioException

data class SimulationHub(val name: String, val lat: Double, val lon: Double)

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

    fun getSimulatedHubIndex(): Int {
        return prefs.getInt(KEY_SIMULATED_HUB_INDEX, 0)
    }

    fun saveSimulatedHubIndex(index: Int) {
        prefs.edit().putInt(KEY_SIMULATED_HUB_INDEX, index).apply()
    }

    fun getSelectedSimulationHub(): SimulationHub? {
        val index = getSimulatedHubIndex()
        return if (index in 1 until SIMULATION_HUBS.size) SIMULATION_HUBS[index] else null
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    suspend fun validateApiKey(
        key: String,
        transitDataSourceFactory: (String) -> TransitDataSource = { token ->
            GolemioApiClient(apiKeyProvider = { token })
        }
    ): Result<Boolean> {
        val trimmed = key.trim()
        if (trimmed.isBlank()) {
            return Result.failure(GolemioException.MissingApiKey)
        }
        val dataSource = transitDataSourceFactory(trimmed)
        val result = dataSource.fetchDeparturesForStops(listOf("Hlavní nádraží"), limit = 1)
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
        private const val KEY_SIMULATED_HUB_INDEX = "simulated_hub_index"
        const val DEFAULT_SEARCH_RADIUS = 300
        const val DEFAULT_REFRESH_INTERVAL = 15L

        val SIMULATION_HUBS = listOf(
            SimulationHub("Automatická GPS poloha (Real GPS)", 0.0, 0.0),
            SimulationHub("Hlavní nádraží", 50.0831, 14.4352),
            SimulationHub("Můstek", 50.0841, 14.4243),
            SimulationHub("Anděl", 50.0711, 14.4038),
            SimulationHub("I. P. Pavlova", 50.0754, 14.4301),
            SimulationHub("Florenc", 50.0906, 14.4395),
            SimulationHub("Staroměstská", 50.0875, 14.4172),
            SimulationHub("Karlovo náměstí", 50.0760, 14.4200),
            SimulationHub("Náměstí Míru", 50.0755, 14.4373)
        )
    }
}

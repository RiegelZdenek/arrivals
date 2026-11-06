package cz.riegel.arrivals.data

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

interface LocationResolver {
    suspend fun resolveLocation(
        highAccuracyTimeoutMs: Long = DEFAULT_HIGH_ACCURACY_TIMEOUT_MS,
        balancedTimeoutMs: Long = DEFAULT_BALANCED_TIMEOUT_MS
    ): Location?

    suspend fun getLastKnownLocation(): Location?

    companion object {
        const val DEFAULT_HIGH_ACCURACY_TIMEOUT_MS = 4_000L
        const val MANUAL_REFRESH_HIGH_ACCURACY_TIMEOUT_MS = 10_000L
        const val DEFAULT_BALANCED_TIMEOUT_MS = 3_000L

        fun create(context: Context): LocationResolver = DefaultLocationResolver(context.applicationContext)
    }
}

class DefaultLocationResolver(
    private val context: Context,
    private val fusedClient: FusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(context),
    private val locationManager: LocationManager? = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager,
    private val queryHighAccuracy: suspend (timeoutMs: Long) -> Location? = { timeoutMs ->
        val cts = CancellationTokenSource()
        try {
            withTimeoutOrNull(timeoutMs) {
                fusedClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token).await()
            }
        } finally {
            cts.cancel()
        }
    },
    private val queryBalancedPower: suspend (timeoutMs: Long) -> Location? = { timeoutMs ->
        val cts = CancellationTokenSource()
        try {
            withTimeoutOrNull(timeoutMs) {
                fusedClient.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, cts.token).await()
            }
        } finally {
            cts.cancel()
        }
    },
    private val queryFusedLastLocation: suspend () -> Location? = {
        fusedClient.lastLocation.await()
    },
    private val queryLocationManager: () -> Location? = {
        getLocationFromLocationManager(locationManager)
    }
) : LocationResolver {

    override suspend fun resolveLocation(
        highAccuracyTimeoutMs: Long,
        balancedTimeoutMs: Long
    ): Location? {
        // Tier 1: Query high-accuracy GPS with timeout
        try {
            val tier1 = queryHighAccuracy(highAccuracyTimeoutMs)
            if (tier1 != null) {
                return tier1
            }
        } catch (e: Exception) {
            if (e is CancellationException && e !is TimeoutCancellationException) throw e
        }

        // Tier 2: Query balanced power accuracy (Wi-Fi & cell triangulation) with timeout
        try {
            val tier2 = queryBalancedPower(balancedTimeoutMs)
            if (tier2 != null) {
                return tier2
            }
        } catch (e: Exception) {
            if (e is CancellationException && e !is TimeoutCancellationException) throw e
        }

        // Tier 3 & 4: Fall back to last known location (Fused lastLocation then LocationManager)
        return getLastKnownLocation()
    }

    override suspend fun getLastKnownLocation(): Location? {
        // Tier 3: Query Fused Location Provider lastLocation
        try {
            val tier3 = queryFusedLastLocation()
            if (tier3 != null) {
                return tier3
            }
        } catch (e: Exception) {
            if (e is CancellationException && e !is TimeoutCancellationException) throw e
        }

        // Tier 4: Fall back to LocationManager providers
        try {
            val tier4 = queryLocationManager()
            if (tier4 != null) {
                return tier4
            }
        } catch (e: Exception) {
            if (e is CancellationException && e !is TimeoutCancellationException) throw e
        }

        return null
    }

    companion object {
        @SuppressLint("MissingPermission")
        fun getLocationFromLocationManager(lm: LocationManager?): Location? {
            if (lm == null) return null
            return try {
                val providers = listOf(
                    LocationManager.GPS_PROVIDER,
                    LocationManager.NETWORK_PROVIDER,
                    LocationManager.PASSIVE_PROVIDER
                )
                providers.mapNotNull { provider ->
                    try {
                        if (lm.isProviderEnabled(provider)) lm.getLastKnownLocation(provider) else null
                    } catch (_: Exception) {
                        null
                    }
                }.maxByOrNull { it.time }
            } catch (_: Exception) {
                null
            }
        }
    }
}

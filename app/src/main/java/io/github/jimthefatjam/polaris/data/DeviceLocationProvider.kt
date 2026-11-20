package io.github.jimthefatjam.polaris.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Looper
import androidx.core.content.ContextCompat
import io.github.jimthefatjam.polaris.core.model.GeoLocation
import io.github.jimthefatjam.polaris.core.model.LocationSource
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/**
 * Obtains an approximate position with the platform LocationManager (no Google Play Services
 * dependency, so it also works on de-Googled devices). Only ACCESS_COARSE_LOCATION is requested:
 * ~3 km precision is more than enough for weather and sky positions.
 */
class DeviceLocationProvider(private val context: Context) {
    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val executor = Executors.newSingleThreadExecutor()

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun isLocationEnabled(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) lm.isLocationEnabled
        else lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) || lm.isProviderEnabled(LocationManager.GPS_PROVIDER)

    /** Current location, or the best recent last-known fix, or null. Never throws for missing permission. */
    suspend fun currentLocation(timeoutMs: Long = 15_000): GeoLocation? {
        if (!hasPermission()) return null
        val fresh = withTimeoutOrNull(timeoutMs) { requestSingle() }
        return (fresh ?: lastKnown())?.toGeo()
    }

    private fun candidateProviders(): List<String> {
        val enabled = runCatching { lm.getProviders(true) }.getOrDefault(emptyList())
        val order = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            add(LocationManager.GPS_PROVIDER)
            add(LocationManager.PASSIVE_PROVIDER)
        }
        return order.filter { it in enabled }
    }

    @SuppressLint("MissingPermission")
    private suspend fun requestSingle(): Location? {
        val provider = candidateProviders().firstOrNull { it != LocationManager.PASSIVE_PROVIDER } ?: return null
        return suspendCancellableCoroutine { cont ->
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val signal = CancellationSignal()
                    cont.invokeOnCancellation { signal.cancel() }
                    lm.getCurrentLocation(provider, signal, executor) { loc -> if (cont.isActive) cont.resume(loc) }
                } else {
                    val listener = object : LocationListener {
                        override fun onLocationChanged(location: Location) { if (cont.isActive) cont.resume(location) }
                        @Deprecated("Deprecated in Java")
                        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                        override fun onProviderEnabled(provider: String) {}
                        override fun onProviderDisabled(provider: String) { if (cont.isActive) cont.resume(null) }
                    }
                    cont.invokeOnCancellation { lm.removeUpdates(listener) }
                    @Suppress("DEPRECATION")
                    lm.requestSingleUpdate(provider, listener, Looper.getMainLooper())
                }
            } catch (e: SecurityException) {
                if (cont.isActive) cont.resume(null)
            } catch (e: IllegalArgumentException) {
                if (cont.isActive) cont.resume(null)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun lastKnown(): Location? = candidateProviders()
        .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
        .maxByOrNull { it.time }

    private fun Location.toGeo() = GeoLocation(
        latitude = latitude,
        longitude = longitude,
        elevationMeters = if (hasAltitude()) altitude.coerceIn(-500.0, 9000.0) else 0.0,
        source = LocationSource.DEVICE,
    )
}

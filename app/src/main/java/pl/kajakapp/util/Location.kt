package pl.kajakapp.util

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

data class GeoPoint(val lat: Double, val lon: Double, val fixAt: Long)

class LocationHelper(private val context: Context) {

    fun hasPermission(): Boolean =
        listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            .any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

    /** Próbuje pobrać świeżą pozycję (Android 11+), a w razie niepowodzenia zwraca ostatnią znaną. */
    @SuppressLint("MissingPermission")
    suspend fun current(): GeoPoint? {
        if (!hasPermission()) return null
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val provider = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                .firstOrNull { lm.isProviderEnabled(it) }
            if (provider != null) {
                val fresh = withTimeoutOrNull(FRESH_FIX_TIMEOUT_MS) {
                    suspendCancellableCoroutine<Location?> { cont ->
                        val signal = CancellationSignal()
                        cont.invokeOnCancellation { signal.cancel() }
                        try {
                            lm.getCurrentLocation(
                                provider,
                                signal,
                                ContextCompat.getMainExecutor(context)
                            ) { location -> if (cont.isActive) cont.resume(location) }
                        } catch (e: SecurityException) {
                            if (cont.isActive) cont.resume(null)
                        }
                    }
                }
                if (fresh != null) return GeoPoint(fresh.latitude, fresh.longitude, fresh.time)
            }
        }
        return lastKnown(lm)
    }

    @SuppressLint("MissingPermission")
    private fun lastKnown(lm: LocationManager): GeoPoint? =
        lm.getProviders(true)
            .mapNotNull { provider ->
                try {
                    lm.getLastKnownLocation(provider)
                } catch (e: SecurityException) {
                    null
                }
            }
            .maxByOrNull { it.time }
            ?.let { GeoPoint(it.latitude, it.longitude, it.time) }

    private companion object {
        const val FRESH_FIX_TIMEOUT_MS = 8_000L
    }
}

/** Otwiera pozycję w zewnętrznej aplikacji map (jeśli jakaś jest zainstalowana). */
fun openInMaps(context: Context, lat: Double, lon: Double, label: String) {
    val uri = Uri.parse("geo:$lat,$lon?q=$lat,$lon(${Uri.encode(label)})")
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
    } catch (e: ActivityNotFoundException) {
        // Brak aplikacji map – nic więcej nie da się zrobić.
    }
}

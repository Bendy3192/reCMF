/*
 * reCMF — Copyright (C) 2026 reCMF contributors. AGPL-3.0-or-later.
 */
package dev.recmf.weather

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import java.io.IOException
import java.util.Locale

/**
 * Where the phone is, coarsely, and only when asked.
 *
 * reCMF went out of its way not to want this: the Bluetooth scan is declared
 * `neverForLocation` precisely so the app need never hold a location permission, and the
 * weather has always been for a city the wearer typed. That is still the default and
 * still works with no permission at all.
 *
 * What this adds is a button. Someone who travels should not have to retype where they
 * are, and a coarse fix — a kilometre or so, from cell towers and known networks, with no
 * satellite involved — is far more precision than a forecast needs.
 *
 * **Coarse and on demand, never in the background.** Following the wearer around would
 * mean a background-location permission and a foreground service that keeps the radio
 * interested, which is a great deal of machinery and battery for a temperature. Asked
 * once, from a screen the wearer is looking at, it costs nothing.
 */
object PhoneLocation {

    private const val TAG = "PhoneLocation"

    /** A place to show, and the coordinates the forecast is actually fetched for. */
    data class Place(val name: String, val latitude: Double, val longitude: Double)

    fun granted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    val PERMISSIONS: Array<String> = arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION)

    /**
     * The last position any app on the phone has already established, named if possible.
     *
     * Deliberately the *last known* position rather than a fresh fix. Something on the
     * phone has almost always located it in the last few minutes, and a forecast does not
     * care whether that was five minutes ago — where asking for a new one would spin up
     * the radios for an answer that is the same to the nearest kilometre.
     *
     * @return null when the permission is missing, when nothing on the phone knows where
     *   it is, or when what it knows is Null Island.
     */
    fun current(context: Context): Place? {
        if (!granted(context)) return null

        val manager = context.getSystemService<LocationManager>() ?: return null

        val fix = try {
            manager.providers(context).mapNotNull { manager.getLastKnownLocation(it) }
                .maxByOrNull { it.time }
        } catch (e: SecurityException) {
            // The permission can be revoked between the check above and here.
            Log.i(TAG, "Not allowed to read the phone's location", e)
            null
        } ?: return null

        if (fix.latitude == 0.0 && fix.longitude == 0.0) return null

        return Place(name(context, fix), fix.latitude, fix.longitude)
    }

    /**
     * Providers worth asking, coarsest first.
     *
     * The fused provider is the one Android would rather everything used, and it is not
     * present on every device — a phone without Play Services has the network provider
     * and nothing else. Both are asked and the newest answer wins.
     */
    private fun LocationManager.providers(context: Context): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
        add(LocationManager.NETWORK_PROVIDER)
        add(LocationManager.PASSIVE_PROVIDER)
    }.filter { runCatching { isProviderEnabled(it) }.getOrDefault(false) }

    /**
     * A name for the place, or its coordinates written out.
     *
     * The name is a courtesy: it is what the watch shows above the temperature, and
     * "55.8, 37.6" is a poor thing to read on a wrist. But the forecast is fetched from
     * the coordinates either way, so a geocoder that is missing, offline or simply
     * unhelpful costs nothing but the label.
     */
    private fun name(context: Context, fix: Location): String {
        if (!Geocoder.isPresent()) return fix.written()

        return try {
            @Suppress("DEPRECATION")
            val found = Geocoder(context, Locale.getDefault())
                .getFromLocation(fix.latitude, fix.longitude, 1)
                ?.firstOrNull()

            found?.locality
                ?: found?.subAdminArea
                ?: found?.adminArea
                ?: fix.written()
        } catch (e: IOException) {
            Log.i(TAG, "Could not name the phone's location", e)
            fix.written()
        }
    }

    private fun Location.written(): String = "%.2f, %.2f".format(Locale.US, latitude, longitude)
}

package com.example.bluetooth

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import java.net.Inet4Address
import java.net.NetworkInterface

private const val TAG = "WifiDirectGroupManager"

/**
 * Ad-hoc, no-router-needed WiFi transport for speaker mode - the "Quick Share" style handoff:
 * the host stands up a WiFi Direct group (a small, temporary WiFi network with a random
 * SSID/passphrase) and hands those credentials to a speaker over the Bluetooth socket that's
 * already connected. The speaker then joins that network like any other WiFi access point via
 * WifiNetworkSpecifier (API 29+) - no peer discovery/pairing dance needed on its side, and no
 * dependency on both phones already sharing a WiFi network, so it works in a car, outdoors,
 * anywhere. A WiFi Direct "legacy" group behaves like a small access point rather than a strict
 * 1:1 link, so any number of speakers can join the same group.
 *
 * Requires API 29+ on both ends (older devices silently get no WiFi Direct offer/attempt and
 * just stay on the Bluetooth connection, which already works end to end, just slower).
 */
class WifiDirectGroupManager(private val context: Context) {

    data class GroupCredentials(val ssid: String, val passphrase: String, val hostIp: String?)

    private val manager = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private val channel = manager?.initialize(context, Looper.getMainLooper(), null)

    @SuppressLint("MissingPermission")
    suspend fun createGroup(): GroupCredentials? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val mgr = manager ?: return null
        val ch = channel ?: return null

        // A group this app created earlier can outlive the process that created it - a
        // force-stop, crash, or OEM background kill all skip onDestroy()/stopHosting(), and
        // WiFi Direct groups live at the OS level, not tied to any app's lifecycle. A device
        // that's since become a plain client but still secretly owns a leftover group of its
        // own will then collide with itself (its own group's conventional 192.168.49.1 clashes
        // with whatever it's trying to reach) the moment it tries to join someone else's group.
        // Clearing first makes this robust regardless of how the previous group was abandoned.
        removeGroup()

        val formed = suspendCancellableCoroutine<Boolean> { cont ->
            try {
                mgr.createGroup(ch, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        if (cont.isActive) cont.resumeWith(Result.success(true))
                    }
                    override fun onFailure(reason: Int) {
                        Log.d(TAG, "createGroup failed: $reason")
                        if (cont.isActive) cont.resumeWith(Result.success(false))
                    }
                })
            } catch (e: SecurityException) {
                Log.d(TAG, "createGroup missing permission", e)
                if (cont.isActive) cont.resumeWith(Result.success(false))
            }
        }
        if (!formed) return null

        // The well-known "192.168.49.1" group-owner address is only a convention for the
        // classic P2P connect() flow - it isn't guaranteed here, especially with concurrent
        // regular-WiFi + P2P (this device can keep its home WiFi connection while hosting a
        // group), which can shift how the P2P interface gets addressed. Read the interface's
        // real IP directly instead of assuming it. One short retry since the interface can take
        // a brief moment to get an address right after group formation completes.
        repeat(2) { attempt ->
            val info = requestGroupInfo(mgr, ch) ?: return@repeat
            Log.d(TAG, "Group info attempt $attempt: interface=${info.`interface`}")
            val hostIp = readInterfaceIpv4(info.`interface`)
            Log.d(TAG, "Resolved P2P interface IP: $hostIp")
            if (hostIp != null) return GroupCredentials(info.networkName, info.passphrase, hostIp)
            if (attempt == 0) delay(400)
        }
        val fallback = requestGroupInfo(mgr, ch) ?: return null
        Log.d(TAG, "Could not resolve P2P interface IP after retry - using fallback IP")
        return GroupCredentials(fallback.networkName, fallback.passphrase, null)
    }

    private suspend fun requestGroupInfo(mgr: WifiP2pManager, ch: WifiP2pManager.Channel): WifiP2pGroup? {
        return suspendCancellableCoroutine { cont ->
            try {
                mgr.requestGroupInfo(ch) { group: WifiP2pGroup? ->
                    val valid = if (group?.networkName != null && group.passphrase != null) group else null
                    if (cont.isActive) cont.resumeWith(Result.success(valid))
                }
            } catch (e: SecurityException) {
                if (cont.isActive) cont.resumeWith(Result.success(null))
            }
        }
    }

    private fun readInterfaceIpv4(interfaceName: String?): String? {
        if (interfaceName == null) return null
        try {
            val all = NetworkInterface.getNetworkInterfaces().asSequence()
                .joinToString(", ") { itf ->
                    "${itf.name}=[" + itf.inetAddresses.asSequence().joinToString(",") { it.hostAddress ?: "?" } + "]"
                }
            Log.d(TAG, "All interfaces at group-info time: $all")
        } catch (e: Exception) { /* diagnostic only */ }
        return try {
            NetworkInterface.getByName(interfaceName)
                ?.inetAddresses?.asSequence()
                ?.filterIsInstance<Inet4Address>()
                ?.firstOrNull { !it.isLoopbackAddress }
                ?.hostAddress
        } catch (e: Exception) {
            Log.d(TAG, "Failed to read IP for P2P interface $interfaceName", e)
            null
        }
    }

    /** Suspends until the removal actually completes (or fails/times out) rather than firing
     * and forgetting - callers that immediately create a new group afterward need the old one
     * actually gone first, not just a request in flight. */
    @SuppressLint("MissingPermission")
    suspend fun removeGroup() {
        val mgr = manager ?: return
        val ch = channel ?: return
        suspendCancellableCoroutine<Unit> { cont ->
            try {
                mgr.removeGroup(ch, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() { if (cont.isActive) cont.resumeWith(Result.success(Unit)) }
                    override fun onFailure(reason: Int) { if (cont.isActive) cont.resumeWith(Result.success(Unit)) }
                })
            } catch (e: Exception) {
                if (cont.isActive) cont.resumeWith(Result.success(Unit))
            }
        }
    }

    /** Joins the given WiFi Direct group as a regular WiFi client and returns the [Network] to
     * bind sockets to (crucial - without binding, traffic keeps routing over the phone's default
     * network, e.g. cellular, instead of this local-only link), or null on failure/timeout.
     *
     * Real-world WPA handshake + association for a fresh WiFi Direct group has been observed
     * taking 5-6 seconds on its own (confirmed via wpa_supplicant logs) - a shorter timeout here
     * doesn't just fail early, it actively races the real `onAvailable` callback: if the timeout
     * fires first, this tears the connection back down (unregisterNetworkCallback) at almost the
     * exact moment it actually succeeded, discarding a perfectly good link. */
    suspend fun joinGroup(ssid: String, passphrase: String, timeoutMs: Long = 15000): Network? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        val specifier = WifiNetworkSpecifier.Builder()
            .setSsid(ssid)
            .setWpa2Passphrase(passphrase)
            .build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()

        return suspendCancellableCoroutine { cont ->
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (cont.isActive) cont.resumeWith(Result.success(network))
                }
                override fun onUnavailable() {
                    if (cont.isActive) cont.resumeWith(Result.success(null))
                }
            }
            val handler = Handler(Looper.getMainLooper())
            val timeoutRunnable = Runnable {
                if (cont.isActive) {
                    try { connectivityManager.unregisterNetworkCallback(callback) } catch (e: Exception) { /* ignore */ }
                    cont.resumeWith(Result.success(null))
                }
            }
            handler.postDelayed(timeoutRunnable, timeoutMs)
            try {
                connectivityManager.requestNetwork(request, callback)
            } catch (e: Exception) {
                Log.e(TAG, "requestNetwork failed", e)
                handler.removeCallbacks(timeoutRunnable)
                if (cont.isActive) cont.resumeWith(Result.success(null))
            }
            cont.invokeOnCancellation {
                handler.removeCallbacks(timeoutRunnable)
                try { connectivityManager.unregisterNetworkCallback(callback) } catch (e: Exception) { /* ignore */ }
            }
        }
    }
}

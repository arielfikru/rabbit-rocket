package io.github.arielfikru.rabbitrocket

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.CompletableDeferred

object Net {
    fun isOnline(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** Suspends until some network with internet shows up; returns at once when already online. */
    suspend fun awaitOnline(ctx: Context) {
        if (isOnline(ctx)) return
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return
        val ready = CompletableDeferred<Unit>()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) ready.complete(Unit)
            }
        }
        cm.registerDefaultNetworkCallback(cb)
        try {
            if (!isOnline(ctx)) ready.await()
        } finally {
            runCatching { cm.unregisterNetworkCallback(cb) }
        }
    }
}

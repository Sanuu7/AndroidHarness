package com.androidharness.app.automation

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal data class AutomationConnection(val connected: Boolean = false, val unmetered: Boolean = false) {
    fun allows(task: AutomationTask): Boolean = connected && (!task.unmeteredOnly || unmetered)
    fun waitingMessage(task: AutomationTask): String =
        if (task.unmeteredOnly) "Waiting for unmetered internet" else "Waiting for internet"
}

/** Application-owned observer. Recheck the default network, including VPN changes. */
internal class AutomationNetwork(context: Context) {
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    private val mutable = MutableStateFlow(snapshot())
    val state: StateFlow<AutomationConnection> = mutable
    init {
        manager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { refresh() }
            override fun onLost(network: Network) { refresh() }
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                refresh()
            }
        })
    }
    private fun refresh() { mutable.value = snapshot() }
    private fun snapshot(): AutomationConnection {
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork)
        return AutomationConnection(
            connected = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            unmetered = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true,
        )
    }
}

internal class AutomationConnectionLost : RuntimeException()
internal enum class AutomationOutcome { DONE, RETRY }

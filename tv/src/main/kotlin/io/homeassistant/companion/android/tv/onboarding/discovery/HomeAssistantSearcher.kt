package io.homeassistant.companion.android.tv.onboarding.discovery

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import java.net.URL
import timber.log.Timber

private const val SERVICE_TYPE = "_home-assistant._tcp"
private const val LOCK_TAG = "HomeAssistantSearcher_lock"

data class HomeAssistantInstance(
    val name: String,
    val url: URL,
)

class HomeAssistantSearcher(
    private val nsdManager: NsdManager,
    private val wifiManager: WifiManager?,
    private val onStart: () -> Unit,
    private val onInstanceFound: (HomeAssistantInstance) -> Unit,
    private val onError: (String) -> Unit,
) {

    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var isSearching = false

    fun beginSearch() {
        if (isSearching) return
        isSearching = true

        try {
            multicastLock = wifiManager?.createMulticastLock(LOCK_TAG)?.apply {
                setReferenceCounted(true)
                acquire()
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to acquire multicast lock")
        }

        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String?) {
                Timber.d("Discovery started")
                onStart()
            }

            override fun onDiscoveryStopped(serviceType: String?) {
                Timber.d("Discovery stopped")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo?) {
                serviceInfo?.let {
                    Timber.d("Service found: ${it.serviceName}")
                    resolveService(it)
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo?) {
                Timber.d("Service lost: ${serviceInfo?.serviceName}")
            }

            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                Timber.e("Start discovery failed: $errorCode")
                onError("Discovery failed with error code: $errorCode")
                stopSearch()
            }

            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {
                Timber.e("Stop discovery failed: $errorCode")
            }
        }

        try {
            nsdManager.discoverServices(
                SERVICE_TYPE,
                NsdManager.PROTOCOL_DNS_SD,
                discoveryListener,
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to start discovery")
            onError("Failed to start discovery: ${e.message}")
        }
    }

    @Suppress("DEPRECATION")
    private fun resolveService(serviceInfo: NsdServiceInfo) {
        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(service: NsdServiceInfo?, errorCode: Int) {
                Timber.e("Resolve failed for ${service?.serviceName}: $errorCode")
            }

            override fun onServiceResolved(service: NsdServiceInfo?) {
                service?.let {
                    val baseUrl = it.attributes?.get("base_url")?.toString(Charsets.UTF_8)
                    if (!baseUrl.isNullOrBlank()) {
                        try {
                            val url = URL(baseUrl)
                            val instance = HomeAssistantInstance(
                                name = it.serviceName,
                                url = url,
                            )
                            Timber.d("Resolved instance: ${instance.name} at ${instance.url}")
                            onInstanceFound(instance)
                        } catch (e: Exception) {
                            Timber.e(e, "Invalid URL: $baseUrl")
                        }
                    } else {
                        Timber.w("No base_url attribute found for ${it.serviceName}")
                    }
                }
            }
        }

        try {
            nsdManager.resolveService(serviceInfo, resolveListener)
        } catch (e: Exception) {
            Timber.e(e, "Failed to resolve service")
        }
    }

    fun stopSearch() {
        if (!isSearching) return
        isSearching = false

        discoveryListener?.let {
            try {
                nsdManager.stopServiceDiscovery(it)
            } catch (e: Exception) {
                Timber.e(e, "Failed to stop discovery")
            }
        }
        discoveryListener = null

        multicastLock?.let {
            try {
                if (it.isHeld) {
                    it.release()
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to release multicast lock")
            }
        }
        multicastLock = null
    }
}

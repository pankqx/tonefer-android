package com.pankqx.tonefer.net

import com.pankqx.tonefer.data.Pairing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Finds the paired laptop on any local network the phone is part of: its own hotspot,
 * USB tethering or shared Wi‑Fi. Every address in the phone's local /24 subnets is probed with
 * the pairing token; only the paired laptop answers 200.
 *
 * Needs no location, multicast or nearby-device permissions.
 */
class Discovery(base: OkHttpClient) {

    sealed interface Result {
        data class Found(val host: String) : Result
        data object Unauthorized : Result
        data object NotFound : Result
    }

    private val probeClient = base.newBuilder()
        .connectTimeout(450, TimeUnit.MILLISECONDS)
        .readTimeout(1200, TimeUnit.MILLISECONDS)
        .callTimeout(1800, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(false)
        .build()

    /** 200 → paired laptop. 401 → a Tonefer laptop that no longer accepts this key. */
    fun ping(host: String, pairing: Pairing, client: OkHttpClient = probeClient): Int = try {
        val req = Request.Builder()
            .url("http://$host:${pairing.port}/api/ping")
            .header("X-Tonefer-Token", pairing.token)
            .build()
        client.newCall(req).execute().use { it.code }
    } catch (_: Exception) {
        -1
    }

    suspend fun find(pairing: Pairing): Result = withContext(Dispatchers.IO) {
        val candidates = candidates(pairing.host)
        if (candidates.isEmpty()) return@withContext Result.NotFound
        val found = AtomicReference<Result?>(null)
        val gate = Semaphore(48)
        coroutineScope {
            candidates.map { host ->
                async {
                    if (found.get() is Result.Found) return@async
                    gate.withPermit {
                        if (found.get() is Result.Found) return@withPermit
                        when (ping(host, pairing)) {
                            200 -> found.set(Result.Found(host))
                            401 -> found.compareAndSet(null, Result.Unauthorized)
                        }
                    }
                }
            }.awaitAll()
        }
        found.get() ?: Result.NotFound
    }

    /** Last known host first, then gateways (.1), then every host of each local /24. */
    private fun candidates(lastHost: String): List<String> {
        val out = LinkedHashSet<String>()
        out += lastHost
        val own = mutableSetOf<String>()
        val subnets = mutableListOf<String>()
        try {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                if (!nif.isUp || nif.isLoopback) continue
                val name = nif.name.lowercase()
                // Skip mobile-data and VPN links: they never lead to the laptop and can be huge.
                if (name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("tun") ||
                    name.startsWith("ppp") || name.startsWith("dummy") || name.startsWith("ipsec")) continue
                for (addr in nif.interfaceAddresses) {
                    val ip = addr.address as? Inet4Address ?: continue
                    if (ip.isLinkLocalAddress || ip.isLoopbackAddress) continue
                    if (!ip.isSiteLocalAddress) continue
                    val text = ip.hostAddress ?: continue
                    own += text
                    subnets += text.substringBeforeLast('.')
                }
            }
        } catch (_: Exception) {
            // Some OEM builds throw for restricted interfaces; fall back to the last host only.
        }
        for (prefix in subnets) out += "$prefix.1"
        for (prefix in subnets) for (i in 2..254) out += "$prefix.$i"
        out.removeAll(own)
        return out.toList()
    }
}

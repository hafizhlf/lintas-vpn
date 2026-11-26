package app.lintas.warp

import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Resolves WARP endpoint hosts without letting a broken DNS server stall the
 * connect path. Android's blocking resolver can take tens of seconds on a
 * network that is still handing over, so each lookup gets a hard deadline and
 * failed hosts are skipped for a while.
 */
class WarpEndpointResolver(
    private val timeoutMs: Long = 2_000L,
    private val failureTtlMs: Long = 60_000L,
) {
    private val executor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "lintas-endpoint-resolver").apply { isDaemon = true }
    }
    private val resolved = ConcurrentHashMap<String, InetAddress>()
    private val failedAt = ConcurrentHashMap<String, Long>()

    fun resolve(host: String): InetAddress? {
        val key = host.trim()
        if (key.isEmpty()) return null
        resolved[key]?.let { return it }

        val failed = failedAt[key]
        if (failed != null && System.currentTimeMillis() - failed < failureTtlMs) {
            return null
        }

        if (isIpLiteral(key)) {
            return runCatching { InetAddress.getByName(key) }
                .onSuccess { resolved[key] = it }
                .getOrNull()
        }

        val future = runCatching {
            executor.submit(Callable {
                runCatching {
                    val addresses = InetAddress.getAllByName(key)
                    addresses.firstOrNull { it is Inet4Address } ?: addresses.first()
                }.getOrNull()
            })
        }.getOrNull() ?: return null
        return try {
            val address = future.get(timeoutMs, TimeUnit.MILLISECONDS)
            if (address == null) {
                failedAt[key] = System.currentTimeMillis()
            } else {
                resolved[key] = address
                failedAt.remove(key)
            }
            address
        } catch (timeout: TimeoutException) {
            future.cancel(true)
            failedAt[key] = System.currentTimeMillis()
            null
        } catch (error: InterruptedException) {
            future.cancel(true)
            Thread.currentThread().interrupt()
            failedAt[key] = System.currentTimeMillis()
            null
        } catch (error: Exception) {
            future.cancel(true)
            failedAt[key] = System.currentTimeMillis()
            null
        }
    }

    fun clearFailures() {
        failedAt.clear()
    }

    fun shutdown() {
        executor.shutdownNow()
    }

    private fun isIpLiteral(host: String): Boolean {
        return host.contains(':') || IPV4.matches(host)
    }

    private companion object {
        val IPV4 = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")
    }
}

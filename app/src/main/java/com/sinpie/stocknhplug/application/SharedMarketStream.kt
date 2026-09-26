package com.sinpie.stocknhplug.application

import com.sinpie.stocknhplug.domain.Quote
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One physical quote connection per credential set, with independent account subscriptions. A
 * closed account releases only its own lease. Capacity overflow remains unacknowledged so
 * HybridPriceMonitor continues REST rather than silently accepting another account's quotes.
 */
class SharedMarketStream(
    private val factory: ((Quote) -> Unit, (String) -> Unit, () -> Unit) -> MarketStream,
    private val capacity: Int = 10,
) {
    private val lock = Any()
    private val connection = Mutex()
    private val clients = linkedSetOf<Client>()
    private var physical: MarketStream? = null
    private var generation = 0L
    private var subscribed = emptySet<String>()
    private var connecting = false

    init {
        require(capacity in 1..10)
    }

    fun lease(
        onQuote: (Quote) -> Unit,
        onEvent: (String) -> Unit,
        onDisconnect: () -> Unit,
    ): MarketStream = Client(onQuote, onEvent, onDisconnect)

    private fun validate(symbols: Set<String>) {
        require(symbols.size <= 10 && symbols.all { it.matches(Regex("[0-9]{6}")) })
    }

    /** Keep already allocated symbols stable; fill free slots deterministically. */
    private fun updateLocked() {
        val wanted = clients.flatMap { it.wanted }.toSet()
        subscribed = ((subscribed intersect wanted) + wanted.sorted()).take(capacity).toSet()
        if (wanted.isEmpty()) {
            generation++
            physical?.close()
            physical = null
            connecting = false
        } else physical?.replaceSubscriptions(subscribed)
    }

    private inner class Client(
        val quote: (Quote) -> Unit,
        val event: (String) -> Unit,
        val disconnected: () -> Unit,
    ) : MarketStream {
        var wanted = emptySet<String>()
        private var leaseGeneration = 0L

        override suspend fun connect(symbols: List<String>) {
            validate(symbols.toSet())
            // close() must also revoke a request queued behind another account's authentication.
            val owner = synchronized(lock) { leaseGeneration }
            connection.withLock {
                val start: Pair<MarketStream, Long>? =
                    synchronized(lock) {
                        if (owner != leaseGeneration) return@withLock
                        wanted = symbols.toSet()
                        clients += this
                        updateLocked()
                        if (subscribed.isEmpty() || physical?.isConnected() == true || connecting)
                            null
                        else {
                            physical?.close()
                            val id = ++generation
                            val stream =
                                factory(
                                    { q ->
                                        synchronized(lock) {
                                            if (
                                                generation == id &&
                                                    q.symbol in subscribed &&
                                                    q.symbol in physical?.acknowledged().orEmpty()
                                            )
                                                clients
                                                    .filter { q.symbol in it.wanted }
                                                    .toList()
                                                    .forEach { it.quote(q) }
                                        }
                                    },
                                    { message ->
                                        synchronized(lock) {
                                            if (generation == id)
                                                clients.toList().forEach { it.event(message) }
                                        }
                                    },
                                    {
                                        synchronized(lock) {
                                            if (generation == id) {
                                                connecting = false
                                                clients.toList().forEach { it.disconnected() }
                                            }
                                        }
                                    },
                                )
                            physical = stream
                            connecting = true
                            stream to id
                        }
                    }
                if (start != null) {
                    try {
                        val targets = synchronized(lock) { subscribed.toList() }
                        start.first.connect(targets)
                        synchronized(lock) {
                            if (generation != start.second) start.first.close()
                            else start.first.replaceSubscriptions(subscribed)
                        }
                    } catch (e: Exception) {
                        synchronized(lock) {
                            if (generation == start.second) {
                                physical?.close()
                                physical = null
                                connecting = false
                            }
                        }
                        throw e
                    }
                }
            }
        }

        override fun replaceSubscriptions(symbols: Set<String>) =
            synchronized(lock) {
                validate(symbols)
                wanted = symbols.toSet()
                clients += this
                updateLocked()
            }

        override fun acknowledged(): Set<String> =
            synchronized(lock) {
                if (this !in clients) emptySet()
                else physical?.acknowledged().orEmpty() intersect wanted
            }

        override fun isConnected(): Boolean =
            synchronized(lock) { this in clients && physical?.isConnected() == true }

        override fun close() =
            synchronized(lock) {
                leaseGeneration++
                clients -= this
                wanted = emptySet()
                updateLocked()
            }
    }
}

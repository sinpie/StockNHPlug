package com.sinpie.stocknhplug

import com.sinpie.stocknhplug.application.*
import com.sinpie.stocknhplug.domain.*
import com.sinpie.stocknhplug.trading.NamuExecutionGate
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class HybridMonitorTest {
    private class Stream : MarketStream {
        var wanted = emptySet<String>()
        var ack = emptySet<String>()
        var connected = true
        var connects = 0

        override suspend fun connect(symbols: List<String>) {
            check(symbols.isNotEmpty())
            connects++
            wanted = symbols.toSet()
            connected = true
        }

        override fun replaceSubscriptions(symbols: Set<String>) {
            wanted = symbols
        }

        override fun acknowledged() = ack

        override fun isConnected() = connected

        override fun close() {
            connected = false
            ack = emptySet()
        }
    }

    @Test
    fun trackingWriteFailurePropagatesInsteadOfBecomingRestRetry() = runBlocking {
        val now = Instant.parse("2026-09-21T01:00:00Z")
        var fail = false
        val gate =
            NamuExecutionGate(
                object : TrackingStore {
                    override fun load(account: Account, environment: Environment) =
                        emptyList<TrackingRecord>()

                    override fun save(
                        account: Account,
                        environment: Environment,
                        records: List<TrackingRecord>,
                    ) {
                        check(!fail)
                    }
                }
            )
        gate.activate(Account("test", Environment.MOCK, "test"), Environment.MOCK)
        val q = Quote("005930", 9000, 9000, 9000, now, now, true)
        gate.evaluate(TargetRequest("a", q.symbol, Side.BUY, 10000, now.plusSeconds(1800)), q, now)
        fail = true
        val events = mutableListOf<String>()
        val monitor =
            HybridPriceMonitor(
                CurrentPriceProvider {
                    PriceSnapshot(
                        q,
                        MarketRules(
                            now.atZone(SEOUL).toLocalDate(),
                            1000,
                            50000,
                            InstrumentKind.STOCK,
                        ),
                        PriceSource.REST,
                    )
                },
                Stream(),
                gate,
                {},
                { events += it },
                { 0.0 },
                { now },
            )
        val error = runCatching { monitor.step(setOf(q.symbol)) }.exceptionOrNull()
        assertTrue(error is TrackingStorageException)
        assertTrue(events.isEmpty())
    }

    @Test
    fun farRestNearSocketAndAllInvalidSuspensionAreAppliedWithoutEmptyConnections() = runBlocking {
        var now = Instant.parse("2026-09-21T01:00:00Z")
        var mono = 0.0
        var price = 15000L
        var reads = 0
        val stream = Stream().apply { connected = false }
        val gate = NamuExecutionGate()
        fun q() = Quote("005930", price, price, price, now, now, true)
        val request = TargetRequest("a", "005930", Side.BUY, 10000, now.plusSeconds(1800))
        gate.evaluate(request, q(), now)
        val monitor =
            HybridPriceMonitor(
                CurrentPriceProvider {
                    reads++
                    PriceSnapshot(
                        q(),
                        MarketRules(
                            now.atZone(SEOUL).toLocalDate(),
                            1000,
                            50000,
                            InstrumentKind.STOCK,
                        ),
                        PriceSource.REST,
                    )
                },
                stream,
                gate,
                {},
                {},
                { mono },
                { now },
            )
        monitor.step(setOf("005930"))
        assertEquals(0, stream.connects)
        mono = 180.0
        now = now.plusSeconds(180)
        price = 10000
        monitor.step(setOf("005930"))
        assertEquals(1, stream.connects)
        assertEquals(2, reads)
        stream.ack = stream.wanted
        price = 9000
        monitor.onWebsocket(q())
        price = 9800
        monitor.onWebsocket(q())
        assertTrue(gate.evaluate(request, q(), now))
        assertEquals(9000L, gate.targets().single().extreme)
        gate.evaluate(request.copy(strategyPrice = 60000), q(), now)
        monitor.step(setOf("005930"))
        assertFalse(stream.connected)
        mono += 1000
        now = now.plusSeconds(1000)
        monitor.step(setOf("005930"))
        assertEquals(2, reads)
    }

    @Test
    fun websocketOffNeverConnectsAndRestAlonePreservesReversalTrigger() = runBlocking {
        val start = Instant.parse("2026-09-21T01:00:00Z")
        var now = start
        var mono = 0.0
        val stream = Stream().apply { connected = false }
        val gate = NamuExecutionGate()
        val reads = mutableListOf<Double>()
        val received = mutableListOf<Quote>()
        fun q(price: Long) = Quote("005930", price, price, price, now, now, true)
        val request = TargetRequest("a", "005930", Side.BUY, 10000, now.plusSeconds(1800))
        gate.evaluate(request, q(15000), now)
        val monitor =
            HybridPriceMonitor(
                CurrentPriceProvider {
                    reads += mono
                    val price = if (mono < 20) 15000L else if (mono < 30) 9000L else 9800L
                    PriceSnapshot(
                        q(price),
                        MarketRules(
                            now.atZone(SEOUL).toLocalDate(),
                            1000,
                            50000,
                            InstrumentKind.STOCK,
                        ),
                        PriceSource.REST,
                    )
                },
                stream,
                gate,
                { received += it },
                {},
                { mono },
                { now },
                websocketEnabled = false,
            )
        for (second in 0..80) {
            mono = second.toDouble()
            now = start.plusSeconds(second.toLong())
            monitor.step(setOf("005930"))
        }
        assertEquals(0, stream.connects)
        assertTrue(reads.zipWithNext().all { (a, b) -> b - a <= 10.0 })
        assertEquals(9000L, gate.targets().single().extreme)
        assertTrue(gate.evaluate(request, received.last(), now))
        val count = received.size
        monitor.onWebsocket(q(8000))
        assertEquals(count, received.size)
    }

    @Test
    fun ackAndMetadataRequiredAndOutageFallsBackWithoutLosingTarget() = runBlocking {
        var now = Instant.parse("2026-09-18T01:00:00Z")
        var mono = 0.0
        var reads = 0
        val stream = Stream()
        val gate = NamuExecutionGate()
        val received = mutableListOf<Quote>()
        fun quote(price: Long) = Quote("005930", price, price, price, now, now, true)
        val monitor =
            HybridPriceMonitor(
                CurrentPriceProvider {
                    reads++
                    PriceSnapshot(
                        quote(10000),
                        MarketRules(
                            now.atZone(SEOUL).toLocalDate(),
                            7000,
                            13000,
                            InstrumentKind.STOCK,
                        ),
                        PriceSource.REST,
                    )
                },
                stream,
                gate,
                { received += it },
                {},
                { mono },
                { now },
            )
        monitor.onWebsocket(quote(9000))
        assertTrue(received.isEmpty())
        monitor.step(setOf("005930"))
        gate.evaluate(
            TargetRequest("g", "005930", Side.BUY, 10000, now.plusSeconds(1800)),
            received.last(),
            now,
        )
        monitor.step(setOf("005930"))
        assertEquals(setOf("005930"), stream.wanted)
        monitor.onWebsocket(quote(9000))
        assertEquals(1, received.size)
        stream.ack = stream.wanted
        monitor.onWebsocket(quote(9000))
        assertEquals(9000L, gate.targets().single().extreme)
        stream.close()
        now = now.plusSeconds(20)
        mono = 20.0
        monitor.step(setOf("005930"))
        assertEquals(2, reads)
        assertEquals(9000L, gate.targets().single().extreme)
        assertTrue(monitor.statuses().single().mode.startsWith("REST"))
    }
}

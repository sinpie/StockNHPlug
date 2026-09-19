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

        override suspend fun connect(symbols: List<String>) {
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

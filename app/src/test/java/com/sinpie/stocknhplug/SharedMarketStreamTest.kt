package com.sinpie.stocknhplug

import com.sinpie.stocknhplug.application.*
import com.sinpie.stocknhplug.domain.Quote
import java.time.Instant
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class SharedMarketStreamTest {
    private class Wire(val quote: (Quote) -> Unit, val disconnect: () -> Unit) : MarketStream {
        var desired = emptySet<String>()
        var open = false
        var starts = 0
        var closed = 0
        var pending: CompletableDeferred<Unit>? = null

        override suspend fun connect(symbols: List<String>) {
            starts++
            pending?.await()
            desired = symbols.toSet()
            open = true
        }

        override fun replaceSubscriptions(symbols: Set<String>) {
            desired = symbols
        }

        override fun acknowledged() = if (open) desired else emptySet()

        override fun isConnected() = open

        override fun close() {
            open = false
            closed++
        }

        fun emit(code: String) {
            val now = Instant.now()
            quote(Quote(code, 100, 100, 100, now, now, true))
        }

        fun fail() {
            open = false
            disconnect()
        }
    }

    @Test
    fun accountsShareConnectionButOnlyReceiveOwnSymbolsAndStopIndependently() = runBlocking {
        val wires = mutableListOf<Wire>()
        val hub = SharedMarketStream({ q, _, d -> Wire(q, d).also { wires += it } })
        val a = mutableListOf<String>()
        val b = mutableListOf<String>()
        val first = hub.lease({ a += it.symbol }, {}, {})
        val second = hub.lease({ b += it.symbol }, {}, {})
        first.connect(listOf("005930"))
        second.connect(listOf("000660"))
        assertEquals(1, wires.size)
        val wire = wires.single()
        wire.emit("005930")
        wire.emit("000660")
        assertEquals(listOf("005930"), a)
        assertEquals(listOf("000660"), b)
        first.close()
        assertTrue(second.isConnected())
        assertFalse(first.isConnected())
        assertEquals(setOf("000660"), wire.desired)
        wire.emit("005930")
        assertEquals(1, a.size)
        second.close()
        assertFalse(wire.open)
    }

    @Test
    fun duplicateSymbolUsesOneSlotAndCapacityOverflowIsNotAcknowledged() = runBlocking {
        lateinit var wire: Wire
        val hub = SharedMarketStream({ q, _, d -> Wire(q, d).also { wire = it } }, 1)
        val a = hub.lease({}, {}, {})
        val b = hub.lease({}, {}, {})
        a.connect(listOf("005930"))
        b.connect(listOf("005930", "000660"))
        assertEquals(setOf("005930"), wire.desired)
        assertEquals(setOf("005930"), b.acknowledged())
        a.close()
        b.replaceSubscriptions(setOf("000660"))
        assertEquals(setOf("000660"), b.acknowledged())
    }

    @Test
    fun disconnectRecreatesOnlyOneWireAndOldFramesCannotReachClients() = runBlocking {
        val wires = mutableListOf<Wire>()
        var disconnects = 0
        var quotes = 0
        val hub = SharedMarketStream({ q, _, d -> Wire(q, d).also { wires += it } })
        val a = hub.lease({ quotes++ }, {}, { disconnects++ })
        val b = hub.lease({}, {}, { disconnects++ })
        a.connect(listOf("005930"))
        b.connect(listOf("000660"))
        wires.first().fail()
        assertEquals(2, disconnects)
        a.connect(listOf("005930"))
        b.connect(listOf("000660"))
        assertEquals(2, wires.size)
        wires.first().emit("005930")
        assertEquals(0, quotes)
        wires.last().emit("005930")
        assertEquals(1, quotes)
    }

    @Test
    fun closingDuringAuthenticationCannotReopenAbandonedWire() = runBlocking {
        val release = CompletableDeferred<Unit>()
        lateinit var wire: Wire
        val hub =
            SharedMarketStream({ q, _, d ->
                Wire(q, d).also {
                    wire = it
                    it.pending = release
                }
            })
        val a = hub.lease({}, {}, {})
        val pending = launch { a.connect(listOf("005930")) }
        yield()
        a.close()
        release.complete(Unit)
        pending.join()
        assertFalse(wire.open)
        assertFalse(a.isConnected())
        assertTrue(a.acknowledged().isEmpty())
    }

    @Test
    fun cancelDuringConnectionReleasesGateForNextAccount() = runBlocking {
        val wires = mutableListOf<Wire>()
        val hub =
            SharedMarketStream({ q, _, d ->
                Wire(q, d).also {
                    if (wires.isEmpty()) it.pending = CompletableDeferred()
                    wires += it
                }
            })
        val a = hub.lease({}, {}, {})
        val pending = launch { a.connect(listOf("005930")) }
        yield()
        pending.cancelAndJoin()
        a.close()
        val b = hub.lease({}, {}, {})
        b.connect(listOf("000660"))
        assertTrue(b.isConnected())
        assertEquals(2, wires.size)
    }
}

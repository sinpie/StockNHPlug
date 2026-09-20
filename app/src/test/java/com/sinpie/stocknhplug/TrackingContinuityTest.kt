package com.sinpie.stocknhplug

import com.sinpie.stocknhplug.domain.*
import com.sinpie.stocknhplug.trading.*
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class TrackingContinuityTest {
    private val now = Instant.parse("2026-09-21T01:00:00Z")
    private val account = Account("test-account", Environment.MOCK, "test")

    private fun sample(price: Long, at: Instant = now, lower: Long = 1000, upper: Long = 50000) =
        PriceSnapshot(
            Quote("005930", price, price, price, at, at, true),
            MarketRules(at.atZone(SEOUL).toLocalDate(), lower, upper, InstrumentKind.STOCK),
            PriceSource.REST,
        )

    private fun request(key: String, side: Side = Side.BUY, price: Long = 10000) =
        TargetRequest(
            key,
            "005930",
            side,
            price,
            now.plusSeconds(1800),
            "strategy-$key",
            "group-$key",
            "test",
        )

    private class Memory : TrackingStore {
        val rows = mutableMapOf<Pair<Account, Environment>, List<TrackingRecord>>()

        override fun load(account: Account, environment: Environment) =
            rows[account to environment].orEmpty()

        override fun save(
            account: Account,
            environment: Environment,
            records: List<TrackingRecord>,
        ) {
            rows[account to environment] = records.toList()
        }
    }

    @Test
    fun independentTargetsKeepBothExtremaAcrossDateAndReloadWithoutReadyReplay() {
        val store = Memory()
        var gate = NamuExecutionGate(store)
        gate.activate(account, Environment.MOCK)
        val s = sample(9000)
        gate.observe(s, now)
        gate.evaluate(request("a"), s.quote, now)
        gate.evaluate(request("b", price = 8000), s.quote, now)
        gate.evaluate(request("c", Side.SELL, 8500), s.quote, now)
        assertEquals(9000L, gate.targets().first { it.key == "a" }.extreme)
        assertNull(gate.targets().first { it.key == "b" }.extreme)
        assertEquals(9000L, gate.targets().first { it.key == "c" }.extreme)
        gate.clear()
        gate = NamuExecutionGate(store)
        gate.activate(account, Environment.MOCK)
        assertTrue(gate.targets().all { !it.ready && it.trigger == null })
        val next = now.plusSeconds(86400)
        val low = sample(8500, next)
        gate.observe(low, next)
        val a = gate.targets().first { it.key == "a" }
        assertEquals(8500L, a.extreme)
        assertEquals(8500L, a.minimum)
        assertEquals(9000L, a.maximum)
        assertFalse(a.ready) // Yesterday's expired deadline cannot trigger at today's low.
        assertNull(gate.targets().first { it.key == "b" }.extreme)
        assertEquals(9000L, gate.targets().first { it.key == "c" }.extreme)
        gate.activate(account.copy(number = "other"), Environment.MOCK)
        assertTrue(gate.targets().isEmpty())
        gate.activate(account, Environment.LIVE)
        assertTrue(gate.targets().isEmpty())
        gate.activate(account, Environment.MOCK)
        assertEquals(3, gate.targets().size)
    }

    @Test
    fun outsideUpstreamFreezesHistoryWhileOtherGroupContinuesAndNextDayResumes() {
        val gate = NamuExecutionGate()
        val first = sample(9000)
        gate.observe(first, now)
        gate.evaluate(request("a"), first.quote, now)
        gate.evaluate(request("b", price = 8500), first.quote, now)
        val secondDay = now.plusSeconds(86400)
        val restricted = sample(8000, secondDay, upper = 9500)
        gate.observe(restricted, secondDay)
        val a = gate.targets().first { it.key == "a" }
        assertEquals(9000L, a.extreme)
        assertEquals(9000L, a.minimum)
        assertNull(a.trigger)
        assertFalse(a.ready)
        assertEquals(8000L, gate.targets().first { it.key == "b" }.extreme)
        val thirdDay = secondDay.plusSeconds(86400)
        gate.observe(sample(8500, thirdDay), thirdDay)
        assertEquals(8500L, gate.targets().first { it.key == "a" }.extreme)
        assertFalse(gate.targets().first { it.key == "a" }.ready)
    }

    @Test
    fun retiringOneTargetPreservesStoredHistoryAndDoesNotEraseOtherGroups() {
        val store = Memory()
        val gate = NamuExecutionGate(store)
        gate.activate(account, Environment.MOCK)
        val s = sample(9000)
        gate.observe(s, now)
        gate.evaluate(request("a"), s.quote, now)
        gate.evaluate(request("b"), s.quote, now)
        gate.retain(setOf("b"))
        assertEquals("b", gate.targets().single().key)
        val archived = store.load(account, Environment.MOCK).first { it.request.key == "a" }
        assertFalse(archived.active)
        assertEquals(9000L, archived.extreme)
    }

    @Test
    fun distanceIntervalsGrowAndOffCapsDiscoveryAndFailureAtTenSeconds() {
        for ((target, expected) in
            listOf(
                10000 to 2.0,
                10200 to 3.0,
                10500 to 5.0,
                11000 to 15.0,
                11500 to 15.0,
                12000 to 30.0,
                13000 to 60.0,
                14000 to 180.0,
            )) {
            for (enabled in listOf(true, false)) {
                val policy = HybridQuotePolicy(enabled)
                policy.setTargets(mapOf("005930" to listOf(target)), now)
                policy.observe(sample(10000), 0.0, now)
                val interval = if (enabled) expected else minOf(10.0, expected)
                assertEquals(
                    interval,
                    policy.statuses(emptySet(), emptySet(), now).single().intervalSeconds,
                    0.0,
                )
                assertNull(policy.claim(interval - 0.1))
                assertEquals("005930", policy.claim(interval))
                if (!enabled) {
                    assertTrue(policy.websocketSymbols(10).isEmpty())
                    repeat(8) {
                        policy.failed("005930", 100.0)
                        assertTrue(
                            policy.statuses(emptySet(), emptySet(), now).single().intervalSeconds <=
                                10
                        )
                    }
                }
            }
        }
        val discovery = HybridQuotePolicy(false)
        discovery.setTargets(mapOf("005930" to emptyList()), now)
        discovery.observe(sample(10000), 0.0, now)
        assertEquals("005930", discovery.claim(10.0))
    }

    @Test
    fun upstreamOutsideCannotBeRescuedByInsideTriggerButOtherGroupCanSubscribe() {
        val p = HybridQuotePolicy(false)
        p.setRequests(mapOf("005930" to listOf(QuoteTarget(60000.0, 10000.0))), now)
        p.observe(sample(10000), 0.0, now)
        assertNull(p.claim(100000.0)) // OFF never overrides daily suspension.
        val next = now.plusSeconds(86400)
        p.setRequests(mapOf("005930" to listOf(QuoteTarget(60000.0, 10000.0))), next)
        assertEquals("005930", p.claim(100001.0)) // New day's limits must be read.
        val mixed = HybridQuotePolicy()
        mixed.setRequests(
            mapOf("005930" to listOf(QuoteTarget(60000.0, 10000.0), QuoteTarget(11000.0, 10999.9))),
            now,
        )
        mixed.observe(sample(10000), 0.0, now)
        assertEquals(setOf("005930"), mixed.websocketSymbols(10))
    }

    @Test
    fun healthyAcknowledgedWebsocketSkipsRestUntilStaleAndTargetChangeAccelerates() {
        val p = HybridQuotePolicy()
        p.setTargets(mapOf("005930" to listOf(14000)), now)
        p.observe(sample(10000), 0.0, now)
        p.setTargets(mapOf("005930" to listOf(10500)), now.plusSeconds(1), 1.0)
        assertEquals("005930", p.claim(6.0))
        val ws = sample(10000, now.plusSeconds(7)).copy(source = PriceSource.WEBSOCKET)
        p.observe(ws, 7.0, ws.quote.receivedAt)
        assertNull(p.claim(12.0, setOf("005930"), now.plusSeconds(12)))
        assertEquals("005930", p.claim(23.0, setOf("005930"), now.plusSeconds(23)))
    }
}

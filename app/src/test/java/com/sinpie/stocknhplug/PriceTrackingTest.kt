package com.sinpie.stocknhplug

import com.sinpie.stocknhplug.domain.*
import com.sinpie.stocknhplug.execution.NhSocket
import com.sinpie.stocknhplug.marketdata.NhCurrentPriceProvider
import com.sinpie.stocknhplug.trading.*
import java.time.Instant
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PriceTrackingTest {
    private val now = Instant.parse("2026-09-18T01:00:00Z")

    private fun sample(price: Long, at: Instant = now, source: PriceSource = PriceSource.REST) =
        PriceSnapshot(
            Quote("005930", price, price, price, at, at, true),
            MarketRules(now.atZone(SEOUL).toLocalDate(), 1000, 50000, InstrumentKind.STOCK),
            source,
        )

    private fun request(key: String = "a", side: Side = Side.BUY, target: Long = 10000) =
        TargetRequest(key, "005930", side, target, now.plusSeconds(1800))

    @Test
    fun validLastPriceCannotHideBidAskOutsideDailyLimits() {
        val q = sample(10000).quote.copy(bid = 999, ask = 999)
        val s = sample(10000).copy(quote = q)
        assertFalse(s.valid(now))
        val gate = NamuExecutionGate()
        gate.observe(s, now)
        assertFalse(gate.evaluate(request().copy(deadline = now), q, now))
        val json = response()
        json.getJSONObject("Output_0").put("bidp", "6990").put("askp", "6990")
        assertTrue(runCatching { NhCurrentPriceProvider.parse("005930", json, now) }.isFailure)
    }

    @Test
    fun lateArrivalWithOlderExchangeTimeCannotMoveExtremaOrDistance() {
        val current = sample(9000)
        val old =
            sample(8000, now.plusSeconds(1)).let {
                it.copy(quote = it.quote.copy(exchangeAt = now.minusSeconds(1)))
            }
        val gate = NamuExecutionGate()
        gate.observe(current, now)
        gate.evaluate(request(), current.quote, now)
        gate.observe(old, now.plusSeconds(1))
        assertEquals(9000L, gate.targets().single().extreme)
        val policy = HybridQuotePolicy()
        policy.setTargets(mapOf("005930" to listOf(9000)), now)
        policy.observe(current, 0.0, now)
        assertFalse(policy.observe(old, 1.0, now.plusSeconds(1)))
        assertEquals(setOf("005930"), policy.websocketSymbols(10))
    }

    @Test
    fun buyWaitsForReversalAndPreservesLowAcrossSources() {
        val gate = NamuExecutionGate()
        val low = sample(9000)
        gate.observe(low, now)
        assertFalse(gate.evaluate(request(), low.quote, now))
        assertEquals(9000L, gate.targets().single().extreme)
        val rebound = sample(9800, now.plusSeconds(1), PriceSource.WEBSOCKET)
        gate.observe(rebound, rebound.quote.receivedAt)
        assertTrue(gate.evaluate(request(), rebound.quote, rebound.quote.receivedAt))
        assertEquals(9000L, gate.targets().single().extreme)
    }

    @Test
    fun sellRequiresPullbackAndAcceptablePrice() {
        val gate = NamuExecutionGate()
        val high = sample(11000)
        gate.observe(high, now)
        assertFalse(gate.evaluate(request(side = Side.SELL), high.quote, now))
        val pullback = sample(10200, now.plusSeconds(1))
        gate.observe(pullback, pullback.quote.receivedAt)
        assertTrue(
            gate.evaluate(request(side = Side.SELL), pullback.quote, pullback.quote.receivedAt)
        )
        val below = sample(9900, now.plusSeconds(2))
        gate.observe(below, below.quote.receivedAt)
        assertFalse(gate.evaluate(request(side = Side.SELL), below.quote, below.quote.receivedAt))
    }

    @Test
    fun independentGroupsDoNotShareExtremaOrTargets() {
        val gate = NamuExecutionGate()
        val s = sample(9000)
        gate.observe(s, now)
        gate.evaluate(request("a"), s.quote, now)
        gate.evaluate(request("b", target = 8000), s.quote, now)
        assertEquals(9000L, gate.targets().first { it.key == "a" }.extreme)
        assertNull(gate.targets().first { it.key == "b" }.extreme)
        gate.retain(setOf("b"))
        assertEquals("b", gate.targets().single().key)
        gate.clear()
        assertTrue(gate.targets().isEmpty())
    }

    @Test
    fun deadlineNeverOverridesStaleOrUnfavorableQuote() {
        val gate = NamuExecutionGate()
        val s = sample(10000)
        gate.observe(s, now)
        val expired = request().copy(deadline = now)
        assertTrue(gate.evaluate(expired, s.quote, now))
        assertFalse(gate.evaluate(expired, s.quote, now.plusSeconds(60)))
        val above = sample(10010, now.plusSeconds(61))
        gate.observe(above, above.quote.receivedAt)
        assertFalse(gate.evaluate(expired, above.quote, above.quote.receivedAt))
    }

    @Test
    fun unknownInstrumentAuctionAndOutOfRangeBlock() {
        for (s in
            listOf(
                sample(9000).copy(rules = sample(9000).rules.copy(kind = InstrumentKind.UNKNOWN)),
                sample(9000).copy(quote = sample(9000).quote.copy(regular = false)),
                sample(9000).copy(rules = sample(9000).rules.copy(upper = 9500)),
            )) {
            val gate = NamuExecutionGate()
            gate.observe(s, now)
            assertFalse(gate.evaluate(request().copy(deadline = now), s.quote, now))
        }
    }

    @Test
    fun tickOffsetsCrossPriceBands() {
        assertEquals(1999L, KrxTicks.offset(2000, -1, InstrumentKind.STOCK))
        assertEquals(2005L, KrxTicks.offset(2000, 1, InstrumentKind.STOCK))
        assertEquals(4995L, KrxTicks.offset(5000, -1, InstrumentKind.STOCK))
        assertEquals(5L, KrxTicks.unit(50000, InstrumentKind.ETF))
    }

    @Test
    fun exactlyTenPercentUsesWebsocketAndCurrentPriceDenominator() {
        val p = HybridQuotePolicy()
        p.setTargets(mapOf("005930" to listOf(11000)), now)
        p.observe(sample(10000), 0.0, now)
        assertEquals(setOf("005930"), p.websocketSymbols(10))
        p.setTargets(mapOf("005930" to listOf(11010)), now)
        assertTrue(p.websocketSymbols(10).isEmpty())
    }

    @Test
    fun cachedOrOlderSampleCannotSlideDueOrChangeRouting() {
        val p = HybridQuotePolicy()
        p.setTargets(mapOf("005930" to listOf(11000)), now)
        val current = sample(10000)
        p.observe(current, 0.0, now)
        p.observe(current, 4.0, now.plusSeconds(4))
        assertFalse(p.observe(sample(8000, now.minusSeconds(1)), 4.0, now))
        assertEquals(setOf("005930"), p.websocketSymbols(10))
        assertEquals("005930", p.claim(5.0))
    }

    @Test
    fun farPollIsAdaptiveAndErrorsBackOff() {
        val p = HybridQuotePolicy()
        p.setTargets(mapOf("005930" to listOf(14000)), now)
        p.observe(sample(10000), 0.0, now)
        assertNull(p.claim(179.0))
        assertEquals("005930", p.claim(180.0))
        p.failed("005930", 180.0)
        assertNull(p.claim(184.0))
        assertEquals("005930", p.claim(185.0))
        p.failed("005930", 185.0)
        assertNull(p.claim(194.0))
        assertEquals("005930", p.claim(195.0))
    }

    @Test
    fun targetsOutsideDailyRangeSuspendUntilChanged() {
        val p = HybridQuotePolicy()
        p.setTargets(mapOf("005930" to listOf(60000)), now)
        p.observe(sample(10000), 0.0, now)
        assertNull(p.claim(10000.0))
        p.setTargets(mapOf("005930" to listOf(11000)), now)
        assertEquals("005930", p.claim(10001.0))
    }

    @Test
    fun acknowledgementRequiresSuccessfulCorrectChannelAndSupportsArray() {
        fun ack(code: String = "00000", channel: String = "oc", type: String = "1") =
            JSONObject(
                """{"header":{"rsp_cd":"$code","tr_cd":"$channel","tr_type":"$type"},"body":{"tr_key":["005930","000660"]}}"""
            )
        assertEquals(setOf("005930", "000660"), NhSocket.acknowledgedSymbols(ack()))
        assertTrue(NhSocket.acknowledgedSymbols(ack("99999")).isEmpty())
        assertTrue(NhSocket.acknowledgedSymbols(ack(channel = "d2")).isEmpty())
        assertTrue(NhSocket.acknowledgedSymbols(ack(type = "2")).isEmpty())
    }

    private fun response() =
        JSONObject(
            """{"Output_0":{"iem_cd":"005930","hoga_bsop_hour":"10:00:00","stck_prpr":"10000","bidp":"9990","askp":"10000","stck_llam":"7000","stck_mxpr":"13000","scrt_grp_isnm":"주식"},"Output_2":{"cncc_aspr_code":"0"}}"""
        )

    @Test
    fun restParserRequiresCompleteTimestampedBidAskAndDailyRange() {
        val s = NhCurrentPriceProvider.parse("005930", response(), now)
        assertEquals(PriceSource.REST, s.source)
        assertEquals(InstrumentKind.STOCK, s.rules.kind)
        for (field in listOf("askp", "hoga_bsop_hour", "stck_llam")) {
            val json = response()
            json.getJSONObject("Output_0").remove(field)
            assertTrue(runCatching { NhCurrentPriceProvider.parse("005930", json, now) }.isFailure)
        }
        assertTrue(
            runCatching { NhCurrentPriceProvider.parse("000660", response(), now) }.isFailure
        )
        assertTrue(
            runCatching { NhCurrentPriceProvider.parse("005930", response(), now.plusSeconds(60)) }
                .isFailure
        )
    }
}

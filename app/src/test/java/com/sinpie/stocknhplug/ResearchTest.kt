package com.sinpie.stocknhplug

import com.sinpie.stocknhplug.application.SignalEngine
import com.sinpie.stocknhplug.domain.*
import com.sinpie.stocknhplug.research.*
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class ResearchTest {
    private val today=LocalDate.of(2026,9,21)
    private fun bars()=(1..70).map {val p=100.0+it;Candle(today.minusDays((71-it).toLong()),p,p+3,p-3,1000.0)}
    @Test fun rsiFlatIsNeutral() {assertEquals(50.0,SignalEngine.rsi(List(30){10.0}),.0001)}
    @Test fun rsiMonotonicGainsIs100() {assertEquals(100.0,SignalEngine.rsi((1..30).map {it.toDouble()}),.0001)}
    @Test fun incompleteCurrentDayIsExcluded() {
        val a=SignalEngine.evaluate("005930",bars(),today)
        val b=SignalEngine.evaluate("005930",bars()+Candle(today,9999.0,10000.0,9000.0,1e12),today)
        assertEquals(a,b)
    }
    @Test fun insufficientOrDuplicateOrNonFiniteDataRejected() {
        assertNull(SignalEngine.evaluate("005930",bars().take(20),today))
        assertNull(SignalEngine.evaluate("005930",bars()+bars().last(),today))
        assertNull(SignalEngine.evaluate("005930",bars().dropLast(1)+bars().last().copy(close=Double.NaN),today))
    }
    @Test fun splitAdjustsOnlyEarlierBarsAndUsesKnownActions() {
        val asOf=Instant.parse("2026-09-21T00:00:00Z")
        val raw=listOf(Candle(today.minusDays(2),100.0,110.0,90.0,10.0),Candle(today.minusDays(1),50.0,55.0,45.0,20.0))
        val action=CorporateAction(today.minusDays(1),.5,2.0,asOf.minusSeconds(10))
        val adjusted=AdjustedPriceEngine.adjust(raw,listOf(action),asOf)
        assertEquals(50.0,adjusted[0].close,0.0);assertEquals(20.0,adjusted[0].volume,0.0);assertEquals(50.0,adjusted[1].close,0.0)
        assertEquals(raw,AdjustedPriceEngine.adjust(raw,listOf(action.copy(publishedAt=asOf.plusSeconds(1))),asOf))
    }
    @Test fun missingNewsOrAdjustmentIsNotSafeEvidence() {
        val now=Instant.now()
        val e=ResearchEvidence("005930",PriceHistory("005930",bars(),false,DataSource.NHPLUG,now),null,emptyList(),emptyList(),false,false,now)
        assertTrue(e.buyBlockers(now).contains("수정주가 기준 미검증"));assertTrue(e.buyBlockers(now).contains("뉴스 이용권한 미확정"))
    }
    @Test fun invalidStrategyCannotSaveOrRun() {
        assertTrue(runCatching {Strategy(orderBudget=-1).validate()}.isFailure)
        assertTrue(runCatching {Strategy(stopLossPercent=Double.NaN).validate()}.isFailure)
        assertTrue(runCatching {Strategy(symbols=listOf("005930","005930")).validate()}.isFailure)
    }
}

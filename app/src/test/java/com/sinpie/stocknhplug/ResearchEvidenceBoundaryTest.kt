package com.sinpie.stocknhplug

import com.sinpie.stocknhplug.domain.*
import com.sinpie.stocknhplug.research.*
import java.math.BigDecimal
import java.time.*
import org.junit.Assert.*
import org.junit.Test

/** Common buy gate, independent of optional strategy indicator toggles. All data is synthetic. */
class ResearchEvidenceBoundaryTest {
    private val now = Instant.parse("2026-09-21T01:00:00Z")
    private val today = now.atZone(SEOUL).toLocalDate()
    private val candle = Candle(today.minusDays(3), 100.0, 110.0, 90.0, 1000.0)

    private fun evidence() =
        ResearchEvidence(
            "005930",
            PriceHistory("005930", listOf(candle), true, DataSource.LICENSED_ADJUSTED_PRICES, now),
            FinancialReport(
                2025,
                "11011",
                "fixture",
                true,
                BigDecimal(100),
                BigDecimal(10),
                BigDecimal(100),
                BigDecimal(20),
                now,
            ),
            emptyList(),
            emptyList(),
            true,
            true,
            now,
        )

    @Test
    fun thirtyMinuteExpiryDoesNotRoundDownExtraSeconds() {
        val e = evidence()
        assertTrue(e.buyBlockers(now.plusSeconds(1800)).isEmpty())
        assertFalse(e.buyBlockers(now.plusSeconds(1800).plusNanos(1)).isEmpty())
    }

    @Test
    fun adjustedFlagCannotApproveMissingWrongSymbolOrUnusableHistory() {
        val e = evidence()
        assertTrue(e.buyBlockers(now).isEmpty())
        val p = e.prices!!
        val invalid =
            listOf(
                p.copy(symbol = "000660"),
                p.copy(candles = emptyList()),
                p.copy(candles = listOf(candle, candle)),
                p.copy(candles = listOf(candle.copy(close = Double.NaN))),
                p.copy(candles = listOf(candle.copy(high = 99.0))),
                p.copy(candles = listOf(candle.copy(volume = -1.0))),
                p.copy(candles = listOf(candle.copy(date = today.minusDays(8)))),
                p.copy(candles = listOf(candle.copy(date = today))),
                p.copy(candles = listOf(candle.copy(date = today.plusDays(1)))),
                p.copy(asOf = now.plusNanos(1)),
            )
        invalid.forEachIndexed { i, item ->
            assertFalse("invalid history case $i", e.copy(prices = item).buyBlockers(now).isEmpty())
        }
    }

    @Test
    fun todaysUnfinishedBarDoesNotReplaceOrInvalidateValidClosedHistory() {
        val e = evidence()
        val current = candle.copy(date = today, close = 100.0)
        assertTrue(
            e.copy(prices = e.prices!!.copy(candles = listOf(candle, current)))
                .buyBlockers(now)
                .isEmpty()
        )
    }

    @Test
    fun adjustedHighOverflowCannotBeReturnedAsUsableCandle() {
        val raw = candle.copy(high = Double.MAX_VALUE)
        val action = CorporateAction(today.minusDays(1), 2.0, 1.0, now)
        assertTrue(
            runCatching { AdjustedPriceEngine.adjust(listOf(raw), listOf(action), now) }.isFailure
        )
    }
}

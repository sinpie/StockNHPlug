package com.sinpie.stocknhplug

import com.sinpie.stocknhplug.application.*
import com.sinpie.stocknhplug.domain.*
import java.math.BigDecimal
import java.time.*
import org.junit.Assert.*
import org.junit.Test

class HistoryAnalyticsTest {
    private fun day(date: String, pnl: Long?, cash: Long = 1000) =
        LocalDate.parse(date).let { d ->
            val at = d.atTime(15, 0).atZone(SEOUL).toInstant()
            AccountDay(
                d,
                Portfolio(cash, 10000, 500, emptyList(), at),
                listOf(Execution("1", "005930", "종목", "매수", 10, 5, 5, 100.0)),
                at,
                pnl?.let { DailyPnl(d, it, 1, 2) },
                if (pnl == null) null else at,
            )
        }

    @Test
    fun calendarBucketsDoNotMixYearsOrTurnDepositsIntoProfit() {
        val days =
            listOf(
                day("2025-12-31", 100),
                day("2026-01-01", -20, 9999999),
                day("2026-01-02", 0),
                day("2026-01-03", null),
            )
        val year = HistoryAnalytics.summarize(days, HistoryPeriod.YEAR)
        assertEquals(listOf("2026", "2025"), year.map { it.period })
        assertEquals(BigDecimal(-20), year[0].pnl)
        assertEquals(2, year[0].pnlDays)
        assertEquals(0.0, year[0].positiveDayRate!!, 0.001)
        assertEquals(3, year[0].filledOrders)
        assertEquals(0, BigDecimal(1500).compareTo(year[0].turnover))
        assertEquals(BigDecimal(2), year[0].fees)
        assertEquals(2, HistoryAnalytics.summarize(days, HistoryPeriod.MONTH).size)
        assertEquals(4, HistoryAnalytics.summarize(days, HistoryPeriod.DAY).size)
    }

    @Test
    fun missingIsNotZeroAndLargeSumsDoNotOverflow() {
        val unknown =
            HistoryAnalytics.summarize(listOf(day("2026-01-01", null)), HistoryPeriod.DAY).single()
        assertNull(unknown.pnl)
        assertNull(unknown.positiveDayRate)
        val total =
            HistoryAnalytics.summarize(
                    listOf(day("2026-01-01", Long.MAX_VALUE), day("2026-01-02", Long.MAX_VALUE)),
                    HistoryPeriod.YEAR,
                )
                .single()
        assertEquals(Long.MAX_VALUE.toBigDecimal() * BigDecimal(2), total.pnl)
        assertEquals(100.0, total.positiveDayRate!!, 0.001)
    }

    @Test(expected = IllegalArgumentException::class)
    fun duplicateDaysAreNotSilentlyDoubleCounted() {
        val d = day("2026-01-01", 5)
        HistoryAnalytics.summarize(listOf(d, d), HistoryPeriod.MONTH)
    }
}

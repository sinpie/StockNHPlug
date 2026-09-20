package com.sinpie.stocknhplug

import com.sinpie.stocknhplug.application.AccountAnalytics
import com.sinpie.stocknhplug.domain.*
import java.math.BigDecimal
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class AccountAnalyticsTest {
    @Test
    fun weightsUseHoldingsOnlyAndReturnUsesReportedPnl() {
        val a = Holding("a", "A", 10, 100, 120, 150)
        val b = Holding("b", "B", 10, 100, 80, -200)
        val rows = AccountAnalytics.holdings(listOf(a, b))
        assertEquals(60.0, rows[0].weightPercent!!, 0.0001)
        assertEquals(15.0, rows[0].returnPercent!!, 0.0001)
        assertEquals(-20.0, rows[1].returnPercent!!, 0.0001)
    }

    @Test
    fun missingCostAndZeroValueAreNotInventedPercentagesAndProductsDoNotOverflow() {
        val zero = Holding("a", "A", 0, 0, 0, 0)
        val metrics = AccountAnalytics.holdings(listOf(zero)).single()
        assertNull(metrics.returnPercent)
        assertNull(metrics.weightPercent)
        val huge =
            AccountAnalytics.holdings(
                    listOf(zero.copy(quantity = Long.MAX_VALUE, price = Long.MAX_VALUE))
                )
                .single()
        assertTrue(huge.value > BigDecimal(Long.MAX_VALUE))
        assertEquals(100.0, huge.weightPercent!!, 0.0)
    }

    @Test
    fun spreadRejectsMissingAndInvertedBookAndUsesMidpoint() {
        val at = Instant.now()
        val q = Quote("a", 100, 99, 101, at, at, true)
        assertEquals(2L, AccountAnalytics.spread(q))
        assertEquals(2.0, AccountAnalytics.spreadPercent(q)!!, 0.0)
        assertNull(AccountAnalytics.spread(q.copy(bid = 0)))
        assertNull(AccountAnalytics.spread(q.copy(ask = 98)))
    }
}

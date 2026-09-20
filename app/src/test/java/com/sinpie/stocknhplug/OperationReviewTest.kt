package com.sinpie.stocknhplug

import com.sinpie.stocknhplug.application.*
import com.sinpie.stocknhplug.domain.*
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class OperationReviewTest {
    private val at = Instant.parse("2026-09-21T01:00:00Z")
    private val account = Account("a", Environment.MOCK, "nhplug")

    private fun order(status: OrderStatus, account: String = "a", time: Instant = at) =
        OrderRecord(
            OrderIntent(
                "test",
                Environment.MOCK,
                account,
                "005930",
                Side.BUY,
                2,
                50000,
                "test",
                time,
            ),
            status,
        )

    private fun state() =
        AppState(
            selected = account,
            connected = true,
            portfolio = Portfolio(100000, 100000, 0, emptyList(), at),
        )

    @Test
    fun unknownOrdersBlockOnlyOwningAccountAndCountTowardConservativeBudget() {
        val report =
            OperationReview.inspect(
                state()
                    .copy(
                        orders =
                            listOf(
                                order(OrderStatus.UNKNOWN),
                                order(OrderStatus.UNKNOWN, "b"),
                                order(OrderStatus.REJECTED),
                                order(OrderStatus.ACCEPTED, time = at.minusSeconds(86400)),
                            )
                    ),
                at,
            )
        assertEquals(ReviewLevel.BLOCKED, report.items.first { it.id == "unknown" }.level)
        assertEquals("100000", report.committedBuy.toPlainString())
        assertEquals("200000", report.remainingBuy.toPlainString())
        val other =
            OperationReview.inspect(
                state().copy(orders = listOf(order(OrderStatus.UNKNOWN, "b"))),
                at,
            )
        assertEquals(ReviewLevel.READY, other.items.first { it.id == "unknown" }.level)
    }

    @Test
    fun balanceBoundaryFutureTimesAndMissingHistoryAreExplicit() {
        fun balance(now: Instant) =
            OperationReview.inspect(state(), now).items.first { it.id == "balance" }.level
        assertEquals(ReviewLevel.READY, balance(at.plusSeconds(60)))
        assertEquals(ReviewLevel.ATTENTION, balance(at.plusSeconds(61)))
        assertEquals(ReviewLevel.ATTENTION, balance(at.minusSeconds(1)))
        assertEquals(
            ReviewLevel.ATTENTION,
            OperationReview.inspect(state(), at).items.first { it.id == "history" }.level,
        )
    }

    @Test
    fun storageAndReconciliationRemainBlockedEvenWithFreshBalance() {
        val report = OperationReview.inspect(state().copy(storageError = true), at)
        assertEquals(ReviewLevel.BLOCKED, report.items.first { it.id == "storage" }.level)
        assertEquals(ReviewLevel.BLOCKED, report.items.first { it.id == "reconciliation" }.level)
        assertEquals(ReviewLevel.READY, report.items.first { it.id == "balance" }.level)
    }
}

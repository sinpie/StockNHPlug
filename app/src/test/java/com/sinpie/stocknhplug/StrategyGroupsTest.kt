package com.sinpie.stocknhplug

import com.sinpie.stocknhplug.application.*
import com.sinpie.stocknhplug.data.GroupCodec
import com.sinpie.stocknhplug.domain.*
import com.sinpie.stocknhplug.trading.GroupLedger
import java.time.*
import org.junit.Assert.*
import org.junit.Test

class StrategyGroupsTest {
    private val now = Instant.parse("2026-09-21T01:00:00Z")
    private val account = Account("same-account", Environment.MOCK, "nhplug")

    private fun group(id: String) =
        StrategyGroup(
            id = id,
            strategyId = "averaging",
            name = id,
            symbols = listOf(GroupSymbol("005930")),
        )

    private fun order(
        id: String,
        group: String,
        side: Side = Side.BUY,
        qty: Long = 10,
        at: Instant = now,
    ) =
        OrderRecord(
            OrderIntent(
                id,
                Environment.MOCK,
                account.number,
                "005930",
                side,
                qty,
                10000,
                "test",
                at,
                "nhplug",
                "averaging",
                group,
                "test",
            ),
            OrderStatus.ACCEPTED,
            id,
        )

    @Test
    fun sameSymbolGroupsHaveIndependentCostAndQuantity() {
        val orders = listOf(order("a", "g1"), order("b", "g2"))
        val fills =
            listOf(
                GroupFillReport("a", 2, 20000, 20, true, now),
                GroupFillReport("b", 5, 60000, 60, true, now),
            )
        val first = GroupLedger.positions(group("g1"), account, orders, fills).single()
        val second = GroupLedger.positions(group("g2"), account, orders, fills).single()
        assertEquals(2L, first.quantity)
        assertEquals(10010L, first.average)
        assertEquals(5L, second.quantity)
        assertEquals(12012L, second.average)
        assertEquals(979980L, GroupLedger.cash(group("g1"), account, orders, fills))
        assertTrue(
            GroupLedger.positions(group("g1"), account.copy(number = "other"), orders, fills)
                .isEmpty()
        )
    }

    @Test
    fun cumulativeReportsAreIdempotentAndPartialFillIsPending() {
        val orders = listOf(order("a", "g1"))
        val partial = GroupFillReport("a", 2, 20000, 20, false, now)
        val initial = GroupLedger.merge(orders, emptyList(), listOf(partial, partial))
        assertEquals(1, initial.size)
        assertTrue(GroupLedger.hasPending(account, orders, initial))
        val final =
            partial.copy(
                quantity = 4,
                gross = 40000,
                fees = 40,
                terminal = true,
                at = now.plusSeconds(1),
            )
        val merged = GroupLedger.merge(orders, initial, listOf(final))
        assertFalse(GroupLedger.hasPending(account, orders, merged))
        assertEquals(
            4L,
            GroupLedger.positions(group("g1"), account, orders, merged).single().quantity,
        )
        assertEquals(
            1,
            GroupLedger.merge(orders, merged, listOf(final.copy(at = now.plusSeconds(2)))).size,
        )
    }

    @Test
    fun regressingOrUnknownReportsFailClosed() {
        val orders = listOf(order("a", "g1"))
        val previous = GroupFillReport("a", 2, 20000, 20, false, now)
        assertTrue(
            runCatching {
                    GroupLedger.merge(orders, listOf(previous), listOf(previous.copy(quantity = 1)))
                }
                .isFailure
        )
        assertTrue(
            runCatching {
                    GroupLedger.merge(
                        orders,
                        emptyList(),
                        listOf(previous.copy(orderId = "unknown")),
                    )
                }
                .isFailure
        )
    }

    @Test
    fun sellsCannotConsumeAnotherGroupsShares() {
        val orders = listOf(order("a", "g1"), order("b", "g2", Side.SELL, 1, now.plusSeconds(1)))
        val fills =
            listOf(
                GroupFillReport("a", 5, 50000, 0, true, now),
                GroupFillReport("b", 1, 10000, 0, true, now.plusSeconds(1)),
            )
        assertTrue(
            runCatching { GroupLedger.positions(group("g2"), account, orders, fills) }.isFailure
        )
    }

    @Test
    fun realizedPnlAndResidualCostAreConserved() {
        val orders = listOf(order("a", "g1"), order("b", "g1", Side.SELL, 1, now.plusSeconds(1)))
        val fills =
            listOf(
                GroupFillReport("a", 3, 30000, 3, true, now),
                GroupFillReport("b", 1, 12000, 2, true, now.plusSeconds(1)),
            )
        val result = GroupLedger.positions(group("g1"), account, orders, fills).single()
        assertEquals(2L, result.quantity)
        assertEquals(20002L, result.cost)
        assertEquals(1997L, result.realized)
    }

    @Test
    fun sameSymbolAcrossGroupsIsValidButWithinGroupIsNot() {
        val valid = StrategyBook(StrategyBook.defaults().plans, listOf(group("g1"), group("g2")))
        valid.validate()
        assertTrue(
            runCatching {
                    valid
                        .copy(
                            groups =
                                listOf(
                                    group("g1")
                                        .copy(
                                            symbols =
                                                listOf(
                                                    GroupSymbol("005930", 50),
                                                    GroupSymbol("005930", 50),
                                                )
                                        )
                                )
                        )
                        .validate()
                }
                .isFailure
        )
        assertTrue(
            runCatching {
                    valid
                        .copy(
                            groups =
                                listOf(
                                    group("g1").copy(symbols = listOf(GroupSymbol("005930", 101)))
                                )
                        )
                        .validate()
                }
                .isFailure
        )
    }

    @Test
    fun fivePresetsRoundTripWithoutEnablingBuy() {
        assertEquals(5, RecommendationPresets.names.size)
        RecommendationPresets.names.keys.forEach { id ->
            val rule = RecommendationPresets.rule(id)
            rule.validate()
            assertFalse(rule.enabled)
            assertEquals(rule, GroupCodec.readRecommendationRule(GroupCodec.encode(rule)))
        }
        val book = StrategyBook(StrategyBook.defaults().plans, listOf(group("g1")))
        assertEquals(book, GroupCodec.readStrategyBook(GroupCodec.encode(book)))
    }

    @Test
    fun activeRecommendationNeedsAtLeastOneIndicator() {
        val rule =
            RecommendationRule(
                enabled = true,
                trend = false,
                rsi = false,
                momentum = false,
                volume = false,
                volatility = false,
                profitability = false,
                leverage = false,
            )
        assertTrue(runCatching { rule.validate() }.isFailure)
    }

    @Test
    fun monthlyWeekendMovesToMondayAndDoesNotCatchUpAfterwards() {
        val schedule = PurchaseSchedule(enabled = true, day = 19)
        assertEquals("2026-09", schedule.occurrence(now))
        assertNull(schedule.occurrence(now.plusSeconds(86400)))
        assertNull(schedule.occurrence(now.minusSeconds(3600)))
    }

    @Test
    fun monthlyOverflowIntoNextMonthKeepsOriginalOccurrence() {
        val schedule = PurchaseSchedule(enabled = true, day = 31)
        assertEquals("2026-02", schedule.occurrence(Instant.parse("2026-03-02T01:00:00Z")))
    }

    @Test
    fun weeklyAndDailyScheduleUseSeoulTradingWindow() {
        assertEquals(
            "2026-09-21",
            PurchaseSchedule(true, ScheduleFrequency.WEEKLY, 1).occurrence(now),
        )
        assertNull(PurchaseSchedule(true, ScheduleFrequency.WEEKLY, 2).occurrence(now))
        assertNull(
            PurchaseSchedule(true, ScheduleFrequency.WEEKDAYS)
                .occurrence(Instant.parse("2026-09-21T06:15:00Z"))
        )
    }

    @Test
    fun groupScheduleInheritanceAndOffAreExplicit() {
        val plan =
            StrategyBook.defaults()
                .plans
                .first()
                .copy(schedule = PurchaseSchedule(enabled = true, budget = 200000))
        assertEquals(200000L, group("g1").effectiveSchedule(plan).budget)
        assertFalse(
            group("g1")
                .copy(scheduleOverride = ScheduleOverride.OFF)
                .effectiveSchedule(plan)
                .enabled
        )
        assertEquals(
            50000L,
            group("g1")
                .copy(
                    scheduleOverride = ScheduleOverride.CUSTOM,
                    schedule = PurchaseSchedule(enabled = true, budget = 50000),
                )
                .effectiveSchedule(plan)
                .budget,
        )
    }

    @Test
    fun averagingUsesOwnAverageAndAdditionalBuyLimit() {
        val q = Quote("005930", 9500, 9500, 9510, now, now, true)
        val p = GroupPosition("005930", 10, 100000, 0, 1)
        val context = GroupContext(group("g1"), listOf(p), mapOf(q.symbol to q), 100000)
        assertEquals(
            9700L,
            AveragingDownAlgorithm().decide(context).single { it.side == Side.BUY }.targetPrice,
        )
        assertTrue(
            AveragingDownAlgorithm()
                .decide(context.copy(positions = listOf(p.copy(buys = 4))))
                .none { it.side == Side.BUY }
        )
        assertTrue(
            AveragingDownAlgorithm()
                .decide(context.copy(positions = listOf(p.copy(cost = 90000))))
                .any { it.side == Side.BUY && it.targetPrice == 8730L }
        )
    }

    @Test
    fun rebalanceUsesGroupNavAndTargetWeights() {
        val q = Quote("005930", 10000, 10000, 10010, now, now, true)
        val group = group("g1").copy(symbols = listOf(GroupSymbol("005930", 50)))
        val context =
            GroupContext(
                group,
                listOf(GroupPosition("005930", 10, 100000, 0, 1)),
                mapOf(q.symbol to q),
                0,
            )
        val decision = RebalancingAlgorithm().decide(context).single()
        assertEquals(Side.SELL, decision.side)
        assertEquals(5L, decision.quantity)
    }
}

package com.sinpie.stocknhplug

import com.sinpie.stocknhplug.domain.*
import com.sinpie.stocknhplug.trading.TradingEngine
import java.time.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class TradingEngineTest {
    private val now = Instant.parse("2026-09-21T01:00:00Z")
    private val account = Account("12345678901", Environment.MOCK, "nhplug")
    private val portfolio = Portfolio(1_000_000, 1_000_000, 0, emptyList(), now)
    private val quote = Quote("005930", 10_000, 10_000, 10_010, now, now, true)

    private class Journal : OrderJournal {
        val list = mutableListOf<OrderRecord>()

        override fun reserve(intent: OrderIntent): Boolean {
            list += OrderRecord(intent, OrderStatus.SUBMITTING)
            return true
        }

        override fun update(id: String, status: OrderStatus, brokerNumber: String) {
            val i = list.indexOfFirst { it.intent.id == id }
            list[i] = list[i].copy(status = status, brokerNumber = brokerNumber)
        }

        override fun records() = list.toList()
    }

    private class FakeBroker : Broker {
        override val id = "nhplug"
        override val environment = Environment.MOCK
        var sent = 0
        var fail = false
        var beforeSend: () -> Unit = {}
        var afterAvailable: () -> Unit = {}

        override suspend fun accounts() = emptyList<Account>()

        override suspend fun portfolio(account: Account): Portfolio = error("unused")

        override suspend fun dailyPnl(account: Account) = emptyList<DailyPnl>()

        override suspend fun available(
            account: Account,
            symbol: String,
            side: Side,
            price: Long,
        ): Long {
            afterAvailable()
            return 100L
        }

        override suspend fun place(account: Account, intent: OrderIntent): String {
            beforeSend()
            sent++
            if (fail) error("timeout")
            return "123"
        }

        override suspend fun executions(account: Account, date: LocalDate) = emptyList<Execution>()
    }

    @Test
    fun marketCloseDuringAvailabilityLookupCannotDispatchOrReserve() = runBlocking {
        var clock = Instant.parse("2026-09-21T06:14:59Z")
        val p = portfolio.copy(at = clock)
        val q = quote.copy(receivedAt = clock, exchangeAt = clock)
        val broker = FakeBroker().apply { afterAvailable = { clock = clock.plusSeconds(2) } }
        val journal = Journal()
        val engine = TradingEngine(broker, journal) { clock }.also { it.start(p) }
        assertTrue(
            runCatching { engine.submit(account, p, q, Side.BUY, "test", Strategy()) }.isFailure
        )
        assertEquals(0, broker.sent)
        assertTrue(journal.records().isEmpty())
    }

    @Test
    fun balanceExpiresWhileQuoteRemainsFreshDuringAvailabilityLookup() = runBlocking {
        var clock = now
        val p = portfolio.copy(at = now.minusSeconds(59))
        val broker = FakeBroker().apply { afterAvailable = { clock = now.plusSeconds(2) } }
        val journal = Journal()
        val engine = TradingEngine(broker, journal) { clock }.also { it.start(p) }
        assertTrue(
            runCatching { engine.submit(account, p, quote, Side.BUY, "test", Strategy()) }.isFailure
        )
        assertEquals(0, broker.sent)
        assertTrue(journal.records().isEmpty())
    }

    @Test
    fun journalDelayIsRejectedBeforeNetworkWithoutClaimingBrokerOutcomeUnknown() = runBlocking {
        var clock = now
        val journal = Journal()
        val slow =
            object : OrderJournal by journal {
                override fun reserve(intent: OrderIntent): Boolean {
                    val saved = journal.reserve(intent)
                    clock = clock.plusSeconds(16)
                    return saved
                }
            }
        val broker = FakeBroker()
        val engine = TradingEngine(broker, slow) { clock }.also { it.start(portfolio) }
        assertTrue(
            runCatching { engine.submit(account, portfolio, quote, Side.BUY, "test", Strategy()) }
                .isFailure
        )
        assertEquals(0, broker.sent)
        assertFalse(engine.running)
        assertEquals(OrderStatus.REJECTED, journal.records().single().status)
    }

    @Test
    fun dispatchDeadlineRetainsOriginalQuoteExpiryAndExactFiveSeconds() = runBlocking {
        val broker = FakeBroker()
        val journal = Journal()
        val engine = TradingEngine(broker, journal) { now }.also { it.start(portfolio) }
        val aged = quote.copy(receivedAt = now.minusSeconds(14), exchangeAt = now.minusSeconds(14))
        val result = engine.submit(account, portfolio, aged, Side.BUY, "test", Strategy())
        assertEquals(now.plusSeconds(1), result.intent.expiresAt)
        assertFalse(result.intent.dispatchable(now.plusSeconds(1).plusNanos(1)))
        val normal = result.intent.copy(expiresAt = now.plusSeconds(30))
        assertTrue(normal.dispatchable(now.plusSeconds(5)))
        assertFalse(normal.dispatchable(now.plusSeconds(5).plusNanos(1)))
        assertFalse(normal.dispatchable(now.minusNanos(1)))
    }

    @Test
    fun parkingCycleCannotReplayAcrossDatesOrUseAnotherStrategyId() = runBlocking {
        val b = FakeBroker()
        val j = Journal()
        j.list +=
            OrderRecord(
                OrderIntent(
                    "old",
                    Environment.MOCK,
                    account.number,
                    quote.symbol,
                    Side.BUY,
                    1,
                    10000,
                    "test",
                    now.minusSeconds(86400),
                    account.brokerId,
                    ParkingPolicy.STRATEGY_ID,
                    ParkingPolicy.GROUP_ID,
                    "parking:7",
                ),
                OrderStatus.ACCEPTED,
            )
        val e = TradingEngine(b, j) { now }.also { it.start(portfolio) }
        val allocation =
            GroupAllocation(
                ParkingPolicy.STRATEGY_ID,
                ParkingPolicy.GROUP_ID,
                "parking:7",
                1,
                0,
                100000,
            )
        assertTrue(
            runCatching {
                    e.submit(account, portfolio, quote, Side.BUY, "test", Strategy(), allocation)
                }
                .isFailure
        )
        assertTrue(
            runCatching {
                    e.submit(
                        account,
                        portfolio,
                        quote,
                        Side.BUY,
                        "test",
                        Strategy(),
                        allocation.copy(strategyId = "other", occurrence = "parking:8"),
                    )
                }
                .isFailure
        )
        assertEquals(0, b.sent)
    }

    @Test
    fun replacementExitPolicyReceivesTrackingButCannotBypassRiskChecks() = runBlocking {
        val b = FakeBroker()
        var calls = 0
        val custom = ExitPolicy { _, _, peak, _ ->
            calls++
            assertEquals(11000L, peak)
            "custom exit"
        }
        val e = TradingEngine(b, Journal(), custom) { now }
        val holding = Holding("005930", "name", 10, 10000, 11000, 0)
        assertEquals("custom exit", e.exitReason(holding, quote, Strategy()))
        assertEquals(1, calls)
        assertNull(e.exitReason(holding, quote.copy(regular = false), Strategy()))
        assertEquals(1, calls)
        e.start(portfolio)
        assertTrue(
            runCatching {
                    e.submit(
                        account,
                        portfolio.copy(holdings = listOf(holding)),
                        quote,
                        Side.SELL,
                        "custom exit",
                        Strategy(),
                    )
                }
                .isFailure
        )
        assertEquals(0, b.sent)
    }

    @Test
    fun differentBrokerAccountCannotDispatch() = runBlocking {
        val b = FakeBroker()
        val e = TradingEngine(b, Journal()) { now }
        e.start(portfolio)
        assertTrue(
            runCatching {
                    e.submit(
                        account.copy(brokerId = "other"),
                        portfolio,
                        quote,
                        Side.BUY,
                        "test",
                        Strategy(),
                    )
                }
                .isFailure
        )
        assertEquals(0, b.sent)
    }

    @Test
    fun anotherBrokersPendingOrderDoesNotMixWithThisBroker() = runBlocking {
        val b = FakeBroker()
        val j = Journal()
        j.reserve(
            OrderIntent(
                "other-order",
                Environment.MOCK,
                account.number,
                quote.symbol,
                Side.BUY,
                1,
                10000,
                "test",
                now,
                "other",
            )
        )
        val e = TradingEngine(b, j) { now }
        e.start(portfolio)
        assertEquals(
            OrderStatus.ACCEPTED,
            e.submit(account, portfolio, quote, Side.BUY, "test", Strategy()).status,
        )
        assertEquals(1, b.sent)
    }

    @Test
    fun storageFailureAfterDispatchStillStops() = runBlocking {
        val b = FakeBroker()
        val delegate = Journal()
        val broken =
            object : OrderJournal by delegate {
                override fun update(id: String, status: OrderStatus, brokerNumber: String) {
                    error("disk unavailable")
                }
            }
        val e = TradingEngine(b, broken) { now }
        e.start(portfolio)
        assertTrue(
            runCatching { e.submit(account, portfolio, quote, Side.BUY, "test", Strategy()) }
                .isFailure
        )
        assertFalse(e.running)
        assertEquals(1, b.sent)
        assertEquals(OrderStatus.SUBMITTING, delegate.records().single().status)
        assertTrue(
            runCatching {
                    e.submit(
                        account,
                        portfolio,
                        quote.copy(symbol = "000660"),
                        Side.BUY,
                        "test",
                        Strategy(),
                    )
                }
                .isFailure
        )
        assertEquals(1, b.sent)
    }

    @Test
    fun runningSessionCannotResetLossBaseline() {
        val e = TradingEngine(FakeBroker(), Journal()) { now }
        e.start(portfolio)
        assertTrue(runCatching { e.start(portfolio.copy(equity = 970000)) }.isFailure)
        assertTrue(e.running)
    }

    @Test
    fun subsecondQuoteExpiryIsNotRoundedDown() {
        assertTrue(
            quote
                .copy(receivedAt = now.minusSeconds(15), exchangeAt = now.minusSeconds(15))
                .fresh(now)
        )
        assertFalse(quote.copy(receivedAt = now.minusSeconds(15).minusNanos(1)).fresh(now))
        assertFalse(quote.copy(exchangeAt = now.minusSeconds(15).minusNanos(1)).fresh(now))
        assertFalse(quote.copy(receivedAt = now.plusNanos(1)).fresh(now))
    }

    @Test
    fun portfolioExpiryAndFutureTimesBlockDispatch() = runBlocking {
        for (at in listOf(now.minusSeconds(60).minusNanos(1), now.plusNanos(1))) {
            val b = FakeBroker()
            val e = TradingEngine(b, Journal()) { now }
            e.start(portfolio)
            assertTrue(
                runCatching {
                        e.submit(
                            account,
                            portfolio.copy(at = at),
                            quote,
                            Side.BUY,
                            "test",
                            Strategy(),
                        )
                    }
                    .isFailure
            )
            assertEquals(0, b.sent)
        }
    }

    @Test
    fun exitIgnoresDifferentSymbolAndNonRegularQuotes() {
        val e = TradingEngine(FakeBroker(), Journal()) { now }
        val holding = Holding("005930", "name", 10, 10000, 10000, 0)
        assertNull(e.exitReason(holding, quote.copy(symbol = "000660", price = 1), Strategy()))
        assertNull(e.exitReason(holding, quote.copy(regular = false, price = 1), Strategy()))
    }

    @Test
    fun differentGroupsCanBuySameSymbolWhileOwnDuplicateIsBlocked() = runBlocking {
        val b = FakeBroker()
        val j = Journal()
        val e = TradingEngine(b, j) { now }
        e.start(portfolio)
        val first = GroupAllocation("averaging", "group-one", "schedule:2026-09", 1, 0, 100000)
        e.submit(account, portfolio, quote, Side.BUY, "group", Strategy(), first)
        e.submit(
            account,
            portfolio,
            quote,
            Side.BUY,
            "group",
            Strategy(),
            first.copy(groupId = "group-two"),
        )
        assertTrue(
            runCatching {
                    e.submit(account, portfolio, quote, Side.BUY, "group", Strategy(), first)
                }
                .isFailure
        )
        assertEquals(2, b.sent)
        assertEquals(setOf("group-one", "group-two"), j.records().map { it.intent.groupId }.toSet())
    }

    @Test
    fun groupedSellIsCappedToGroupOwnership() = runBlocking {
        val b = FakeBroker()
        val e = TradingEngine(b, Journal()) { now }
        e.start(portfolio)
        val p = portfolio.copy(holdings = listOf(Holding("005930", "test", 20, 10000, 10000, 0)))
        val result =
            e.submit(
                account,
                p,
                quote,
                Side.SELL,
                "group",
                Strategy(manageHoldings = true),
                GroupAllocation("rebalance", "g1", "rebalance", 10, 3, 0),
            )
        assertEquals(3L, result.intent.quantity)
    }

    @Test
    fun durableIntentExistsBeforeNetwork() = runBlocking {
        val b = FakeBroker()
        val j = Journal()
        val e = TradingEngine(b, j) { now }
        e.start(portfolio)
        b.beforeSend = { assertEquals(OrderStatus.SUBMITTING, j.list.single().status) }
        val result = e.submit(account, portfolio, quote, Side.BUY, "test", Strategy())
        assertEquals(9L, result.intent.quantity)
        assertEquals(OrderStatus.ACCEPTED, result.status)
    }

    @Test
    fun timeoutStopsAndNeverRetries() = runBlocking {
        val b = FakeBroker().apply { fail = true }
        val j = Journal()
        val e = TradingEngine(b, j) { now }
        e.start(portfolio)
        assertTrue(
            runCatching { e.submit(account, portfolio, quote, Side.BUY, "test", Strategy()) }
                .isFailure
        )
        assertFalse(e.running)
        assertEquals(1, b.sent)
        assertEquals(OrderStatus.UNKNOWN, j.list.single().status)
        e.start(portfolio)
        assertTrue(
            runCatching {
                    e.submit(
                        account,
                        portfolio,
                        quote.copy(symbol = "000660"),
                        Side.BUY,
                        "test",
                        Strategy(),
                    )
                }
                .isFailure
        )
        assertEquals(1, b.sent)
    }

    @Test
    fun concurrentSignalsProduceOnlyOneOrder() = runBlocking {
        val b = FakeBroker()
        val j = Journal()
        val e = TradingEngine(b, j) { now }
        e.start(portfolio)
        coroutineScope {
            (1..10)
                .map {
                    async {
                        runCatching {
                            e.submit(account, portfolio, quote, Side.BUY, "test", Strategy())
                        }
                    }
                }
                .awaitAll()
        }
        assertEquals(1, b.sent)
    }

    @Test
    fun staleOrFutureQuotesNeverTrade() = runBlocking {
        for (q in
            listOf(
                quote.copy(exchangeAt = now.minusSeconds(20)),
                quote.copy(receivedAt = now.plusSeconds(3)),
                quote.copy(exchangeAt = now.plusSeconds(3)),
            )) {
            val b = FakeBroker()
            val e = TradingEngine(b, Journal()) { now }
            e.start(portfolio)
            assertTrue(
                runCatching { e.submit(account, portfolio, q, Side.BUY, "test", Strategy()) }
                    .isFailure
            )
            assertEquals(0, b.sent)
        }
    }

    @Test
    fun wrongEnvironmentNeverTrades() = runBlocking {
        val b = FakeBroker()
        val e = TradingEngine(b, Journal()) { now }
        e.start(portfolio)
        assertTrue(
            runCatching {
                    e.submit(
                        account.copy(environment = Environment.LIVE),
                        portfolio,
                        quote,
                        Side.BUY,
                        "test",
                        Strategy(),
                    )
                }
                .isFailure
        )
        assertEquals(0, b.sent)
    }

    @Test
    fun weekendNeverTrades() = runBlocking {
        val b = FakeBroker()
        val e = TradingEngine(b, Journal()) { Instant.parse("2026-09-19T01:00:00Z") }
        e.start(portfolio)
        assertTrue(
            runCatching { e.submit(account, portfolio, quote, Side.BUY, "test", Strategy()) }
                .isFailure
        )
        assertEquals(0, b.sent)
    }

    @Test
    fun lossLimitStopsSession() = runBlocking {
        val b = FakeBroker()
        val e = TradingEngine(b, Journal()) { now }
        e.start(portfolio)
        assertTrue(
            runCatching {
                    e.submit(
                        account,
                        portfolio.copy(equity = 970000),
                        quote,
                        Side.BUY,
                        "test",
                        Strategy(),
                    )
                }
                .isFailure
        )
        assertFalse(e.running)
        assertEquals(0, b.sent)
    }

    @Test
    fun pendingBuysCountAgainstPositionLimit() = runBlocking {
        val b = FakeBroker()
        val j = Journal()
        val e = TradingEngine(b, j) { now }
        e.start(portfolio)
        e.submit(account, portfolio, quote, Side.BUY, "test", Strategy(maxPositions = 1))
        assertTrue(
            runCatching {
                    e.submit(
                        account,
                        portfolio,
                        quote.copy(symbol = "000660"),
                        Side.BUY,
                        "test",
                        Strategy(maxPositions = 1),
                    )
                }
                .isFailure
        )
        assertEquals(1, b.sent)
    }

    @Test
    fun sellNeedsExplicitHoldingsConsent() = runBlocking {
        val b = FakeBroker()
        val e = TradingEngine(b, Journal()) { now }
        e.start(portfolio)
        val p = portfolio.copy(holdings = listOf(Holding("005930", "name", 10, 10000, 10000, 0)))
        assertTrue(
            runCatching { e.submit(account, p, quote, Side.SELL, "test", Strategy()) }.isFailure
        )
        assertEquals(0, b.sent)
    }

    @Test
    fun stopAndTakeProfitFollowConfiguredThresholds() {
        val e = TradingEngine(FakeBroker(), Journal()) { now }
        val h = Holding("005930", "name", 10, 10000, 10000, 0)
        assertEquals("손절 조건", e.exitReason(h, quote.copy(price = 9600), Strategy()))
        assertEquals("익절 조건", e.exitReason(h, quote.copy(price = 10700), Strategy()))
    }
}

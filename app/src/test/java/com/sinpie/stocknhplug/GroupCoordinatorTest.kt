package com.sinpie.stocknhplug

import com.sinpie.stocknhplug.application.*
import com.sinpie.stocknhplug.domain.*
import com.sinpie.stocknhplug.research.*
import com.sinpie.stocknhplug.trading.TradingEngine
import java.math.BigDecimal
import java.time.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** 합성 자료와 가짜 증권 포트만 사용하는 응용 계약 테스트. 실제 주문·인증 요청 없음. */
class GroupCoordinatorTest {
    private val now = Instant.parse("2026-09-21T01:00:00Z")
    private val account = Account("test", Environment.MOCK, "test-broker")
    private val portfolio = Portfolio(1_000_000, 1_000_000, 0, emptyList(), now)
    private val quote = Quote("005930", 10000, 10000, 10010, now, now, true)

    private class MemoryStore : ApplicationStorage {
        var book =
            StrategyBook(
                StrategyBook.defaults().plans.map {
                    it.copy(
                        enabled = true,
                        schedule = PurchaseSchedule(true, ScheduleFrequency.WEEKDAYS),
                    )
                },
                listOf(
                    StrategyGroup(
                        id = "g1",
                        strategyId = "averaging",
                        name = "test",
                        enabled = true,
                        symbols = listOf(GroupSymbol("005930")),
                    )
                ),
            )
        val orders = mutableListOf<OrderRecord>()
        var fills = emptyList<GroupFillReport>()

        override fun strategyBook() = book

        override fun saveStrategyBook(book: StrategyBook) {
            this.book = book
        }

        override fun groupFills() = fills

        override fun saveGroupFills(reports: List<GroupFillReport>) {
            fills = reports
        }

        override fun settings() = Strategy()

        override fun saveSettings(settings: Strategy) {}

        override fun events() = emptyList<Event>()

        override fun log(level: String, message: String) {}

        override fun snapshots() = emptyList<HoldingSnapshot>()

        override fun snapshot(account: Account, environment: Environment, portfolio: Portfolio) {}

        override fun hasCredentials() = false

        override fun saveCredentials(key: String, secret: String, dart: String) = error("not used")

        override fun clear() = error("not used")

        override fun reserve(intent: OrderIntent): Boolean {
            orders += OrderRecord(intent, OrderStatus.SUBMITTING)
            return true
        }

        override fun update(id: String, status: OrderStatus, brokerNumber: String) {
            val n = orders.indexOfFirst { it.intent.id == id }
            orders[n] = orders[n].copy(status = status, brokerNumber = brokerNumber)
        }

        override fun records() = orders.toList()
    }

    private class FakeBroker : Broker {
        override val id = "test-broker"
        override val environment = Environment.MOCK
        var sent = 0

        override suspend fun accounts() = emptyList<Account>()

        override suspend fun portfolio(account: Account): Portfolio = error("not used")

        override suspend fun available(account: Account, symbol: String, side: Side, price: Long) =
            100L

        override suspend fun place(account: Account, intent: OrderIntent): String {
            sent++
            return "test-$sent"
        }

        override suspend fun executions(account: Account, date: LocalDate) = emptyList<Execution>()

        override suspend fun dailyPnl(account: Account) = emptyList<DailyPnl>()
    }

    private val source =
        object : GroupExecutionSource {
            override suspend fun reconcile(account: Account, orders: List<OrderRecord>) =
                emptyList<GroupFillReport>()
        }

    private fun evidence(adjusted: Boolean = true): ResearchEvidence {
        val today = now.atZone(SEOUL).toLocalDate()
        val bars =
            (1..100).map {
                Candle(
                    today.minusDays((101 - it).toLong()),
                    10000.0 + it,
                    10200.0 + it,
                    9800.0 + it,
                    100.0,
                )
            }
        return ResearchEvidence(
            "005930",
            PriceHistory("005930", bars, adjusted, DataSource.LICENSED_ADJUSTED_PRICES, now),
            FinancialReport(
                2025,
                "11011",
                "test",
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
    }

    @Test
    fun staleStrategyQuoteCannotBeMisreadAsIdleCash() = runBlocking {
        val store = MemoryStore()
        store.book = store.book.copy(parking = ParkingPolicy(true, "005940"))
        val broker = FakeBroker()
        val engine = TradingEngine(broker, store) { now }.also { it.start(portfolio) }
        val coordinator =
            GroupTradingCoordinator(
                store,
                source,
                GroupAlgorithmRegistry.defaults(),
                engine,
                ImmediateTestGate(),
            )
        val q = quote.copy(symbol = "005940")
        val e =
            evidence().let {
                it.copy(symbol = q.symbol, prices = it.prices!!.copy(symbol = q.symbol))
            }
        assertNull(
            coordinator.tick(
                account,
                portfolio,
                mapOf(q.symbol to q),
                listOf(e),
                Strategy(manageHoldings = true),
                now,
            )
        )
        assertEquals(0, broker.sent)
    }

    @Test
    fun parkingSaleWaitsForTerminalFillAndUpdatedCashBeforeStrategyBuy() = runBlocking {
        val store = MemoryStore()
        store.book = store.book.copy(parking = ParkingPolicy(true, "005940"))
        val seed =
            OrderIntent(
                "seed",
                Environment.MOCK,
                account.number,
                "005940",
                Side.BUY,
                100,
                10000,
                "test",
                now.minusSeconds(600),
                account.brokerId,
                ParkingPolicy.STRATEGY_ID,
                ParkingPolicy.GROUP_ID,
                "parking:0",
            )
        // 전일 취득분은 오늘의 공통 매수 예산을 소비하지 않는다.
        store.orders +=
            OrderRecord(
                seed.copy(at = now.minusSeconds(86400)),
                OrderStatus.ACCEPTED,
                "seed-broker",
            )
        store.fills =
            listOf(GroupFillReport(seed.id, 100, 1_000_000, 0, true, now.minusSeconds(600)))
        val p =
            portfolio.copy(
                cash = 50_000,
                equity = 1_050_000,
                holdings = listOf(Holding("005940", "test", 100, 10000, 10000, 0)),
            )
        val parkingQuote = quote.copy(symbol = "005940", ask = 10000)
        var quotes = mapOf(quote.symbol to quote, parkingQuote.symbol to parkingQuote)
        val broker = FakeBroker()
        val engine = TradingEngine(broker, store) { now }.also { it.start(p) }
        val gate = com.sinpie.stocknhplug.trading.NamuExecutionGate()
        fun observe(q: Quote) =
            gate.observe(
                PriceSnapshot(
                    q,
                    MarketRules(now.atZone(SEOUL).toLocalDate(), 7000, 13000, InstrumentKind.STOCK),
                    PriceSource.REST,
                ),
                now,
            )
        observe(quote)
        val coordinator =
            GroupTradingCoordinator(store, source, GroupAlgorithmRegistry.defaults(), engine, gate)
        val limits = Strategy(manageHoldings = true)
        // 가상 매수만 등록했을 때는 파킹을 팔지 않는다. 실제 반전 후에만 자금을 확보한다.
        assertNull(coordinator.tick(account, p, quotes, listOf(evidence()), limits, now))
        assertEquals(0, broker.sent)
        val low = quote.copy(price = 9000, bid = 9000, ask = 9000)
        observe(low)
        quotes = quotes + (low.symbol to low)
        assertNull(coordinator.tick(account, p, quotes, listOf(evidence()), limits, now))
        val rebound = quote.copy(price = 9800, bid = 9800, ask = 9800)
        observe(rebound)
        quotes = quotes + (rebound.symbol to rebound)
        val sale = coordinator.tick(account, p, quotes, listOf(evidence()), limits, now)!!
        assertEquals(ParkingPolicy.GROUP_ID, sale.intent.groupId)
        assertEquals(Side.SELL, sale.intent.side)
        assertNull(coordinator.tick(account, p, quotes, listOf(evidence()), limits, now))
        store.fills =
            store.fills +
                GroupFillReport(
                    sale.intent.id,
                    sale.intent.quantity,
                    sale.intent.quantity * 10000,
                    1000,
                    true,
                    now,
                )
        // 체결 확인만 있고 잔고 현금이 아직 증가하지 않았으면 전략 주문은 나가지 않는다.
        assertNull(coordinator.tick(account, p, quotes, listOf(evidence()), limits, now))
        assertEquals(1, broker.sent)
        val funded =
            p.copy(cash = 149_000, holdings = listOf(p.holdings.single().copy(quantity = 90)))
        val buy = coordinator.tick(account, funded, quotes, listOf(evidence()), limits, now)!!
        assertEquals("g1", buy.intent.groupId)
        assertEquals(Side.BUY, buy.intent.side)
        assertEquals(2, broker.sent)
    }

    @Test
    fun idleParkingBuyStillRequiresResearchEvidence() = runBlocking {
        val store = MemoryStore()
        store.book = store.book.copy(groups = emptyList(), parking = ParkingPolicy(true, "005940"))
        val broker = FakeBroker()
        val engine = TradingEngine(broker, store) { now }.also { it.start(portfolio) }
        val coordinator =
            GroupTradingCoordinator(
                store,
                source,
                GroupAlgorithmRegistry.defaults(),
                engine,
                ImmediateTestGate(),
            )
        val q = quote.copy(symbol = "005940")
        assertNull(
            coordinator.tick(
                account,
                portfolio,
                mapOf(q.symbol to q),
                emptyList(),
                Strategy(manageHoldings = true),
                now,
            )
        )
        val e =
            evidence().let {
                it.copy(symbol = q.symbol, prices = it.prices!!.copy(symbol = q.symbol))
            }
        val buy =
            coordinator.tick(
                account,
                portfolio,
                mapOf(q.symbol to q),
                listOf(e),
                Strategy(manageHoldings = true),
                now,
            )!!
        assertEquals(ParkingPolicy.GROUP_ID, buy.intent.groupId)
        assertEquals(Side.BUY, buy.intent.side)
    }

    @Test
    fun absentReconciliationCapabilityBlocksBeforeOrder() = runBlocking {
        val store = MemoryStore()
        val broker = FakeBroker()
        val engine = TradingEngine(broker, store) { now }
        engine.start(portfolio)
        val coordinator =
            GroupTradingCoordinator(
                store,
                null,
                GroupAlgorithmRegistry.defaults(),
                engine,
                ImmediateTestGate(),
            )
        assertTrue(
            runCatching {
                    coordinator.tick(
                        account,
                        portfolio,
                        mapOf(quote.symbol to quote),
                        listOf(evidence()),
                        Strategy(),
                        now,
                    )
                }
                .isFailure
        )
        assertEquals(0, broker.sent)
    }

    @Test
    fun scheduleDispatchIsScopedAndWaitsForFinalFillBeforeNextOrder() = runBlocking {
        val store = MemoryStore()
        val broker = FakeBroker()
        val engine = TradingEngine(broker, store) { now }
        engine.start(portfolio)
        val coordinator =
            GroupTradingCoordinator(
                store,
                source,
                GroupAlgorithmRegistry.defaults(),
                engine,
                ImmediateTestGate(),
            )
        val first =
            coordinator.tick(
                account,
                portfolio,
                mapOf(quote.symbol to quote),
                listOf(evidence()),
                Strategy(),
                now,
            )!!
        assertEquals("g1", first.intent.groupId)
        assertEquals("schedule:2026-09-21", first.intent.occurrence)
        assertNull(
            coordinator.tick(
                account,
                portfolio,
                mapOf(quote.symbol to quote),
                listOf(evidence()),
                Strategy(),
                now,
            )
        )
        assertEquals(1, broker.sent)
    }

    @Test
    fun scheduledBuyCannotBypassMissingAdjustedHistory() = runBlocking {
        val store = MemoryStore()
        val broker = FakeBroker()
        val engine = TradingEngine(broker, store) { now }
        engine.start(portfolio)
        val coordinator =
            GroupTradingCoordinator(
                store,
                source,
                GroupAlgorithmRegistry.defaults(),
                engine,
                ImmediateTestGate(),
            )
        assertNull(
            coordinator.tick(
                account,
                portfolio,
                mapOf(quote.symbol to quote),
                listOf(evidence(false)),
                Strategy(),
                now,
            )
        )
        assertEquals(0, broker.sent)
    }

    @Test
    fun recommendationTogglesAndThresholdsAreAppliedWithoutSkippingDataGate() {
        val rule =
            RecommendationRule(
                enabled = true,
                trend = false,
                rsi = false,
                momentum = true,
                momentumMin = 0.0,
                volatility = false,
            )
        assertTrue(GroupRecommendation.matches(rule, evidence(), now))
        assertFalse(GroupRecommendation.matches(rule.copy(momentumMin = 100.0), evidence(), now))
        assertFalse(GroupRecommendation.matches(rule.copy(enabled = false), evidence(), now))
        assertFalse(GroupRecommendation.matches(rule, evidence(false), now))
    }
}

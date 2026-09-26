package com.sinpie.stocknhplug

import com.sinpie.stocknhplug.application.*
import com.sinpie.stocknhplug.domain.*
import com.sinpie.stocknhplug.research.*
import com.sinpie.stocknhplug.trading.NamuExecutionGate
import java.time.*
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/** 정지·지연 응답의 순서를 가상 dispatcher에서 재현한다. 실제 API나 주문은 호출하지 않는다. */
@OptIn(ExperimentalCoroutinesApi::class)
class ControllerAsyncTest {
    private val account = Account("test", Environment.MOCK, "fake")
    private val p = Portfolio(100000, 100000, 0, emptyList(), Instant.now())

    private class Store : ApplicationStorage {
        var book = StrategyBook.defaults()
        var snapshotsWritten = 0

        override fun strategyBook() = book

        override fun saveStrategyBook(book: StrategyBook) {
            this.book = book
        }

        override fun groupFills() = emptyList<GroupFillReport>()

        override fun saveGroupFills(reports: List<GroupFillReport>) {}

        override fun settings() = Strategy()

        override fun saveSettings(settings: Strategy) {}

        override fun events() = emptyList<Event>()

        override fun log(level: String, message: String) {}

        override fun snapshots() = emptyList<HoldingSnapshot>()

        override fun snapshot(account: Account, environment: Environment, portfolio: Portfolio) {
            snapshotsWritten++
        }

        override fun hasCredentials() = true

        override fun saveCredentials(key: String, secret: String, dart: String) {}

        override fun clear() {}

        override fun records() = emptyList<OrderRecord>()

        override fun reserve(intent: OrderIntent): Boolean = error("No orders in UI tests")

        override fun update(id: String, status: OrderStatus, brokerNumber: String) =
            error("No orders in UI tests")
    }

    private inner class FakeBroker : Broker {
        override val id = "fake"
        override val environment = Environment.MOCK
        var accountsBlock: suspend () -> List<Account> = { listOf(account) }
        var portfolioBlock: suspend () -> Portfolio = { p }
        var failExecutions = false
        var pnlRows = emptyList<DailyPnl>()

        override suspend fun accounts() = accountsBlock()

        override suspend fun portfolio(account: Account) = portfolioBlock()

        override suspend fun executions(account: Account, date: LocalDate): List<Execution> {
            check(!failExecutions)
            return emptyList()
        }

        override suspend fun dailyPnl(account: Account) = pnlRows

        override suspend fun available(account: Account, symbol: String, side: Side, price: Long) =
            error("No orders")

        override suspend fun place(account: Account, intent: OrderIntent): String =
            error("No orders")
    }

    private class Stream : MarketStream {
        override suspend fun connect(symbols: List<String>) {}

        override fun replaceSubscriptions(symbols: Set<String>) {}

        override fun acknowledged() = emptySet<String>()

        override fun isConnected() = false

        override fun close() {}
    }

    private inner class Harness(dispatcher: CoroutineDispatcher, bound: Account? = null) {
        val broker = FakeBroker()
        val store = Store()
        var creates = 0
        val messages = mutableListOf<(String) -> Unit>()
        var history: suspend (String) -> PriceHistory = { error("No research") }
        val controller =
            TradingController(
                store,
                SessionFactory { _, event, _ ->
                    creates++
                    messages += event
                    BrokerSession(
                        broker,
                        Stream(),
                        ResearchRepository(
                            PriceHistoryProvider { history(it) },
                            null,
                            UnlicensedNewsProvider(),
                        ),
                    )
                },
                TechnicalStrategy(),
                NamuExecutionGate(),
                dispatcher = dispatcher,
                ioDispatcher = dispatcher,
                boundAccount = bound,
            )
    }

    @Test
    fun failedAnalysisWithNewConfigurationCannotKeepOldEvidence() = runTest {
        val h = Harness(StandardTestDispatcher(testScheduler))
        h.history = { PriceHistory(it, emptyList(), false, DataSource.NHPLUG, Instant.now()) }
        h.controller.connect()
        runCurrent()
        h.controller.analyze("", 2024, "11011")
        runCurrent()
        assertTrue(h.controller.state.value.research.isNotEmpty())
        h.history = { error("unavailable") }
        h.controller.analyze("", 2025, "11012")
        runCurrent()
        assertEquals(2025, h.controller.state.value.settings.research.year)
        assertTrue(h.controller.state.value.messageError)
        assertTrue(h.controller.state.value.research.isEmpty())
        assertTrue(h.controller.state.value.candidates.isEmpty())
        h.controller.dispose()
    }

    @Test
    fun boundControllerRejectsMissingAccountAndNeverSwitchesOwnership() = runTest {
        val other = account.copy(number = "other")
        val h = Harness(StandardTestDispatcher(testScheduler), other)
        h.controller.connect()
        runCurrent()
        assertFalse(h.controller.state.value.connected)
        assertEquals(0, h.store.snapshotsWritten)
        h.broker.accountsBlock = { listOf(account, other) }
        h.controller.connect()
        runCurrent()
        assertEquals(other, h.controller.state.value.selected)
        h.controller.select(account)
        runCurrent()
        assertEquals(other, h.controller.state.value.selected)
        assertEquals(1, h.store.snapshotsWritten)
    }

    @Test
    fun rapidDoubleTapAdmitsOneRequestAndCancelBeforeStartClearsBusy() = runTest {
        val h = Harness(StandardTestDispatcher(testScheduler))
        h.controller.connect()
        h.controller.connect()
        assertTrue(h.controller.state.value.busy)
        runCurrent()
        assertEquals(1, h.creates)
        assertTrue(h.controller.state.value.connected)
        assertFalse(h.controller.state.value.busy)
        h.controller.connect()
        h.controller.cancelRequest()
        runCurrent()
        assertEquals(1, h.creates)
        assertFalse(h.controller.state.value.busy)
        assertFalse(h.controller.state.value.connected)
    }

    @Test
    fun nonCooperativeLateAccountResponseCannotReconnectAfterStop() = runTest {
        val h = Harness(StandardTestDispatcher(testScheduler))
        lateinit var continuation: Continuation<List<Account>>
        h.broker.accountsBlock = { suspendCoroutine { continuation = it } }
        h.controller.connect()
        runCurrent()
        h.controller.stop()
        continuation.resume(listOf(account))
        runCurrent()
        assertFalse(h.controller.state.value.connected)
        assertNull(h.controller.state.value.selected)
        assertNull(h.controller.state.value.portfolio)
        assertFalse(h.controller.state.value.busy)
        assertEquals(0, h.store.snapshotsWritten)
    }

    @Test
    fun lateBalanceCannotReplaceSnapshotAndOldSessionMessagesAreDropped() = runTest {
        val h = Harness(StandardTestDispatcher(testScheduler))
        h.controller.connect()
        runCurrent()
        val previous = h.controller.state.value.portfolio
        val oldEvent = h.messages.single()
        lateinit var continuation: Continuation<Portfolio>
        h.broker.portfolioBlock = { suspendCoroutine { continuation = it } }
        h.controller.refresh()
        runCurrent()
        h.controller.stop()
        continuation.resume(p.copy(cash = 999))
        runCurrent()
        assertEquals(previous, h.controller.state.value.portfolio)
        assertEquals(1, h.store.snapshotsWritten)
        h.broker.portfolioBlock = { p }
        h.controller.connect()
        runCurrent()
        oldEvent("old-session-message")
        runCurrent()
        assertNotEquals("old-session-message", h.controller.state.value.message)
        assertTrue(h.controller.state.value.connected)
        h.controller.stop()
        runCurrent()
    }

    @Test
    fun partialRefreshFailureKeepsOldSnapshotAndReleasesControls() = runTest {
        val h = Harness(StandardTestDispatcher(testScheduler))
        h.controller.connect()
        runCurrent()
        h.broker.portfolioBlock = { p.copy(cash = 999) }
        h.broker.failExecutions = true
        h.controller.refresh()
        runCurrent()
        assertEquals(p, h.controller.state.value.portfolio)
        assertTrue(h.controller.state.value.messageError)
        assertFalse(h.controller.state.value.busy)
        assertEquals(1, h.store.snapshotsWritten)
        h.controller.stop()
        runCurrent()
    }

    @Test
    fun timeoutEndsLoadingAndEmptyPnlIsDistinguishedFromNotLoaded() = runTest {
        val h = Harness(StandardTestDispatcher(testScheduler))
        h.broker.accountsBlock = { suspendCancellableCoroutine {} }
        h.controller.connect()
        runCurrent()
        advanceTimeBy(60001)
        runCurrent()
        assertFalse(h.controller.state.value.busy)
        assertTrue(h.controller.state.value.messageError)
        h.broker.accountsBlock = { listOf(account) }
        h.controller.connect()
        runCurrent()
        assertNull(h.controller.state.value.pnlLoadedAt)
        h.controller.refreshPnl()
        runCurrent()
        assertNotNull(h.controller.state.value.pnlLoadedAt)
        assertTrue(h.controller.state.value.pnl.isEmpty())
        h.controller.saveCredentials("test-input", "test-input", "")
        runCurrent()
        assertNull(h.controller.state.value.pnlLoadedAt)
        assertNull(h.controller.state.value.selected)
        h.controller.stop()
        runCurrent()
    }
}

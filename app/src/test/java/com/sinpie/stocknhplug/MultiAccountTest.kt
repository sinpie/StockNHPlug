package com.sinpie.stocknhplug

import com.sinpie.stocknhplug.application.*
import com.sinpie.stocknhplug.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MultiAccountTest {
    private val a = Account("1111", Environment.MOCK, "nhplug")
    private val b = Account("2222", Environment.MOCK, "nhplug")

    @Test
    fun serviceCannotStartWhenSharedCredentialStorageHasFailed() = runTest {
        val root = Runtime()
        val child = Runtime(a)
        val manager =
            MultiAccountController(
                root,
                Directory(listOf(AccountProfile(a, true))),
                { child },
                { StrategyBook.defaults() to Strategy() },
                StandardTestDispatcher(testScheduler),
            )
        root.state.value = root.state.value.copy(storageError = true)
        assertTrue(runCatching { manager.startSession() }.isFailure)
        assertEquals(0, child.starts)
    }

    @Test
    fun commandsCapturedByOldScreenStayBoundToTheirOriginalAccount() = runTest {
        val directory = Directory(listOf(AccountProfile(a), AccountProfile(b)))
        val runtimes = mutableMapOf<Account, Runtime>()
        val manager =
            MultiAccountController(
                Runtime(),
                directory,
                { Runtime(it).also { r -> runtimes[it] = r } },
                { StrategyBook.defaults() to Strategy() },
                StandardTestDispatcher(testScheduler),
            )
        val oldScreen = manager.accountWorkspace(a)
        manager.select(b)
        oldScreen.saveSettings(Strategy(orderBudget = 170000))
        runCurrent()
        assertEquals(170000L, runtimes[a]!!.state.value.settings.orderBudget)
        assertEquals(100000L, manager.state.value.settings.orderBudget)
        assertEquals(b, manager.state.value.selected)
    }

    private class Directory(var rows: List<AccountProfile>) : AccountDirectory {
        var fail = false

        override fun profiles() = rows

        override fun save(profiles: List<AccountProfile>) {
            check(!fail)
            rows = profiles
        }
    }

    private class Runtime(account: Account? = null) : AccountRuntime {
        override val state =
            MutableStateFlow(AppState(selected = account, connected = true, hasCredentials = true))
        var starts = 0
        var stops = 0
        var disposed = false
        var valid = true

        override fun validateStart() {
            check(valid && !state.value.busy)
        }

        override fun startSession() {
            validateStart()
            starts++
            state.value = state.value.copy(running = true)
        }

        override fun stop(reason: String) {
            stops++
            state.value = state.value.copy(running = false)
        }

        override fun dispose() {
            disposed = true
        }

        override fun saveCredentials(key: String, secret: String, dart: String) {}

        override fun saveBook(book: StrategyBook) {
            state.value = state.value.copy(book = book)
        }

        override fun saveSettings(settings: Strategy) {
            state.value = state.value.copy(settings = settings)
        }

        override fun connect() {}

        override fun select(account: Account) {
            error("bound runtimes must not switch accounts")
        }

        override fun refresh() {}

        override fun refreshPrice(symbol: String) {}

        override fun refreshPnl() {}

        override fun analyze(corpMapping: String, year: Int, reportCode: String) {}

        override fun cancelRequest() {}

        override fun deleteAll() {}
    }

    @Test
    fun enabledAccountsRunConcurrentlyAndSelectionDoesNotStopOrMixThem() = runTest {
        val directory = Directory(listOf(AccountProfile(a, true), AccountProfile(b, true)))
        val runtimes = mutableMapOf<Account, Runtime>()
        val manager =
            MultiAccountController(
                Runtime(),
                directory,
                { Runtime(it).also { r -> runtimes[it] = r } },
                { StrategyBook.defaults() to Strategy() },
                StandardTestDispatcher(testScheduler),
            )
        manager.select(a)
        manager.saveSettings(Strategy(orderBudget = 150000))
        manager.select(b)
        assertEquals(100000L, manager.state.value.settings.orderBudget)
        manager.startSession()
        runCurrent()
        assertTrue(runtimes.values.all { it.state.value.running })
        manager.select(a)
        assertEquals(150000L, manager.state.value.settings.orderBudget)
        assertTrue(manager.state.value.fleetRunning)
        runtimes[b]!!.state.value =
            runtimes[b]!!
                .state
                .value
                .copy(
                    message = "B 응답",
                    pnl = listOf(DailyPnl(java.time.LocalDate.now(), 999, 0, 0)),
                )
        runCurrent()
        assertTrue(manager.state.value.pnl.isEmpty())
        assertEquals(0, runtimes[b]!!.stops)
        manager.enable(a, false)
        runCurrent()
        assertFalse(runtimes[a]!!.state.value.running)
        assertTrue(runtimes[b]!!.state.value.running)
        manager.stop()
        runCurrent()
        assertFalse(manager.state.value.fleetRunning)
    }

    @Test
    fun anyInvalidAccountPreventsPartialStartAndEnablePersistenceFailureDoesNotChangeSelection() =
        runTest {
            val directory = Directory(listOf(AccountProfile(a, true), AccountProfile(b, true)))
            val runtimes = mutableMapOf<Account, Runtime>()
            val manager =
                MultiAccountController(
                    Runtime(),
                    directory,
                    { Runtime(it).also { r -> runtimes[it] = r } },
                    { StrategyBook.defaults() to Strategy() },
                    StandardTestDispatcher(testScheduler),
                )
            runtimes[b]!!.valid = false
            assertTrue(runCatching { manager.startSession() }.isFailure)
            assertTrue(runtimes.values.all { it.starts == 0 })
            directory.fail = true
            manager.enable(a, false)
            assertTrue(directory.rows.all { it.enabled })
            assertEquals(0, runtimes[a]!!.stops)
        }

    @Test
    fun keysAndDeletionAreBlockedWhileAnyOtherAccountHasPendingWork() = runTest {
        val directory = Directory(listOf(AccountProfile(a), AccountProfile(b)))
        val runtimes = mutableMapOf<Account, Runtime>()
        val manager =
            MultiAccountController(
                Runtime(),
                directory,
                { Runtime(it).also { r -> runtimes[it] = r } },
                { StrategyBook.defaults() to Strategy() },
                StandardTestDispatcher(testScheduler),
            )
        runtimes[b]!!.state.value = runtimes[b]!!.state.value.copy(busy = true)
        manager.select(a)
        manager.saveCredentials("test", "test", "")
        manager.deleteAll()
        assertTrue(runtimes.values.none { it.disposed })
        assertEquals(2, directory.rows.size)
    }
}

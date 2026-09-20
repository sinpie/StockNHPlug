package com.sinpie.stocknhplug

import androidx.test.platform.app.InstrumentationRegistry
import com.sinpie.stocknhplug.data.*
import com.sinpie.stocknhplug.domain.*
import java.io.File
import java.time.*
import org.junit.*
import org.junit.Assert.*

/** 반드시 disposable debug 설치에서 실행한다. 기존 운영 앱 저장소를 대상으로 실행하지 않는다. */
class AccountHistoryInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root
        get() = SecureVault(context)

    private val a = Account("history-a", Environment.MOCK, "nhplug")
    private val b = Account("history-b", Environment.MOCK, "nhplug")

    @Before
    fun before() {
        root.deleteAll()
    }

    @After
    fun after() {
        root.deleteAll()
    }

    private fun at(date: String) = LocalDate.parse(date).atTime(15, 0).atZone(SEOUL).toInstant()

    private fun portfolio(date: String, cash: Long) =
        Portfolio(cash, cash + 1000, 10, emptyList(), at(date))

    private fun execution(qty: Long) =
        Execution("same-number", "005930", "검증 종목", "매수", 10, qty, 10 - qty, 100.0)

    @Test
    fun accountFilesAreAuthenticatedSeparatelyAndSettingsDoNotLeak() {
        val av = SecureVault(context, a)
        val bv = SecureVault(context, b)
        EncryptedAppStorage(av, root).saveSettings(Strategy(orderBudget = 150000))
        EncryptedAppStorage(bv, root).saveSettings(Strategy(orderBudget = 200000))
        assertEquals(
            150000L,
            EncryptedAppStorage(SecureVault(context, a), root).settings().orderBudget,
        )
        assertEquals(
            200000L,
            EncryptedAppStorage(SecureVault(context, b), root).settings().orderBudget,
        )
        val files =
            File(context.noBackupFilesDir, "accounts")
                .listFiles()!!
                .map { File(it, "settings.enc") }
                .filter { it.exists() }
        assertEquals(2, files.size)
        files[1].writeBytes(files[0].readBytes())
        assertTrue(
            runCatching {
                    av.read("settings")
                    bv.read("settings")
                }
                .isFailure
        )
    }

    @Test
    fun sameOrderNumberAcrossAccountsAndYearsIsIndependentAndRepeatedQueriesReplace() {
        val ah = EncryptedAccountHistory(SecureVault(context, a))
        val bh = EncryptedAccountHistory(SecureVault(context, b))
        val d = LocalDate.parse("2025-12-31")
        ah.record(d, portfolio(d.toString(), 1000), listOf(execution(2)), at(d.toString()))
        ah.record(
            d,
            portfolio(d.toString(), 900),
            listOf(execution(5)),
            at(d.toString()).plusSeconds(1),
        )
        bh.record(d, portfolio(d.toString(), 8000), listOf(execution(1)), at(d.toString()))
        val d2 = LocalDate.parse("2026-01-01")
        ah.record(d2, portfolio(d2.toString(), 700), listOf(execution(3)), at(d2.toString()))
        ah.mergePnl(listOf(DailyPnl(d, 100, 2, 3), DailyPnl(d2, -50, 1, 2)), at(d2.toString()))
        ah.mergePnl(listOf(DailyPnl(d2, -40, 1, 2)), at(d2.toString()).plusSeconds(1))
        ah.mergePnl(emptyList(), at(d2.toString()).plusSeconds(2))
        val restored = EncryptedAccountHistory(SecureVault(context, a)).days()
        assertEquals(2, restored.size)
        assertEquals(5L, restored.first().executions.single().filled)
        assertEquals(100L, restored.first().pnl!!.amount)
        assertEquals(-40L, restored.last().pnl!!.amount)
        assertEquals(8000L, bh.days().single().portfolio!!.cash)
        assertEquals(1L, bh.days().single().executions.single().filled)
        assertFalse(
            File(context.noBackupFilesDir, "accounts")
                .walkTopDown()
                .filter { it.extension == "enc" }
                .any { it.readBytes().toString(Charsets.UTF_8).contains("same-number") }
        )
    }

    @Test
    fun migrationPreservesUnknownOrderGateAndDoesNotCopyOtherAccounts() {
        val legacy = EncryptedAppStorage(root)
        val intent =
            OrderIntent(
                "legacy-a",
                Environment.MOCK,
                a.number,
                "005930",
                Side.BUY,
                1,
                100,
                "검증",
                at("2026-01-01"),
            )
        legacy.reserve(intent)
        legacy.update(intent.id, OrderStatus.UNKNOWN, "")
        legacy.reserve(intent.copy(id = "legacy-b", account = b.number))
        legacy.snapshot(a, Environment.MOCK, portfolio("2026-01-01", 999))
        val av = SecureVault(context, a)
        AccountMigration.migrate(root, av, a)
        AccountMigration.migrate(root, av, a)
        val saved = EncryptedAppStorage(av, root).records()
        assertEquals(1, saved.size)
        assertEquals(OrderStatus.UNKNOWN, saved.single().status)
        assertEquals(2, legacy.records().size)
        assertNull(EncryptedAccountHistory(av).days().single().executionsAt)
    }
}

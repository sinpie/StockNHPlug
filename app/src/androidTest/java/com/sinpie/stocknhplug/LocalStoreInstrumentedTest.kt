package com.sinpie.stocknhplug

import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sinpie.stocknhplug.data.LocalStore
import com.sinpie.stocknhplug.data.SecureVault
import com.sinpie.stocknhplug.domain.*
import java.io.File
import java.time.Instant
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** 실제 앱 파일과 분리한 테스트 디렉터리만 사용한다. 폐기 가능한 테스트 설치에서 실행한다. */
@RunWith(AndroidJUnit4::class)
class LocalStoreInstrumentedTest {
    private lateinit var vault: SecureVault
    private lateinit var directory: File

    @Before
    fun setup() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        directory = File(base.noBackupFilesDir, "store-test-${java.util.UUID.randomUUID()}")
        check(directory.mkdir())
        vault =
            SecureVault(
                object : ContextWrapper(base) {
                    override fun getNoBackupFilesDir() = directory
                }
            )
    }

    @After
    fun cleanup() {
        // 공유 Keystore alias는 삭제하지 않고 이 테스트가 만든 파일만 제거한다.
        listOf("journal", "events", "snapshot", "groupbook", "groupfills").forEach(vault::delete)
        check(directory.delete())
    }

    @Test
    fun pendingOrderSurvivesReloadAndDuplicateReservationIsRejected() {
        val intent =
            OrderIntent(
                "test-id",
                Environment.MOCK,
                "test-account",
                "005930",
                Side.BUY,
                1,
                10000,
                "test",
                Instant.parse("2026-09-21T01:00:00Z"),
                expiresAt = Instant.parse("2026-09-21T01:00:01Z"),
            )
        assertTrue(LocalStore(vault).reserve(intent))
        val restored = LocalStore(vault)
        assertEquals(intent.expiresAt, restored.records().single().intent.expiresAt)
        assertEquals(OrderStatus.SUBMITTING, restored.records().single().status)
        assertFalse(restored.reserve(intent))
        restored.update(intent.id, OrderStatus.UNKNOWN, "")
        assertEquals(OrderStatus.UNKNOWN, LocalStore(vault).records().single().status)
    }

    @Test
    fun legacyJournalKeepsNhplugNamespaceAndNewBrokerIdsRoundTrip() {
        val intent =
            OrderIntent(
                "migration-test",
                Environment.MOCK,
                "test-account",
                "005930",
                Side.BUY,
                1,
                10000,
                "test",
                Instant.parse("2026-09-21T01:00:00Z"),
                "other",
            )
        LocalStore(vault).reserve(intent)
        assertEquals("other", LocalStore(vault).records().single().intent.brokerId)
        val oldFormat = vault.read("journal")!!
        oldFormat.getJSONArray("orders").getJSONObject(0).remove("broker")
        oldFormat.getJSONArray("orders").getJSONObject(0).remove("expiresAt")
        vault.write("journal", oldFormat)
        assertEquals("nhplug", LocalStore(vault).records().single().intent.brokerId)
        assertEquals(
            intent.at.plusSeconds(5),
            LocalStore(vault).records().single().intent.expiresAt,
        )
        assertEquals(OrderStatus.SUBMITTING, LocalStore(vault).records().single().status)
    }

    @Test
    fun strategyGroupsAndSchedulesPersistEncrypted() {
        val book =
            StrategyBook(
                StrategyBook.defaults().plans.map {
                    it.copy(schedule = PurchaseSchedule(enabled = true))
                },
                listOf(
                    StrategyGroup(
                        id = "group-test",
                        strategyId = "averaging",
                        name = "테스트 그룹",
                        symbols = listOf(GroupSymbol("005930")),
                    )
                ),
                parking = ParkingPolicy(enabled = true, symbol = "005940", reserveCash = 30000),
            )
        val store = com.sinpie.stocknhplug.data.EncryptedAppStorage(vault)
        store.saveStrategyBook(book)
        assertEquals(book, com.sinpie.stocknhplug.data.EncryptedAppStorage(vault).strategyBook())
        assertFalse(File(directory, "groupbook.enc").readText().contains("group-test"))
    }

    @Test
    fun invalidJournalSchemaCannotBecomeEmptyHistory() {
        vault.write("journal", JSONObject().put("orders", "invalid"))
        assertTrue(runCatching { LocalStore(vault) }.isFailure)
        vault.write("journal", JSONObject())
        assertTrue(runCatching { LocalStore(vault) }.isFailure)
    }

    @Test
    fun snapshotsReplaceOnlySameAccountEnvironmentAndSeoulDate() {
        val store = LocalStore(vault)
        val at = Instant.parse("2026-09-21T01:00:00Z")
        val p = Portfolio(100, 100, 0, emptyList(), at)
        val account = Account("test-one", Environment.MOCK, "nhplug")
        store.snapshot(account, Environment.MOCK, p)
        store.snapshot(account, Environment.MOCK, p.copy(cash = 200, at = at.plusSeconds(60)))
        store.snapshot(account.copy(number = "test-two"), Environment.MOCK, p)
        store.snapshot(account, Environment.LIVE, p)
        store.snapshot(account.copy(brokerId = "other"), Environment.MOCK, p)
        val restored = LocalStore(vault).snapshots()
        assertEquals(4, restored.size)
        assertEquals(
            200L,
            restored
                .single {
                    it.account == account.number &&
                        it.environment == Environment.MOCK &&
                        it.brokerId == "nhplug"
                }
                .portfolio
                .cash,
        )
    }
}

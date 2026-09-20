package com.sinpie.stocknhplug.data

import com.sinpie.stocknhplug.domain.*
import org.json.JSONObject

/** 기존 공용 저장소는 지우지 않는다. 계좌 식별 가능한 기록만 복사하고 완료 표식을 마지막에 쓴다. */
object AccountMigration {
    fun migrate(root: SecureVault, scoped: SecureVault, account: Account) {
        if (scoped.read("migration") != null) return
        val source = EncryptedAppStorage(root)
        val target = EncryptedAppStorage(scoped, root)
        val orders =
            source.records().filter {
                it.intent.let { i ->
                    i.account == account.number &&
                        i.brokerId == account.brokerId &&
                        i.environment == account.environment
                }
            }
        orders.forEach { r ->
            if (target.reserve(r.intent)) target.update(r.intent.id, r.status, r.brokerNumber)
            else target.update(r.intent.id, r.status, r.brokerNumber)
        }
        val ids = orders.map { it.intent.id }.toSet()
        target.saveGroupFills(source.groupFills().filter { it.orderId in ids })
        val snapshots =
            source.snapshots().filter {
                it.account == account.number &&
                    it.brokerId == account.brokerId &&
                    it.environment == account.environment
            }
        snapshots.forEach { target.snapshot(account, account.environment, it.portfolio) }
        EncryptedAccountHistory(scoped).importSnapshots(snapshots.map { it.portfolio })
        if (orders.isNotEmpty() || snapshots.isNotEmpty()) {
            target.saveStrategyBook(source.strategyBook())
            target.saveSettings(source.settings())
        }
        val tracking = EncryptedTrackingStore(root).load(account, account.environment)
        EncryptedTrackingStore(scoped).save(account, account.environment, tracking)
        scoped.write("migration", JSONObject().put("version", 1))
    }
}

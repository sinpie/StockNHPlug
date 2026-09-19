package com.sinpie.stocknhplug.data

import com.sinpie.stocknhplug.application.ApplicationStorage
import com.sinpie.stocknhplug.domain.*
import org.json.JSONObject

/** 응용 저장 포트를 암호화 구현에 연결한다. lazy 초기화 오류도 컨트롤러의 저장소 잠금 경계로 전달된다. */
class EncryptedAppStorage(private val vault: SecureVault) : ApplicationStorage {
    private var local: LocalStore? = null

    private fun store(): LocalStore = local ?: LocalStore(vault).also { local = it }

    override fun settings() = store().settings()

    override fun saveSettings(settings: Strategy) = store().saveSettings(settings)

    override fun events() = store().events.toList()

    override fun log(level: String, message: String) = store().log(level, message)

    override fun snapshots() = store().snapshots()

    override fun snapshot(account: Account, environment: Environment, portfolio: Portfolio) =
        store().snapshot(account, environment, portfolio)

    override fun reserve(intent: OrderIntent) = store().reserve(intent)

    override fun update(id: String, status: OrderStatus, brokerNumber: String) =
        store().update(id, status, brokerNumber)

    override fun records() = store().records()

    override fun hasCredentials() = vault.read("credentials") != null

    /** 인증정보 교체와 토큰 무효화를 저장 계층 안에 가둔다. 원문은 반환하지 않는다. */
    override fun saveCredentials(key: String, secret: String, dart: String) {
        vault.write(
            "credentials",
            JSONObject()
                .put("key", key.trim())
                .put("secret", secret.trim())
                .put("dart", dart.trim()),
        )
        vault.delete("token")
    }

    override fun clear() {
        vault.deleteAll()
        local = null
    }
}

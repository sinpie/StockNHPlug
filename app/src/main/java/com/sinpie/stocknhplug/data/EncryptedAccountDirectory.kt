package com.sinpie.stocknhplug.data

import com.sinpie.stocknhplug.domain.*
import org.json.JSONArray
import org.json.JSONObject

/** 계좌 목록과 enable만 root vault에 저장한다. 각 계좌의 설정/시세/이력은 별도 vault에 있다. */
class EncryptedAccountDirectory(private val vault: SecureVault) : AccountDirectory {
    override fun profiles(): List<AccountProfile> {
        val j = vault.read("accounts") ?: return emptyList()
        check(j.getInt("version") == 1)
        val rows = j.getJSONArray("items")
        return (0 until rows.length())
            .map { n ->
                val r = rows.getJSONObject(n)
                AccountProfile(
                    Account(
                        r.getString("number"),
                        Environment.valueOf(r.getString("environment")),
                        r.getString("broker"),
                    ),
                    r.getBoolean("enabled"),
                )
            }
            .also { check(it.map { p -> p.account }.distinct().size == it.size) }
    }

    override fun save(profiles: List<AccountProfile>) {
        require(profiles.map { it.account }.distinct().size == profiles.size)
        vault.write(
            "accounts",
            JSONObject()
                .put("version", 1)
                .put(
                    "items",
                    JSONArray(
                        profiles.map { p ->
                            JSONObject()
                                .put("number", p.account.number)
                                .put("environment", p.account.environment.name)
                                .put("broker", p.account.brokerId)
                                .put("enabled", p.enabled)
                        }
                    ),
                ),
        )
    }
}

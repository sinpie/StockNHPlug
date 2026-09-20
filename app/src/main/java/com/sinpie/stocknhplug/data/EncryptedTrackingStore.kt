package com.sinpie.stocknhplug.data

import com.sinpie.stocknhplug.domain.*
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject

/** 추적 상태도 키/주문 기록과 동일한 Keystore 인증 암호화 및 원자적 저장을 사용한다. */
class EncryptedTrackingStore(private val vault: SecureVault) : TrackingStore {
    private fun matches(row: JSONObject, account: Account, environment: Environment) =
        row.getString("broker") == account.brokerId &&
            row.getString("account") == account.number &&
            row.getString("environment") == environment.name

    override fun load(account: Account, environment: Environment): List<TrackingRecord> {
        val scopes = vault.read("tracking")?.getJSONArray("scopes") ?: return emptyList()
        val rows =
            (0 until scopes.length())
                .map { scopes.getJSONObject(it) }
                .filter { matches(it, account, environment) }
        require(rows.size <= 1)
        val entries = rows.singleOrNull()?.getJSONArray("entries") ?: return emptyList()
        return (0 until entries.length())
            .map { index ->
                val j = entries.getJSONObject(index)
                fun optional(name: String) =
                    if (j.isNull(name)) null else j.getLong(name).also { require(it > 0) }
                TrackingRecord(
                        TargetRequest(
                            j.getString("key"),
                            j.getString("symbol"),
                            Side.valueOf(j.getString("side")),
                            j.getLong("price"),
                            Instant.parse(j.getString("deadline")),
                            j.getString("strategy"),
                            j.getString("group"),
                            j.getString("occurrence"),
                        ),
                        optional("extreme"),
                        optional("minimum"),
                        optional("maximum"),
                        j.getBoolean("active"),
                    )
                    .also { require(it.request.key.isNotBlank() && it.request.strategyPrice > 0) }
            }
            .also { require(it.map { row -> row.request.key }.distinct().size == it.size) }
    }

    override fun save(account: Account, environment: Environment, records: List<TrackingRecord>) {
        val previous = vault.read("tracking")?.getJSONArray("scopes") ?: JSONArray()
        val scopes =
            (0 until previous.length())
                .map { previous.getJSONObject(it) }
                .filterNot { matches(it, account, environment) }
        val entries =
            JSONArray(
                records.map { r ->
                    val q = r.request
                    JSONObject()
                        .put("key", q.key)
                        .put("symbol", q.symbol)
                        .put("side", q.side.name)
                        .put("price", q.strategyPrice)
                        .put("deadline", q.deadline.toString())
                        .put("strategy", q.strategyId)
                        .put("group", q.groupId)
                        .put("occurrence", q.occurrence)
                        .put("extreme", r.extreme ?: JSONObject.NULL)
                        .put("minimum", r.minimum ?: JSONObject.NULL)
                        .put("maximum", r.maximum ?: JSONObject.NULL)
                        .put("active", r.active)
                }
            )
        vault.write(
            "tracking",
            JSONObject()
                .put(
                    "scopes",
                    JSONArray(
                        scopes +
                            JSONObject()
                                .put("broker", account.brokerId)
                                .put("account", account.number)
                                .put("environment", environment.name)
                                .put("entries", entries)
                    ),
                ),
        )
    }
}

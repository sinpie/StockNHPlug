package com.sinpie.stocknhplug.data

import com.sinpie.stocknhplug.domain.*
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

class LocalStore(private val vault: SecureVault) : OrderJournal {
    private val orders = mutableListOf<OrderRecord>()
    val events = mutableListOf<Event>()
    init {
        vault.read("journal")?.optJSONArray("orders")?.let { a ->
            for (n in 0 until a.length()) { val j = a.getJSONObject(n)
                orders += OrderRecord(OrderIntent(j.getString("id"), Environment.valueOf(j.getString("env")), j.getString("account"),
                    j.getString("symbol"), Side.valueOf(j.getString("side")), j.getLong("qty"), j.getLong("price"), j.getString("reason"), Instant.parse(j.getString("at"))),
                    OrderStatus.valueOf(j.getString("status")), j.optString("number"))
            }
        }
        vault.read("events")?.optJSONArray("items")?.let { a -> for (i in 0 until a.length()) {
            val j=a.getJSONObject(i); events += Event(Instant.parse(j.getString("at")), j.getString("level"), j.getString("message"))
        } }
    }
    @Synchronized override fun reserve(intent: OrderIntent): Boolean {
        if (orders.any { it.intent.id == intent.id }) return false
        orders += OrderRecord(intent, OrderStatus.SUBMITTING)
        try { persist() } catch (e: Exception) { orders.removeAt(orders.lastIndex); throw e }
        return true
    }
    @Synchronized override fun update(id: String, status: OrderStatus, brokerNumber: String) {
        val index = orders.indexOfFirst { it.intent.id == id }; check(index >= 0)
        orders[index] = orders[index].copy(status = status, brokerNumber = brokerNumber); persist()
    }
    @Synchronized override fun records(): List<OrderRecord> = orders.toList()
    @Synchronized fun log(level: String, message: String) {
        // Only controlled application messages. Never accept HTTP bodies, credentials or exception URLs.
        events.add(0, Event(Instant.now(), level, message.take(220)))
        while (events.size > 500) events.removeAt(events.lastIndex)
        vault.write("events", JSONObject().put("items", JSONArray(events.map { JSONObject().put("at", it.at).put("level", it.level).put("message", it.message) })))
    }
    private fun persist() = vault.write("journal", JSONObject().put("orders", JSONArray(orders.map { r ->
        val i=r.intent; JSONObject().put("id",i.id).put("env",i.environment.name).put("account",i.account).put("symbol",i.symbol)
            .put("side",i.side.name).put("qty",i.quantity).put("price",i.limitPrice).put("reason",i.reason).put("at",i.at.toString())
            .put("status",r.status.name).put("number",r.brokerNumber)
    })))
    fun saveSettings(s: Strategy) {
        s.validate(); vault.write("settings", JSONObject().put("symbols",JSONArray(s.symbols)).put("order",s.orderBudget).put("daily",s.dailyBudget)
            .put("positions",s.maxPositions).put("stop",s.stopLossPercent).put("take",s.takeProfitPercent).put("trail",s.trailingPercent)
            .put("loss",s.maxSessionLoss).put("score",s.minScore).put("manage",s.manageHoldings))
    }
    fun settings(): Strategy {
        val j=vault.read("settings") ?: return Strategy()
        return Strategy((0 until j.getJSONArray("symbols").length()).map { j.getJSONArray("symbols").getString(it) }, j.getLong("order"),j.getLong("daily"),
            j.getInt("positions"),j.getDouble("stop"),j.getDouble("take"),j.getDouble("trail"),j.getLong("loss"),j.getInt("score"),j.getBoolean("manage")).also { it.validate() }
    }
    @Synchronized fun snapshots(): List<HoldingSnapshot> {
        val a=vault.read("snapshot")?.optJSONArray("items") ?: return emptyList()
        return (0 until a.length()).map { i -> val j=a.getJSONObject(i); val h=j.getJSONArray("holdings")
            HoldingSnapshot(j.getString("account"),Environment.valueOf(j.getString("env")),Portfolio(j.getLong("cash"),j.getLong("equity"),j.getLong("pnl"),
                (0 until h.length()).map { n -> val v=h.getJSONObject(n); Holding(v.getString("symbol"),v.getString("name"),v.getLong("qty"),v.getLong("average"),v.getLong("price"),v.getLong("pnl")) },Instant.parse(j.getString("at"))))
        }
    }
    @Synchronized fun snapshot(account: Account, environment: Environment, portfolio: Portfolio) {
        val today=portfolio.at.atZone(SEOUL).toLocalDate()
        val items=(snapshots().filterNot { it.account==account.number && it.environment==environment && it.portfolio.at.atZone(SEOUL).toLocalDate()==today }+
            HoldingSnapshot(account.number,environment,portfolio)).takeLast(365)
        vault.write("snapshot",JSONObject().put("items",JSONArray(items.map { s -> val p=s.portfolio
            JSONObject().put("account",s.account).put("env",s.environment.name).put("at",p.at.toString()).put("cash",p.cash).put("equity",p.equity).put("pnl",p.unrealized)
                .put("holdings",JSONArray(p.holdings.map { h -> JSONObject().put("symbol",h.symbol).put("name",h.name).put("qty",h.quantity).put("average",h.average).put("price",h.price).put("pnl",h.pnl) }))
        })))
    }
}

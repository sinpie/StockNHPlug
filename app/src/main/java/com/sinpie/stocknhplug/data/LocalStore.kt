package com.sinpie.stocknhplug.data

import com.sinpie.stocknhplug.domain.*
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject

/** 암호화 저장소 위의 주문 저널과 설정/스냅샷 저장소. 상태 손상을 정상 빈 기록으로 처리하지 않는다. */
class LocalStore(private val vault: SecureVault) : OrderJournal {
    private val orders = mutableListOf<OrderRecord>()
    val events = mutableListOf<Event>()

    init {
        vault.read("journal")?.getJSONArray("orders")?.let { a ->
            for (n in 0 until a.length()) {
                val j = a.getJSONObject(n)
                orders +=
                    OrderRecord(
                        OrderIntent(
                            j.getString("id"),
                            Environment.valueOf(j.getString("env")),
                            j.getString("account"),
                            j.getString("symbol"),
                            Side.valueOf(j.getString("side")),
                            j.getLong("qty"),
                            j.getLong("price"),
                            j.getString("reason"),
                            Instant.parse(j.getString("at")),
                            j.optString("broker", "nhplug"),
                            j.optString("strategy", ""),
                            j.optString("group", ""),
                            j.optString("occurrence", ""),
                            if (j.has("expiresAt")) Instant.parse(j.getString("expiresAt"))
                            else Instant.parse(j.getString("at")).plusSeconds(5),
                        ),
                        OrderStatus.valueOf(j.getString("status")),
                        j.optString("number"),
                    )
            }
        }
        vault.read("events")?.getJSONArray("items")?.let { a ->
            for (i in 0 until a.length()) {
                val j = a.getJSONObject(i)
                events +=
                    Event(
                        Instant.parse(j.getString("at")),
                        j.getString("level"),
                        j.getString("message"),
                    )
            }
        }
    }

    /** 전송 전에 SUBMITTING을 영속화한다. 쓰기 실패 시 메모리 추가를 되돌리고 예외를 전파한다. */
    @Synchronized
    override fun reserve(intent: OrderIntent): Boolean {
        if (orders.any { it.intent.id == intent.id }) return false
        orders += OrderRecord(intent, OrderStatus.SUBMITTING)
        try {
            persist()
        } catch (e: Exception) {
            orders.removeAt(orders.lastIndex)
            throw e
        }
        return true
    }

    /** 기존 주문의 상태만 변경한다. 저장 실패는 호출자가 거래 정지로 처리해야 한다. */
    @Synchronized
    override fun update(id: String, status: OrderStatus, brokerNumber: String) {
        val index = orders.indexOfFirst { it.intent.id == id }
        check(index >= 0)
        orders[index] = orders[index].copy(status = status, brokerNumber = brokerNumber)
        persist()
    }

    /** 내부 가변 목록을 노출하지 않는 스냅샷. 환경/계좌 필터는 호출자가 명시한다. */
    @Synchronized override fun records(): List<OrderRecord> = orders.toList()

    @Synchronized
    fun log(level: String, message: String) {
        // Only controlled application messages. Never accept HTTP bodies, credentials or exception
        // URLs.
        events.add(0, Event(Instant.now(), level, message.take(220)))
        while (events.size > 500) events.removeAt(events.lastIndex)
        vault.write(
            "events",
            JSONObject()
                .put(
                    "items",
                    JSONArray(
                        events.map {
                            JSONObject()
                                .put("at", it.at)
                                .put("level", it.level)
                                .put("message", it.message)
                        }
                    ),
                ),
        )
    }

    /** 원시 API 응답 없이 주문 식별자·상태·수량·가격만 직렬화한다. */
    private fun persist() =
        vault.write(
            "journal",
            JSONObject()
                .put(
                    "orders",
                    JSONArray(
                        orders.map { r ->
                            val i = r.intent
                            JSONObject()
                                .put("id", i.id)
                                .put("broker", i.brokerId)
                                .put("strategy", i.strategyId)
                                .put("group", i.groupId)
                                .put("occurrence", i.occurrence)
                                .put("env", i.environment.name)
                                .put("account", i.account)
                                .put("symbol", i.symbol)
                                .put("side", i.side.name)
                                .put("qty", i.quantity)
                                .put("price", i.limitPrice)
                                .put("reason", i.reason)
                                .put("at", i.at.toString())
                                .put("expiresAt", i.expiresAt.toString())
                                .put("status", r.status.name)
                                .put("number", r.brokerNumber)
                        }
                    ),
                ),
        )

    /** 유효한 전략만 영속화한다. 화면 입력 오류가 다음 실행에 남지 않게 한다. */
    fun saveSettings(s: Strategy) {
        s.validate()
        vault.write(
            "settings",
            JSONObject()
                .put("symbols", JSONArray(s.symbols))
                .put("order", s.orderBudget)
                .put("daily", s.dailyBudget)
                .put("positions", s.maxPositions)
                .put("stop", s.stopLossPercent)
                .put("take", s.takeProfitPercent)
                .put("trail", s.trailingPercent)
                .put("loss", s.maxSessionLoss)
                .put("score", s.minScore)
                .put("manage", s.manageHoldings)
                .put("websocket", s.websocketEnabled),
        )
    }

    /** 저장값도 다시 검증한다. 잘못된 값은 기본값으로 은폐하지 않고 초기화 실패로 전달한다. */
    fun settings(): Strategy {
        val j = vault.read("settings") ?: return Strategy()
        return Strategy(
                (0 until j.getJSONArray("symbols").length()).map {
                    j.getJSONArray("symbols").getString(it)
                },
                j.getLong("order"),
                j.getLong("daily"),
                j.getInt("positions"),
                j.getDouble("stop"),
                j.getDouble("take"),
                j.getDouble("trail"),
                j.getLong("loss"),
                j.getInt("score"),
                j.getBoolean("manage"),
                if (j.has("websocket")) j.getBoolean("websocket") else true,
            )
            .also { it.validate() }
    }

    /** 계좌/환경/날짜가 포함된 보유 이력을 복원한다. 현재 잔고를 대신하지 않는다. */
    @Synchronized
    fun snapshots(): List<HoldingSnapshot> {
        val a = vault.read("snapshot")?.getJSONArray("items") ?: return emptyList()
        return (0 until a.length()).map { i ->
            val j = a.getJSONObject(i)
            val h = j.getJSONArray("holdings")
            HoldingSnapshot(
                j.getString("account"),
                Environment.valueOf(j.getString("env")),
                Portfolio(
                    j.getLong("cash"),
                    j.getLong("equity"),
                    j.getLong("pnl"),
                    (0 until h.length()).map { n ->
                        val v = h.getJSONObject(n)
                        Holding(
                            v.getString("symbol"),
                            v.getString("name"),
                            v.getLong("qty"),
                            v.getLong("average"),
                            v.getLong("price"),
                            v.getLong("pnl"),
                        )
                    },
                    Instant.parse(j.getString("at")),
                ),
                j.optString("broker", "nhplug"),
            )
        }
    }

    /** 하루 마지막 조회를 교체한다. 연도별 비교를 위해 보존일 수를 자르지 않는다. */
    @Synchronized
    fun snapshot(account: Account, environment: Environment, portfolio: Portfolio) {
        val today = portfolio.at.atZone(SEOUL).toLocalDate()
        val items =
            (snapshots().filterNot {
                    it.brokerId == account.brokerId &&
                        it.account == account.number &&
                        it.environment == environment &&
                        it.portfolio.at.atZone(SEOUL).toLocalDate() == today
                } + HoldingSnapshot(account.number, environment, portfolio, account.brokerId))
                .sortedBy { it.portfolio.at }
        vault.write(
            "snapshot",
            JSONObject()
                .put(
                    "items",
                    JSONArray(
                        items.map { s ->
                            val p = s.portfolio
                            JSONObject()
                                .put("account", s.account)
                                .put("broker", s.brokerId)
                                .put("env", s.environment.name)
                                .put("at", p.at.toString())
                                .put("cash", p.cash)
                                .put("equity", p.equity)
                                .put("pnl", p.unrealized)
                                .put(
                                    "holdings",
                                    JSONArray(
                                        p.holdings.map { h ->
                                            JSONObject()
                                                .put("symbol", h.symbol)
                                                .put("name", h.name)
                                                .put("qty", h.quantity)
                                                .put("average", h.average)
                                                .put("price", h.price)
                                                .put("pnl", h.pnl)
                                        }
                                    ),
                                )
                        }
                    ),
                ),
        )
    }
}

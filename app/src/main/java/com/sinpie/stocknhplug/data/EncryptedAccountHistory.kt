package com.sinpie.stocknhplug.data

import com.sinpie.stocknhplug.domain.*
import java.time.Instant
import java.time.LocalDate
import org.json.JSONArray
import org.json.JSONObject

/** 계좌 전용 vault의 연속 일별 기록. AtomicFile 교체 후에만 새 기록이 보이며 보존일 수를 자르지 않는다. */
class EncryptedAccountHistory(private val vault: SecureVault) : AccountHistoryStore {
    @Synchronized
    override fun days(): List<AccountDay> {
        val j = vault.read("history") ?: return emptyList()
        check(j.getInt("version") == 1)
        val a = j.getJSONArray("days")
        return (0 until a.length())
            .map { n ->
                val row = a.getJSONObject(n)
                val date = LocalDate.parse(row.getString("date"))
                val p = row.optJSONObject("portfolio")?.let(::portfolio)
                val e = row.getJSONArray("executions")
                val executions = (0 until e.length()).map { i -> execution(e.getJSONObject(i)) }
                AccountDay(
                    date,
                    p,
                    executions,
                    row.optString("executionsAt").takeIf { it.isNotEmpty() }?.let(Instant::parse),
                    row.optJSONObject("pnl")?.let {
                        DailyPnl(date, it.getLong("amount"), it.getLong("fee"), it.getLong("tax"))
                    },
                    row.optString("pnlAt").takeIf { it.isNotEmpty() }?.let(Instant::parse),
                )
            }
            .also { rows ->
                check(rows.map { it.date }.distinct().size == rows.size)
                rows.forEach { validate(it) }
            }
            .sortedBy { it.date }
    }

    @Synchronized
    override fun record(
        date: LocalDate,
        portfolio: Portfolio,
        executions: List<Execution>,
        at: Instant,
    ) {
        require(
            portfolio.at.atZone(SEOUL).toLocalDate() == date &&
                at.atZone(SEOUL).toLocalDate() == date
        )
        val existing = days().associateBy { it.date }.toMutableMap()
        val previous = existing[date] ?: AccountDay(date)
        if (previous.executionsAt?.isAfter(at) == true) return
        // 完결 응답 전체를 교체한다. 주문번호는 날짜 안에서만 유일하다.
        existing[date] =
            previous.copy(portfolio = portfolio, executions = executions, executionsAt = at)
        persist(existing.values.toList())
    }

    @Synchronized
    override fun mergePnl(items: List<DailyPnl>, at: Instant) {
        require(items.map { it.date }.distinct().size == items.size)
        val existing = days().associateBy { it.date }.toMutableMap()
        items.forEach { p ->
            require(!p.date.isAfter(at.atZone(SEOUL).toLocalDate()))
            val old = existing[p.date] ?: AccountDay(p.date)
            if (old.pnlAt?.isAfter(at) != true) existing[p.date] = old.copy(pnl = p, pnlAt = at)
        }
        persist(existing.values.toList())
    }

    /** 기존 보유 스냅샷에는 당일 체결 조회 증거가 없으므로 executionsAt은 채우지 않는다. */
    @Synchronized
    fun importSnapshots(items: List<Portfolio>) {
        val existing = days().associateBy { it.date }.toMutableMap()
        items.forEach { p ->
            val date = p.at.atZone(SEOUL).toLocalDate()
            val old = existing[date] ?: AccountDay(date)
            if (old.portfolio == null || old.portfolio.at.isBefore(p.at))
                existing[date] = old.copy(portfolio = p)
        }
        persist(existing.values.toList())
    }

    private fun validate(day: AccountDay) {
        check(day.pnl == null || day.pnl.date == day.date)
        check(day.portfolio == null || day.portfolio.at.atZone(SEOUL).toLocalDate() == day.date)
        check(day.executions.map { it.number }.distinct().size == day.executions.size)
        day.executions.forEach { e ->
            check(
                e.number.isNotBlank() &&
                    e.symbol.isNotBlank() &&
                    e.ordered >= 0 &&
                    e.filled in 0..e.ordered &&
                    e.remaining in 0..e.ordered
            )
            check(e.average.isFinite() && e.average >= 0 && (e.filled == 0L || e.average > 0))
        }
    }

    private fun persist(items: List<AccountDay>) {
        items.forEach(::validate)
        vault.write(
            "history",
            JSONObject()
                .put("version", 1)
                .put(
                    "days",
                    JSONArray(
                        items
                            .sortedBy { it.date }
                            .map { d ->
                                JSONObject()
                                    .put("date", d.date)
                                    .put("portfolio", d.portfolio?.let(::encode))
                                    .put("executionsAt", d.executionsAt?.toString())
                                    .put("executions", JSONArray(d.executions.map(::encode)))
                                    .put("pnlAt", d.pnlAt?.toString())
                                    .put(
                                        "pnl",
                                        d.pnl?.let {
                                            JSONObject()
                                                .put("amount", it.amount)
                                                .put("fee", it.buyFee)
                                                .put("tax", it.sellTax)
                                        },
                                    )
                            }
                    ),
                ),
        )
    }

    private fun encode(e: Execution) =
        JSONObject()
            .put("number", e.number)
            .put("symbol", e.symbol)
            .put("name", e.name)
            .put("side", e.side)
            .put("ordered", e.ordered)
            .put("filled", e.filled)
            .put("remaining", e.remaining)
            .put("average", e.average)

    private fun execution(j: JSONObject) =
        Execution(
            j.getString("number"),
            j.getString("symbol"),
            j.getString("name"),
            j.getString("side"),
            j.getLong("ordered"),
            j.getLong("filled"),
            j.getLong("remaining"),
            j.getDouble("average"),
        )

    private fun encode(p: Portfolio) =
        JSONObject()
            .put("cash", p.cash)
            .put("equity", p.equity)
            .put("unrealized", p.unrealized)
            .put("at", p.at.toString())
            .put(
                "holdings",
                JSONArray(
                    p.holdings.map { h ->
                        JSONObject()
                            .put("symbol", h.symbol)
                            .put("name", h.name)
                            .put("quantity", h.quantity)
                            .put("average", h.average)
                            .put("price", h.price)
                            .put("pnl", h.pnl)
                    }
                ),
            )

    private fun portfolio(j: JSONObject): Portfolio {
        val h = j.getJSONArray("holdings")
        return Portfolio(
            j.getLong("cash"),
            j.getLong("equity"),
            j.getLong("unrealized"),
            (0 until h.length()).map { n ->
                val v = h.getJSONObject(n)
                Holding(
                    v.getString("symbol"),
                    v.getString("name"),
                    v.getLong("quantity"),
                    v.getLong("average"),
                    v.getLong("price"),
                    v.getLong("pnl"),
                )
            },
            Instant.parse(j.getString("at")),
        )
    }
}

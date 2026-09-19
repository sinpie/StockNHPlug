package com.sinpie.stocknhplug.data

import com.sinpie.stocknhplug.domain.*
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject

/** 그룹 설정/체결의 명시적 JSON 스키마. 누락 필드는 기본값으로 은폐하지 않는다. */
object GroupCodec {
    fun encode(value: PurchaseSchedule): JSONObject =
        JSONObject()
            .put("enabled", value.enabled)
            .put("frequency", value.frequency.name)
            .put("day", value.day)
            .put("hour", value.hour)
            .put("minute", value.minute)
            .put("budget", value.budget)

    fun readPurchaseSchedule(json: JSONObject): PurchaseSchedule =
        PurchaseSchedule(
            enabled = json.getBoolean("enabled"),
            frequency = ScheduleFrequency.valueOf(json.getString("frequency")),
            day = json.getInt("day"),
            hour = json.getInt("hour"),
            minute = json.getInt("minute"),
            budget = json.getLong("budget"),
        )

    fun encode(value: RecommendationRule): JSONObject =
        JSONObject()
            .put("enabled", value.enabled)
            .put("preset", value.preset)
            .put("trend", value.trend)
            .put("fastDays", value.fastDays)
            .put("slowDays", value.slowDays)
            .put("rsi", value.rsi)
            .put("rsiMin", value.rsiMin)
            .put("rsiMax", value.rsiMax)
            .put("momentum", value.momentum)
            .put("momentumMin", value.momentumMin)
            .put("volume", value.volume)
            .put("volumeMin", value.volumeMin)
            .put("volatility", value.volatility)
            .put("atrMax", value.atrMax)
            .put("profitability", value.profitability)
            .put("marginMin", value.marginMin)
            .put("leverage", value.leverage)
            .put("debtMax", value.debtMax)

    fun readRecommendationRule(json: JSONObject): RecommendationRule =
        RecommendationRule(
            enabled = json.getBoolean("enabled"),
            preset = json.getString("preset"),
            trend = json.getBoolean("trend"),
            fastDays = json.getInt("fastDays"),
            slowDays = json.getInt("slowDays"),
            rsi = json.getBoolean("rsi"),
            rsiMin = json.getDouble("rsiMin"),
            rsiMax = json.getDouble("rsiMax"),
            momentum = json.getBoolean("momentum"),
            momentumMin = json.getDouble("momentumMin"),
            volume = json.getBoolean("volume"),
            volumeMin = json.getDouble("volumeMin"),
            volatility = json.getBoolean("volatility"),
            atrMax = json.getDouble("atrMax"),
            profitability = json.getBoolean("profitability"),
            marginMin = json.getDouble("marginMin"),
            leverage = json.getBoolean("leverage"),
            debtMax = json.getDouble("debtMax"),
        )

    fun encode(value: GroupSymbol): JSONObject =
        JSONObject().put("symbol", value.symbol).put("weightPercent", value.weightPercent)

    fun readGroupSymbol(json: JSONObject): GroupSymbol =
        GroupSymbol(symbol = json.getString("symbol"), weightPercent = json.getInt("weightPercent"))

    fun encode(value: StrategyPlan): JSONObject =
        JSONObject()
            .put("id", value.id)
            .put("name", value.name)
            .put("algorithmId", value.algorithmId)
            .put("enabled", value.enabled)
            .put("recommendation", encode(value.recommendation))
            .put("schedule", encode(value.schedule))

    fun readStrategyPlan(json: JSONObject): StrategyPlan =
        StrategyPlan(
            id = json.getString("id"),
            name = json.getString("name"),
            algorithmId = json.getString("algorithmId"),
            enabled = json.getBoolean("enabled"),
            recommendation = readRecommendationRule(json.getJSONObject("recommendation")),
            schedule = readPurchaseSchedule(json.getJSONObject("schedule")),
        )

    fun encode(value: StrategyGroup): JSONObject =
        JSONObject()
            .put("id", value.id)
            .put("strategyId", value.strategyId)
            .put("name", value.name)
            .put("enabled", value.enabled)
            .put("symbols", JSONArray(value.symbols.map { encode(it) }))
            .put("capital", value.capital)
            .put("orderBudget", value.orderBudget)
            .put("dailyBudget", value.dailyBudget)
            .put("dropPercent", value.dropPercent)
            .put("maxAdditionalBuys", value.maxAdditionalBuys)
            .put("takeProfitPercent", value.takeProfitPercent)
            .put("rebalanceBand", value.rebalanceBand)
            .put("scheduleOverride", value.scheduleOverride.name)
            .put("schedule", encode(value.schedule))

    fun readStrategyGroup(json: JSONObject): StrategyGroup =
        StrategyGroup(
            id = json.getString("id"),
            strategyId = json.getString("strategyId"),
            name = json.getString("name"),
            enabled = json.getBoolean("enabled"),
            symbols =
                json.getJSONArray("symbols").let { a ->
                    (0 until a.length()).map { readGroupSymbol(a.getJSONObject(it)) }
                },
            capital = json.getLong("capital"),
            orderBudget = json.getLong("orderBudget"),
            dailyBudget = json.getLong("dailyBudget"),
            dropPercent = json.getDouble("dropPercent"),
            maxAdditionalBuys = json.getInt("maxAdditionalBuys"),
            takeProfitPercent = json.getDouble("takeProfitPercent"),
            rebalanceBand = json.getDouble("rebalanceBand"),
            scheduleOverride = ScheduleOverride.valueOf(json.getString("scheduleOverride")),
            schedule = readPurchaseSchedule(json.getJSONObject("schedule")),
        )

    fun encode(value: StrategyBook): JSONObject =
        JSONObject()
            .put("plans", JSONArray(value.plans.map { encode(it) }))
            .put("groups", JSONArray(value.groups.map { encode(it) }))
            .put("parking", encode(value.parking))

    fun readStrategyBook(json: JSONObject): StrategyBook =
        StrategyBook(
            parking =
                if (json.has("parking")) readParkingPolicy(json.getJSONObject("parking"))
                else ParkingPolicy(),
            plans =
                json.getJSONArray("plans").let { a ->
                    (0 until a.length()).map { readStrategyPlan(a.getJSONObject(it)) }
                },
            groups =
                json.getJSONArray("groups").let { a ->
                    (0 until a.length()).map { readStrategyGroup(a.getJSONObject(it)) }
                },
        )

    /** 이전 설정에 parking이 없을 때만 꺼진 기본값으로 이전한다. 존재하는 필드의 손상은 거절한다. */
    fun encode(value: ParkingPolicy): JSONObject =
        JSONObject()
            .put("enabled", value.enabled)
            .put("symbol", value.symbol)
            .put("reserveCash", value.reserveCash)
            .put("maxValue", value.maxValue)
            .put("orderBudget", value.orderBudget)
            .put("dailyTurnover", value.dailyTurnover)
            .put("minimumTrade", value.minimumTrade)
            .put("cooldownSeconds", value.cooldownSeconds)

    fun readParkingPolicy(json: JSONObject) =
        ParkingPolicy(
                enabled = json.getBoolean("enabled"),
                symbol = json.getString("symbol"),
                reserveCash = json.getLong("reserveCash"),
                maxValue = json.getLong("maxValue"),
                orderBudget = json.getLong("orderBudget"),
                dailyTurnover = json.getLong("dailyTurnover"),
                minimumTrade = json.getLong("minimumTrade"),
                cooldownSeconds = json.getInt("cooldownSeconds"),
            )
            .also { it.validate() }

    fun encode(value: GroupFillReport): JSONObject =
        JSONObject()
            .put("orderId", value.orderId)
            .put("quantity", value.quantity)
            .put("gross", value.gross)
            .put("fees", value.fees)
            .put("terminal", value.terminal)
            .put("at", value.at.toString())

    fun readGroupFillReport(json: JSONObject): GroupFillReport =
        GroupFillReport(
            orderId = json.getString("orderId"),
            quantity = json.getLong("quantity"),
            gross = json.getLong("gross"),
            fees = json.getLong("fees"),
            terminal = json.getBoolean("terminal"),
            at = Instant.parse(json.getString("at")),
        )
}

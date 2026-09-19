package com.sinpie.stocknhplug.application

import com.sinpie.stocknhplug.domain.*
import com.sinpie.stocknhplug.research.ResearchEvidence
import java.time.*

data class GroupDecision(
    val symbol: String,
    val side: Side,
    val quantity: Long,
    val reason: String,
    val occurrence: String,
)

data class GroupContext(
    val group: StrategyGroup,
    val positions: List<GroupPosition>,
    val quotes: Map<String, Quote>,
    val cash: Long,
)

/** 전략 확장 지점. 주문/저장 구현을 갖지 않고 그룹에 대한 희망 주문만 반환한다. */
interface GroupAlgorithm {
    val id: String
    val name: String

    fun decide(context: GroupContext): List<GroupDecision>
}

class AveragingDownAlgorithm : GroupAlgorithm {
    override val id = "averaging"
    override val name = "물타기"

    override fun decide(context: GroupContext): List<GroupDecision> =
        context.positions.mapNotNull { p ->
            val q = context.quotes[p.symbol] ?: return@mapNotNull null
            if (
                p.quantity > 0 &&
                    p.average > 0 &&
                    q.bid >= p.average * (1 + context.group.takeProfitPercent / 100)
            ) {
                val quantity = minOf(p.quantity, context.group.orderBudget / q.bid)
                return@mapNotNull if (quantity > 0)
                    GroupDecision(p.symbol, Side.SELL, quantity, "그룹 목표 수익률 도달", "take-profit")
                else null
            }
            if (
                p.quantity <= 0 ||
                    p.average <= 0 ||
                    p.buys > context.group.maxAdditionalBuys ||
                    q.ask > p.average * (1 - context.group.dropPercent / 100)
            )
                return@mapNotNull null
            val quantity =
                (minOf(context.cash, context.group.orderBudget) / (q.ask * 1.01)).toLong()
            if (quantity <= 0) null
            else
                GroupDecision(
                    p.symbol,
                    Side.BUY,
                    quantity,
                    "그룹 평균가 대비 ${context.group.dropPercent}% 하락",
                    "averaging",
                )
        }
}

class RebalancingAlgorithm : GroupAlgorithm {
    override val id = "rebalance"
    override val name = "리밸런싱"

    override fun decide(context: GroupContext): List<GroupDecision> {
        if (context.group.symbols.any { context.quotes[it.symbol] == null }) return emptyList()
        val nav =
            context.cash +
                context.positions.sumOf {
                    Math.multiplyExact(it.quantity, context.quotes.getValue(it.symbol).price)
                }
        if (nav <= 0) return emptyList()
        return context.group.symbols
            .mapNotNull { target ->
                val q = context.quotes.getValue(target.symbol)
                val owned = context.positions.find { it.symbol == target.symbol }?.quantity ?: 0
                val value = Math.multiplyExact(owned, q.price)
                val deviation = value * 100.0 / nav - target.weightPercent
                val desired = nav * target.weightPercent / 100
                when {
                    deviation > context.group.rebalanceBand -> {
                        val qty =
                            minOf(
                                owned,
                                (value - desired) / q.bid,
                                context.group.orderBudget / q.bid,
                            )
                        if (qty <= 0) null
                        else
                            GroupDecision(
                                target.symbol,
                                Side.SELL,
                                qty,
                                "목표 비중 초과 ${"%.1f".format(java.util.Locale.US, deviation)}%p",
                                "rebalance",
                            )
                    }
                    deviation < -context.group.rebalanceBand -> {
                        val qty =
                            (minOf(desired - value, context.group.orderBudget, context.cash) /
                                    (q.ask * 1.01))
                                .toLong()
                        if (qty <= 0) null
                        else GroupDecision(target.symbol, Side.BUY, qty, "목표 비중 미달", "rebalance")
                    }
                    else -> null
                }
            }
            .sortedBy { if (it.side == Side.SELL) 0 else 1 }
    }
}

class GroupAlgorithmRegistry(algorithms: List<GroupAlgorithm>) {
    private val implementations = algorithms.associateBy { it.id }

    init {
        require(implementations.size == algorithms.size)
    }

    val names: Map<String, String>
        get() = implementations.mapValues { it.value.name }

    fun get(id: String) = implementations[id] ?: error("지원하지 않는 그룹 전략")

    companion object {
        fun defaults() =
            GroupAlgorithmRegistry(listOf(AveragingDownAlgorithm(), RebalancingAlgorithm()))
    }
}

/** 설정된 지표는 AND로 결합한다. 필수 데이터/이용권한 게이트는 토글로 끌 수 없다. */
object GroupRecommendation {
    fun matches(rule: RecommendationRule, evidence: ResearchEvidence, now: Instant): Boolean {
        if (!rule.enabled || evidence.buyBlockers(now).isNotEmpty()) return false
        rule.validate()
        val today = now.atZone(SEOUL).toLocalDate()
        val candles =
            evidence.prices?.candles.orEmpty().filter { it.date < today }.sortedBy { it.date }
        if (
            SignalEngine.evaluate(evidence.symbol, candles, today) == null ||
                candles.size < rule.slowDays
        )
            return false
        val close = candles.map { it.close }
        val rsi = SignalEngine.rsi(close)
        val atr = SignalEngine.atr(candles) / close.last() * 100
        val momentum = (close.last() / close[close.lastIndex - 20] - 1) * 100
        val volume =
            candles.last().volume /
                candles.dropLast(1).takeLast(20).map { it.volume }.average().coerceAtLeast(1.0)
        return (!rule.trend ||
            (close.last() > close.takeLast(rule.fastDays).average() &&
                close.takeLast(rule.fastDays).average() >
                    close.takeLast(rule.slowDays).average())) &&
            (!rule.rsi || rsi in rule.rsiMin..rule.rsiMax) &&
            (!rule.momentum || momentum >= rule.momentumMin) &&
            (!rule.volume || volume >= rule.volumeMin) &&
            (!rule.volatility || atr <= rule.atrMax) &&
            (!rule.profitability ||
                evidence.financials?.operatingMargin?.toDouble()?.let { it >= rule.marginMin } ==
                    true) &&
            (!rule.leverage ||
                evidence.financials?.debtRatio?.toDouble()?.let { it <= rule.debtMax } == true)
    }
}

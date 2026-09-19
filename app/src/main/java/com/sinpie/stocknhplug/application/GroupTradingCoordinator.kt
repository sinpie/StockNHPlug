package com.sinpie.stocknhplug.application

import com.sinpie.stocknhplug.domain.*
import com.sinpie.stocknhplug.research.ResearchEvidence
import com.sinpie.stocknhplug.trading.*
import java.time.Instant

/** 전략 그룹 반복의 응용 조정자. 체결 대사 → 그룹별 정책 → 공통 안전 엔진 순서를 고정한다. */
class GroupTradingCoordinator(
    private val store: ApplicationStorage,
    private val source: GroupExecutionSource?,
    private val registry: GroupAlgorithmRegistry,
    private val engine: TradingEngine,
) {
    suspend fun reconcile(account: Account) {
        val provider = source ?: return
        val orders = store.records()
        val scoped =
            GroupLedger.ordersFor(account, orders).filter {
                it.intent.groupId.isNotBlank() && it.status == OrderStatus.ACCEPTED
            }
        if (scoped.isEmpty()) return
        val incoming = provider.reconcile(account, scoped)
        require(incoming.all { r -> scoped.any { it.intent.id == r.orderId } })
        val merged = GroupLedger.merge(orders, store.groupFills(), incoming)
        // 원장 계산까지 검증한 후 암호화 저장한다. 메모리에서만 체결을 반영하지 않는다.
        store.strategyBook().groups.forEach { GroupLedger.positions(it, account, orders, merged) }
        store.saveGroupFills(merged)
    }

    suspend fun tick(
        account: Account,
        portfolio: Portfolio,
        quotes: Map<String, Quote>,
        evidence: List<ResearchEvidence>,
        limits: Strategy,
        now: Instant,
    ): OrderRecord? {
        check(source != null) { "증권사의 그룹별 체결 대사 검증이 필요합니다." }
        val book = store.strategyBook().also { it.validate() }
        val orders = store.records()
        val fills = store.groupFills()
        if (GroupLedger.hasPending(account, orders, fills)) return null
        val enabled =
            book.groups.filter {
                it.enabled && book.plans.any { p -> p.id == it.strategyId && p.enabled }
            }
        val allPositions = book.groups.flatMap { GroupLedger.positions(it, account, orders, fills) }
        // 계좌 외부 매도가 있으면 특정 그룹의 주식을 다른 그룹 소유로 간주하지 않는다.
        allPositions
            .groupBy { it.symbol }
            .forEach { (symbol, positions) ->
                check(
                    positions.sumOf { it.quantity } <=
                        (portfolio.holdings.find { it.symbol == symbol }?.quantity ?: 0)
                ) {
                    "그룹 원장과 증권사 잔고 불일치"
                }
            }
        for (group in enabled) {
            val plan = book.plans.single { it.id == group.strategyId }
            val positions = GroupLedger.positions(group, account, orders, fills)
            val cash = GroupLedger.cash(group, account, orders, fills)
            check(cash >= 0)
            val validQuotes =
                quotes.filterValues {
                    it.fresh(now) && it.regular && it.bid > 0 && it.ask >= it.bid && it.price > 0
                }
            if (group.symbols.any { it.symbol !in validQuotes }) continue
            val context = GroupContext(group, positions, validQuotes, cash)
            val schedule = group.effectiveSchedule(plan)
            val occurrence = schedule.occurrence(now)
            val scheduled =
                if (occurrence == null) emptyList()
                else
                    group.symbols.mapNotNull { item ->
                        val price = validQuotes.getValue(item.symbol).ask
                        val budget =
                            minOf(
                                schedule.budget * item.weightPercent / 100,
                                group.orderBudget,
                                cash,
                            )
                        val qty = (budget / (price * 1.01)).toLong()
                        if (qty == 0L) null
                        else
                            GroupDecision(
                                item.symbol,
                                Side.BUY,
                                qty,
                                "정기매수",
                                "schedule:$occurrence",
                            )
                    }
            val recommended =
                group.symbols.mapNotNull { item ->
                    if (positions.any { it.symbol == item.symbol && it.quantity > 0 })
                        return@mapNotNull null
                    val research =
                        evidence.find { it.symbol == item.symbol } ?: return@mapNotNull null
                    if (!GroupRecommendation.matches(plan.recommendation, research, now))
                        return@mapNotNull null
                    val qty =
                        (minOf(group.orderBudget, cash) /
                                (validQuotes.getValue(item.symbol).ask * 1.01))
                            .toLong()
                    if (qty == 0L) null
                    else GroupDecision(item.symbol, Side.BUY, qty, "전략 추천조건 충족", "recommendation")
                }
            // 리밸런싱 매도 → 정기매수 → 추천 신규매수 → 추가매수 순서. 하루 그룹/종목/방향 한 번.
            val algorithm = registry.get(plan.algorithmId).decide(context)
            val decisions =
                algorithm.filter { it.side == Side.SELL } +
                    scheduled +
                    recommended +
                    algorithm.filter { it.side == Side.BUY }
            for (decision in decisions) {
                val scoped =
                    GroupLedger.ordersFor(account, orders).filter { it.intent.groupId == group.id }
                val today =
                    scoped.filter {
                        it.intent.at.atZone(SEOUL).toLocalDate() == now.atZone(SEOUL).toLocalDate()
                    }
                if (
                    today.any {
                        it.intent.symbol == decision.symbol && it.intent.side == decision.side
                    }
                )
                    continue
                if (
                    decision.occurrence.startsWith("schedule:") &&
                        scoped.any {
                            it.intent.occurrence == decision.occurrence &&
                                it.intent.symbol == decision.symbol
                        }
                )
                    continue
                if (decision.side == Side.BUY) {
                    val research = evidence.find { it.symbol == decision.symbol } ?: continue
                    if (research.buyBlockers(now).isNotEmpty()) continue
                    // 리밸런싱으로 새 종목을 진입하는 경우에도 전략별 추천 on/off와 조건을 존중한다.
                    if (
                        decision.occurrence == "rebalance" &&
                            positions.none { it.symbol == decision.symbol && it.quantity > 0 } &&
                            !GroupRecommendation.matches(plan.recommendation, research, now)
                    )
                        continue
                }
                val spent =
                    today
                        .filter { it.intent.side == Side.BUY && it.status != OrderStatus.REJECTED }
                        .sumOf { Math.multiplyExact(it.intent.quantity, it.intent.limitPrice) }
                val availableCash = minOf(cash, (group.dailyBudget - spent).coerceAtLeast(0))
                if (
                    decision.side == Side.BUY &&
                        availableCash < validQuotes.getValue(decision.symbol).ask * 1.01
                )
                    continue
                val owned = positions.find { it.symbol == decision.symbol }?.quantity ?: 0
                return engine.submit(
                    account,
                    portfolio,
                    validQuotes.getValue(decision.symbol),
                    decision.side,
                    "${plan.name} / ${group.name}: ${decision.reason}",
                    limits.copy(orderBudget = minOf(limits.orderBudget, group.orderBudget)),
                    GroupAllocation(
                        plan.id,
                        group.id,
                        decision.occurrence,
                        decision.quantity,
                        owned,
                        availableCash,
                    ),
                )
            }
        }
        return null
    }
}

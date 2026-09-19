package com.sinpie.stocknhplug.domain

import java.time.*
import java.util.UUID

/** 알고리즘 ID는 확장 가능한 문자열이다. 사용자 전략 인스턴스와 그룹 ID는 별개로 유지한다. */
data class StrategyPlan(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val algorithmId: String,
    val enabled: Boolean = false,
    val recommendation: RecommendationRule = RecommendationRule(),
    val schedule: PurchaseSchedule = PurchaseSchedule(),
)

enum class ScheduleFrequency {
    WEEKDAYS,
    WEEKLY,
    MONTHLY,
}

enum class ScheduleOverride {
    INHERIT,
    OFF,
    CUSTOM,
}

/** 서울 시간 기준 정기매수. 실행 중인 세션에서만 평가하며 지난 회차를 몰아서 실행하지 않는다. */
data class PurchaseSchedule(
    val enabled: Boolean = false,
    val frequency: ScheduleFrequency = ScheduleFrequency.MONTHLY,
    val day: Int = 1,
    val hour: Int = 10,
    val minute: Int = 0,
    val budget: Long = 100_000,
) {
    fun validate() {
        require(day in 1..31 && (frequency != ScheduleFrequency.WEEKLY || day <= 5))
        require(hour in 9..14 && minute in 0..59 && (hour != 9 || minute >= 5))
        require(budget in 10_000..10_000_000)
    }

    /** 월말 없는 날짜는 마지막 날로 맞추고 주말은 다음 평일로 이동한다. 15:15 이후에는 실행하지 않는다. */
    fun occurrence(now: Instant): String? {
        if (!enabled) return null
        val local = now.atZone(SEOUL)
        if (
            local.dayOfWeek.value > 5 ||
                local.toLocalTime() < LocalTime.of(hour, minute) ||
                local.toLocalTime() >= LocalTime.of(15, 15)
        )
            return null
        val date = local.toLocalDate()
        fun businessDay(d: LocalDate): LocalDate =
            when (d.dayOfWeek) {
                DayOfWeek.SATURDAY -> d.plusDays(2)
                DayOfWeek.SUNDAY -> d.plusDays(1)
                else -> d
            }
        return when (frequency) {
            ScheduleFrequency.WEEKDAYS -> date.toString()
            ScheduleFrequency.WEEKLY -> date.takeIf { it.dayOfWeek.value == day }?.toString()
            ScheduleFrequency.MONTHLY ->
                listOf(YearMonth.from(date), YearMonth.from(date).minusMonths(1))
                    .firstOrNull {
                        businessDay(it.atDay(day.coerceAtMost(it.lengthOfMonth()))) == date
                    }
                    ?.toString()
        }
    }
}

/** 각 지표는 독립 토글이며 활성화한 조건은 모두 충족해야 한다. 데이터 권한 검사는 별도 필수 게이트다. */
data class RecommendationRule(
    val enabled: Boolean = false,
    val preset: String = "trend",
    val trend: Boolean = true,
    val fastDays: Int = 20,
    val slowDays: Int = 60,
    val rsi: Boolean = true,
    val rsiMin: Double = 45.0,
    val rsiMax: Double = 68.0,
    val momentum: Boolean = true,
    val momentumMin: Double = 0.0,
    val volume: Boolean = false,
    val volumeMin: Double = 1.2,
    val volatility: Boolean = true,
    val atrMax: Double = 4.0,
    val profitability: Boolean = false,
    val marginMin: Double = 5.0,
    val leverage: Boolean = false,
    val debtMax: Double = 200.0,
) {
    fun validate() {
        require(fastDays in 2..100 && slowDays in (fastDays + 1)..250)
        require(rsiMin in 0.0..100.0 && rsiMax in rsiMin..100.0)
        require(momentumMin in -90.0..200.0 && volumeMin in 0.1..20.0 && atrMax in 0.1..50.0)
        require(marginMin in -100.0..100.0 && debtMax in 0.0..2000.0)
        require(
            !enabled ||
                listOf(trend, rsi, momentum, volume, volatility, profitability, leverage).any { it }
        ) {
            "추천매수 지표를 하나 이상 켜세요."
        }
    }
}

/** 추천 설정 시작점 다섯 가지. 수익 검증된 전략이라는 뜻은 아니며 적용 후 개별 수정할 수 있다. */
object RecommendationPresets {
    val names =
        linkedMapOf(
            "trend" to "추세·모멘텀",
            "pullback" to "눌림목",
            "volume" to "거래량 확장",
            "defensive" to "저변동 추세",
            "quality" to "재무·추세",
        )

    fun rule(id: String): RecommendationRule =
        when (id) {
            "trend" -> RecommendationRule(preset = id)
            "pullback" ->
                RecommendationRule(
                    preset = id,
                    rsiMin = 30.0,
                    rsiMax = 45.0,
                    momentum = false,
                    atrMax = 5.0,
                )
            "volume" ->
                RecommendationRule(
                    preset = id,
                    rsiMax = 75.0,
                    momentumMin = 3.0,
                    volume = true,
                    volumeMin = 1.5,
                    atrMax = 6.0,
                )
            "defensive" ->
                RecommendationRule(preset = id, rsiMin = 40.0, rsiMax = 60.0, atrMax = 2.0)
            "quality" -> RecommendationRule(preset = id, profitability = true, leverage = true)
            else -> error("지원하지 않는 지표 조합")
        }
}

data class GroupSymbol(val symbol: String, val weightPercent: Int = 100)

/** 동일 종목도 그룹마다 자금·원가·보유량이 독립이다. 자본은 계좌 간 이체가 아닌 앱 내부 한도다. */
data class StrategyGroup(
    val id: String = UUID.randomUUID().toString(),
    val strategyId: String,
    val name: String,
    val enabled: Boolean = false,
    val symbols: List<GroupSymbol> = emptyList(),
    val capital: Long = 1_000_000,
    val orderBudget: Long = 100_000,
    val dailyBudget: Long = 300_000,
    val dropPercent: Double = 3.0,
    val maxAdditionalBuys: Int = 3,
    val takeProfitPercent: Double = 6.0,
    val rebalanceBand: Double = 5.0,
    val scheduleOverride: ScheduleOverride = ScheduleOverride.INHERIT,
    val schedule: PurchaseSchedule = PurchaseSchedule(),
) {
    fun effectiveSchedule(plan: StrategyPlan): PurchaseSchedule =
        when (scheduleOverride) {
            ScheduleOverride.INHERIT -> plan.schedule
            ScheduleOverride.OFF -> schedule.copy(enabled = false)
            ScheduleOverride.CUSTOM -> schedule
        }
}

data class StrategyBook(
    val plans: List<StrategyPlan>,
    val groups: List<StrategyGroup>,
    val parking: ParkingPolicy = ParkingPolicy(),
) {
    /** 파킹은 사용자 전략 목록 밖에 있지만 체결 대사·소유권 검증에는 항상 포함한다. */
    fun ledgerGroups(): List<StrategyGroup> = groups + parking.ledgerGroup()

    fun validate() {
        parking.validate()
        require(plans.none { it.id == ParkingPolicy.STRATEGY_ID })
        require(groups.none { it.id == ParkingPolicy.GROUP_ID })
        require(
            parking.symbol.isEmpty() ||
                groups.none { g -> g.symbols.any { it.symbol == parking.symbol } }
        ) {
            "파킹종목은 전략그룹 종목과 분리하세요."
        }
        require(plans.size in 1..20 && groups.size <= 100)
        require(
            plans.map { it.id }.distinct().size == plans.size &&
                groups.map { it.id }.distinct().size == groups.size
        )
        plans.forEach {
            require(
                it.id.isNotBlank() &&
                    it.name.isNotBlank() &&
                    it.name.length <= 40 &&
                    it.algorithmId.isNotBlank()
            )
            it.recommendation.validate()
            it.schedule.validate()
        }
        groups.forEach { g ->
            require(
                g.id.isNotBlank() &&
                    g.strategyId in plans.map { it.id } &&
                    g.name.isNotBlank() &&
                    g.name.length <= 40
            )
            require(
                g.symbols.size <= 10 &&
                    g.symbols.map { it.symbol }.distinct().size == g.symbols.size
            )
            require(
                g.symbols.all { it.symbol.matches(Regex("[0-9]{6}")) && it.weightPercent in 1..100 }
            )
            require(g.symbols.sumOf { it.weightPercent } <= 100)
            require(!g.enabled || g.symbols.isNotEmpty())
            require(
                g.capital in 10_000..100_000_000 &&
                    g.orderBudget in 10_000..g.capital &&
                    g.dailyBudget in g.orderBudget..g.capital
            )
            require(
                g.dropPercent in 0.5..50.0 &&
                    g.maxAdditionalBuys in 0..20 &&
                    g.takeProfitPercent in 0.5..100.0 &&
                    g.rebalanceBand in 0.5..50.0
            )
            g.schedule.validate()
        }
    }

    companion object {
        fun defaults() =
            StrategyBook(
                listOf(
                    StrategyPlan(id = "averaging", name = "물타기", algorithmId = "averaging"),
                    StrategyPlan(id = "rebalance", name = "리밸런싱", algorithmId = "rebalance"),
                ),
                emptyList(),
            )
    }
}

/** 확정된 누적 체결 보고. 로컬 주문 ID 대응은 증권 어댑터가 입증해야 하며 문자열 유사도로 추정하지 않는다. */
data class GroupFillReport(
    val orderId: String,
    val quantity: Long,
    val gross: Long,
    val fees: Long,
    val terminal: Boolean,
    val at: Instant,
)

interface GroupExecutionSource {
    suspend fun reconcile(account: Account, orders: List<OrderRecord>): List<GroupFillReport>
}

/** 그룹 주문 요청에 포함하는 최종 수량/자금 상한. 공통 계좌 위험 검사는 TradingEngine이 다시 적용한다. */
data class GroupAllocation(
    val strategyId: String,
    val groupId: String,
    val occurrence: String,
    val quantity: Long,
    val owned: Long,
    val cash: Long,
)

data class GroupPosition(
    val symbol: String,
    val quantity: Long,
    val cost: Long,
    val realized: Long,
    val buys: Int,
) {
    val average: Long
        get() = if (quantity == 0L) 0 else cost / quantity
}

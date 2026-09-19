package com.sinpie.stocknhplug.domain

/** 계좌의 유휴 현금을 관리하는 설정. 종목을 추천하거나 기존 보유량을 자동 인수하지 않는다. */
data class ParkingPolicy(
    val enabled: Boolean = false,
    val symbol: String = "",
    val reserveCash: Long = 50_000,
    val maxValue: Long = 1_000_000,
    val orderBudget: Long = 100_000,
    val dailyTurnover: Long = 300_000,
    val minimumTrade: Long = 10_000,
    val cooldownSeconds: Int = 300,
) {
    fun validate() {
        require(symbol.isEmpty() || symbol.matches(Regex("[0-9]{6}"))) { "파킹 종목코드는 6자리입니다." }
        require(!enabled || symbol.isNotEmpty()) { "파킹종목을 입력하세요." }
        require(reserveCash in 0..100_000_000)
        require(maxValue in 10_000..100_000_000)
        require(orderBudget in 10_000..maxValue)
        require(dailyTurnover in orderBudget..100_000_000)
        require(minimumTrade in 10_000..orderBudget)
        require(cooldownSeconds in 60..3600)
    }

    /** 원장 계산용 예약 소유권. 전략 자본을 이중 배정하지 않도록 자본은 0이다. */
    fun ledgerGroup() =
        StrategyGroup(
            id = GROUP_ID,
            strategyId = STRATEGY_ID,
            name = "현금 파킹",
            symbols = if (symbol.isEmpty()) emptyList() else listOf(GroupSymbol(symbol)),
            capital = 0,
        )

    companion object {
        const val GROUP_ID = "__cash_parking__"
        const val STRATEGY_ID = "__cash_management__"
    }
}

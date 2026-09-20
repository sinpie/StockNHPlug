package com.sinpie.stocknhplug.domain

import java.time.Instant
import java.time.LocalDate

/** 계좌 전체의 날짜별 관측 기록. 미조회는 null이며 휴장일/미수집일을 0으로 합성하지 않는다. */
data class AccountDay(
    val date: LocalDate,
    val portfolio: Portfolio? = null,
    val executions: List<Execution> = emptyList(),
    val executionsAt: Instant? = null,
    val pnl: DailyPnl? = null,
    val pnlAt: Instant? = null,
)

/** 매일 마지막 완결 조회를 보존한다. 체결 수량은 누적값이므로 같은 날 반복 조회를 더하지 않는다. */
interface AccountHistoryStore {
    fun days(): List<AccountDay>

    fun record(date: LocalDate, portfolio: Portfolio, executions: List<Execution>, at: Instant)

    fun mergePnl(items: List<DailyPnl>, at: Instant)
}

data class AccountProfile(val account: Account, val enabled: Boolean = false)

/** 키는 공유하더라도 계좌 식별은 증권사·환경·계좌번호 전체 조합이다. */
interface AccountDirectory {
    fun profiles(): List<AccountProfile>

    fun save(profiles: List<AccountProfile>)
}

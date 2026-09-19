package com.sinpie.stocknhplug.research

import java.time.LocalDate

/** 주문 기능이 없는 가격 이력 포트. 출처·공개시점·수정 여부를 데이터와 함께 반환한다. */
fun interface PriceHistoryProvider {
    suspend fun history(symbol: String): PriceHistory
}

/** 재무와 공시 조회 포트. 구현체의 HTTP/인증/JSON은 연구 정책에 노출되지 않는다. */
interface CorporateResearchProvider {
    suspend fun financials(corp: String, year: Int, reportCode: String): FinancialReport?

    suspend fun disclosures(corp: String, from: LocalDate, to: LocalDate): List<Disclosure>
}

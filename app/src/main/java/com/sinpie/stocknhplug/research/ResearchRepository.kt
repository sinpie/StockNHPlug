package com.sinpie.stocknhplug.research

import com.sinpie.stocknhplug.domain.*
import java.time.*

/**
 * Coordinates provider evidence; a missing source remains UNKNOWN and cannot become a BUY
 * permission.
 */
class ResearchRepository(
    private val prices: PriceHistoryProvider,
    private val corporate: CorporateResearchProvider?,
    private val news: NewsProvider,
    private val directory: CorporateDirectory? = null,
    private val clock: () -> Instant = Instant::now,
) {
    /** 공식 제공자 자료를 한 종목의 근거로 묶는다. 연결되지 않은 뉴스/수정주가 권한을 승인 상태로 승격하지 않는다. */
    suspend fun inspect(
        symbol: String,
        corpCode: String?,
        year: Int,
        reportCode: String,
    ): ResearchEvidence {
        val now = clock()
        val today = now.atZone(SEOUL).toLocalDate()
        val history = prices.history(symbol)
        check(history.symbol == symbol) { "가격 이력 종목이 요청과 다릅니다." }
        val official = if (corporate != null) directory?.corporation(symbol) else null
        if (directory != null && corpCode != null) {
            require(corpCode == official) { "입력한 기업 고유번호가 공식 종목 정보와 다릅니다." }
        }
        val corporation = official ?: if (directory == null) corpCode else null
        val financials =
            if (corporate != null && corporation != null)
                corporate.financials(corporation, year, reportCode)
            else null
        val disclosures =
            if (corporate != null && corporation != null)
                corporate.disclosures(corporation, today.minusDays(30), today)
            else emptyList()
        return ResearchEvidence(
            symbol,
            history,
            financials,
            disclosures,
            news.latest(symbol),
            corporate != null && corporation != null,
            news.licensedForAutomatedTrading,
            now,
        )
    }
}

package com.sinpie.stocknhplug.research

import com.sinpie.stocknhplug.domain.*
import java.time.*

/**
 * Coordinates provider evidence; a missing source remains UNKNOWN and cannot become a BUY
 * permission.
 */
class ResearchRepository(private val broker: Broker, private val dart: DartClient?) {
    /** 공식 제공자 자료를 한 종목의 근거로 묶는다. 연결되지 않은 뉴스/수정주가 권한을 승인 상태로 승격하지 않는다. */
    suspend fun inspect(
        symbol: String,
        corpCode: String?,
        year: Int,
        reportCode: String,
    ): ResearchEvidence {
        val now = Instant.now()
        val today = now.atZone(SEOUL).toLocalDate()
        val candles = broker.candles(symbol)
        val financials =
            if (dart != null && corpCode != null) dart.financials(corpCode, year, reportCode)
            else null
        val disclosures =
            if (dart != null && corpCode != null)
                dart.disclosures(corpCode, today.minusDays(30), today)
            else emptyList()
        return ResearchEvidence(
            symbol,
            PriceHistory(symbol, candles, false, DataSource.NHPLUG, now),
            financials,
            disclosures,
            UnlicensedNewsProvider().latest(symbol),
            dart != null && corpCode != null,
            false,
            now,
        )
    }
}

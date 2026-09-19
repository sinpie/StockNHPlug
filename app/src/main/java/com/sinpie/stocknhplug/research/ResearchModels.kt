package com.sinpie.stocknhplug.research

import com.sinpie.stocknhplug.domain.Candle
import java.math.BigDecimal
import java.time.*

enum class DataSource { NHPLUG, OPENDART, LICENSED_NEWS, LICENSED_ADJUSTED_PRICES }
enum class PermissionStatus { OFFICIAL_API_PERSONAL, CONTRACT_REQUIRED, DISABLED }
data class SourcePolicy(val source: DataSource, val permission: PermissionStatus, val reviewed: LocalDate, val termsUrl: String)
object SourceRegistry {
    val policies = listOf(
        SourcePolicy(DataSource.NHPLUG,PermissionStatus.OFFICIAL_API_PERSONAL,LocalDate.of(2026,9,19),"https://www.nhplug.com/intro"),
        SourcePolicy(DataSource.OPENDART,PermissionStatus.OFFICIAL_API_PERSONAL,LocalDate.of(2026,9,19),"https://opendart.fss.or.kr/intro/terms.do"),
        SourcePolicy(DataSource.LICENSED_NEWS,PermissionStatus.CONTRACT_REQUIRED,LocalDate.of(2026,9,19),""),
        SourcePolicy(DataSource.LICENSED_ADJUSTED_PRICES,PermissionStatus.CONTRACT_REQUIRED,LocalDate.of(2026,9,19),""))
}
data class PriceHistory(val symbol: String, val candles: List<Candle>, val adjusted: Boolean, val source: DataSource, val asOf: Instant)
data class CorporateAction(val exDate: LocalDate, val priceFactor: Double, val volumeFactor: Double, val publishedAt: Instant)
/** Only licensed, explicit factors. No split detection by price jumps; no fabricated dividend factors. */
object AdjustedPriceEngine {
    fun adjust(raw: List<Candle>, actions: List<CorporateAction>, asOf: Instant): List<Candle> {
        require(actions.all { it.priceFactor.isFinite() && it.priceFactor>0 && it.volumeFactor.isFinite() && it.volumeFactor>0 })
        val known=actions.filter { !it.publishedAt.isAfter(asOf) && !it.exDate.isAfter(asOf.atZone(com.sinpie.stocknhplug.domain.SEOUL).toLocalDate()) }
        return raw.map { c ->
            val applicable=known.filter { c.date < it.exDate }
            val p=applicable.fold(1.0) { a,b -> a*b.priceFactor }; val v=applicable.fold(1.0) { a,b -> a*b.volumeFactor }
            c.copy(close=c.close*p,high=c.high*p,low=c.low*p,volume=c.volume*v).also { require(it.close.isFinite() && it.volume.isFinite()) }
        }
    }
}
data class FinancialReport(val year: Int, val reportCode: String, val receipt: String, val consolidated: Boolean,
    val revenue: BigDecimal?, val operatingProfit: BigDecimal?, val equity: BigDecimal?, val liabilities: BigDecimal?, val retrievedAt: Instant) {
    val operatingMargin: BigDecimal? get() = ratio(operatingProfit,revenue)
    val debtRatio: BigDecimal? get() = ratio(liabilities,equity)
    private fun ratio(a: BigDecimal?, b: BigDecimal?) = if(a!=null && b!=null && b>BigDecimal.ZERO) a.multiply(BigDecimal(100)).divide(b,2,java.math.RoundingMode.HALF_UP) else null
}
data class Disclosure(val receipt: String, val title: String, val date: LocalDate) {
    val url get()="https://dart.fss.or.kr/dsaf001/main.do?rcpNo=$receipt"
    val risk get()=Regex("거래정지|상장폐지|감사의견|횡령|배임|회생|파산|유상증자|불성실공시").containsMatchIn(title)
}
data class NewsItem(val title: String, val sourceName: String, val url: String, val publishedAt: Instant)
interface NewsProvider { suspend fun latest(symbol: String): List<NewsItem> }
/** Intentionally no scraper: must be replaced only after a licensed provider is approved in DATA_SOURCES.md. */
class UnlicensedNewsProvider : NewsProvider { override suspend fun latest(symbol: String): List<NewsItem> = emptyList() }
data class ResearchEvidence(val symbol: String, val prices: PriceHistory?, val financials: FinancialReport?, val disclosures: List<Disclosure>,
    val news: List<NewsItem>, val disclosuresChecked: Boolean, val newsLicensed: Boolean, val checkedAt: Instant) {
    fun buyBlockers(now: Instant): List<String> = buildList {
        if(prices?.adjusted!=true) add("수정주가 기준 미검증")
        if(financials==null || financials.equity==null || financials.operatingProfit==null) add("재무지표 미확인")
        if(financials?.equity?.let { it<=BigDecimal.ZERO }==true || financials?.operatingProfit?.let { it<=BigDecimal.ZERO }==true) add("재무 필터 미충족")
        if(!disclosuresChecked) add("공시 확인 필요")
        if(disclosures.any { it.risk }) add("주요 위험 공시 검토 필요")
        if(!newsLicensed) add("뉴스 이용권한 미확정")
        if(checkedAt>now || Duration.between(checkedAt,now).toMinutes()>30) add("분석 자료 갱신 필요")
    }
}

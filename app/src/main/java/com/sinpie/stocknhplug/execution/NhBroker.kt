package com.sinpie.stocknhplug.execution

import com.sinpie.stocknhplug.BuildConfig
import com.sinpie.stocknhplug.domain.*
import org.json.JSONObject
import org.json.JSONArray
import java.time.*
import java.time.format.DateTimeFormatter

fun json(vararg pairs: Pair<String, Any>) = JSONObject().apply { pairs.forEach { put(it.first,it.second) } }
fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
class NhBroker(private val transport: NhTransport) : Broker {
    override val environment = transport.environment
    override suspend fun accounts(): List<Account> = transport.call("/n2/acctinfo",JSONObject()).optJSONArray("Output_0")?.objects().orEmpty()
        .map { Account(it.getString("acct_no"),it.getString("acct_type")) }.filter { it.validFor(environment) }
    override suspend fun portfolio(account: Account): Portfolio {
        require(account.validFor(environment))
        val pages=transport.pages("/krstock/inquiry/v1/balance",json("act_no" to account.number,"bnc_bse_cd" to "1","ltg_aot_dit_cd" to "9","aet_bse" to "1","qut_dit_cd" to "KRX","aly_qut_cd" to "1"),true)
        val summary=pages.last().getJSONObject("Output_0")
        val holdings=pages.flatMap { it.optJSONArray("Output_1")?.objects().orEmpty() }.map { j ->
            Holding(j.getString("iem_cd"),j.getString("iem_nm"),j.getDouble("itg_bnc_qty").toLong(),j.getLong("phs_pr"),j.getLong("now_pr"),j.getLong("eal_pls_amt"))
        }.filter { it.quantity>0 }
        // Duplicate tax/credit lots are grouped for display; automatic sells still require cash availability.
        val grouped=holdings.groupBy { it.symbol }.map { (_, lots) ->
            val qty=lots.sumOf { it.quantity }; lots.first().copy(quantity=qty,average=lots.sumOf { Math.multiplyExact(it.average,it.quantity) }/qty,pnl=lots.sumOf { it.pnl })
        }
        return Portfolio(summary.getLong("dca").coerceAtLeast(0),summary.getLong("nas_amt"),summary.getLong("tot_eal_pls"),grouped,Instant.now())
    }
    override suspend fun candles(symbol: String): List<Candle> {
        val today=LocalDate.now(SEOUL)
        val j=transport.call("/krstock/quote/v1/period",json("market_cd" to "KRX","iem_cd" to symbol,"view_main_yn" to "Y","edate" to today.minusDays(1).format(DateTimeFormatter.BASIC_ISO_DATE),"array_cnt" to "250","gubun" to "1","out1_scale_change" to "0","out2_scale_change" to "0"))
        return j.getJSONArray("Output_1").objects().map { c ->
            // The published spec does not guarantee a total-return adjustment basis. Research layer records this explicitly.
            Candle(LocalDate.parse(c.getString("bsop_date"),DateTimeFormatter.BASIC_ISO_DATE),c.getDouble("stck_prpr"),c.getDouble("stck_hgpr"),c.getDouble("stck_lwpr"),c.getDouble("vol"))
        }.sortedBy { it.date }
    }
    override suspend fun available(account: Account, symbol: String, side: Side, price: Long): Long {
        val j=if(side==Side.BUY) transport.call("/krstock/inquiry/v1/buyableQuantity",json("ost_dit_cd" to "1","act_no" to account.number,"iem_cd" to symbol,"nmn_pr_tp_cd" to "01","orr_pr" to price))
        else transport.call("/krstock/inquiry/v1/sellableQuantity",json("act_no" to account.number,"iem_cd" to symbol,"cfd_lon_cd" to "00"))
        return j.getJSONObject("Output_0").getDouble(if(side==Side.BUY) "csh_orr_pbl_qty" else "sll_pbl_qty").toLong().coerceAtLeast(0)
    }
    override suspend fun place(account: Account, intent: OrderIntent): String {
        check(environment==Environment.MOCK || BuildConfig.LIVE_TRADING_ENABLED) { "실거래 검증 게이트가 잠겨 있습니다." }
        require(account.validFor(environment) && intent.environment==environment && intent.account==account.number)
        require(intent.quantity>0 && intent.limitPrice>0)
        val j=transport.pages("/krstock/order/v1/" + if(intent.side==Side.BUY) "cashBuy" else "cashSell",json("act_no" to account.number,"iem_cd" to intent.symbol,
            "orr_qty" to intent.quantity,"orr_pr" to intent.limitPrice,"orr_amt" to Math.multiplyExact(intent.quantity,intent.limitPrice),"nmn_pr_tp_cd" to "01",
            "orr_cnd_dit_cd" to "01","ssl_nmn_pr_dit_cd" to "00","rmt_mkt_cd" to "KRX","sor_mkt_sli_yn" to "N"), dispatchGuard={
                check(Duration.between(intent.at,Instant.now()).seconds in 0..5) { "주문 전송 유효시간 초과" }
            }).single()
        return j.getJSONObject("Output_0").getLong("mkt_orr_no").also { check(it>0) }.toString()
    }
    override suspend fun executions(account: Account, date: LocalDate): List<Execution> = transport.pages("/krstock/inquiry/v1/dailyOrderExecution",
        json("act_no" to account.number,"orr_dt" to date.format(DateTimeFormatter.BASIC_ISO_DATE),"orr_mkt_cd" to "00","ost_cns_dit" to "0"),true)
        .flatMap { it.optJSONArray("Output_1")?.objects().orEmpty() }.map { j -> Execution(j.getString("itg_orr_no"),j.getString("iem_cd"),j.getString("iem_nm"),
            j.getString("sby_dit_cd_nm"),j.getLong("orr_qty"),j.getLong("tot_cns_qty"),j.getLong("ny_cns_qty"),j.getDouble("cns_avg_uit_pr")) }
    suspend fun dailyPnl(account: Account): List<DailyPnl> {
        val today=LocalDate.now(SEOUL)
        return transport.pages("/krstock/inquiry/v1/dailyPnl",json("act_no" to account.number,"iqr_sta_dt" to today.minusDays(30).format(DateTimeFormatter.BASIC_ISO_DATE),"iqr_end_dt" to today.format(DateTimeFormatter.BASIC_ISO_DATE)),true)
            .flatMap { it.optJSONArray("Output_1")?.objects().orEmpty() }.map { DailyPnl(LocalDate.parse(it.getString("sby_dt"),DateTimeFormatter.BASIC_ISO_DATE),it.getLong("pls_amt"),it.getLong("byn_fee"),it.getLong("sll_tax_sum")) }.sortedBy { it.date }
    }
}

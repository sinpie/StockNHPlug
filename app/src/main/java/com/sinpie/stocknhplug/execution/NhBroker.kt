package com.sinpie.stocknhplug.execution

import com.sinpie.stocknhplug.BuildConfig
import com.sinpie.stocknhplug.domain.*
import com.sinpie.stocknhplug.infrastructure.json.*
import com.sinpie.stocknhplug.infrastructure.nh.NhTransport
import java.time.*
import java.time.format.DateTimeFormatter
import org.json.JSONObject

/** Broker 포트의 NHPlug 구현. 전문 필드 변환을 이 클래스 안에 가두어 매매 계층이 API 필드명을 몰라도 되게 한다. */
class NhBroker(private val transport: NhTransport) : Broker {
    override val id = "nhplug"
    override val environment = transport.environment

    /** 명세의 acct_type을 이용해 현재 거래 환경에 맞는 계좌만 반환한다. */
    override suspend fun accounts(): List<Account> =
        transport
            .call("/n2/acctinfo", JSONObject())
            .optJSONArray("Output_0")
            ?.objects()
            .orEmpty()
            .filter {
                if (environment == Environment.MOCK) it.getString("acct_type") == "03"
                else it.getString("acct_type") in setOf("01", "02")
            }
            .map { Account(it.getString("acct_no"), environment, id) }

    /** 모든 잔고 페이지를 받은 뒤 집계한다. 여러 세금/신용 lot는 표시용으로 합산한다. */
    override suspend fun portfolio(account: Account): Portfolio {
        require(account.validFor(environment) && account.brokerId == id)
        val pages =
            transport.pages(
                "/krstock/inquiry/v1/balance",
                json(
                    "act_no" to account.number,
                    "bnc_bse_cd" to "1",
                    "ltg_aot_dit_cd" to "9",
                    "aet_bse" to "1",
                    "qut_dit_cd" to "KRX",
                    "aly_qut_cd" to "1",
                ),
                true,
            )
        val summary = pages.last().getJSONObject("Output_0")
        val holdings =
            pages
                .flatMap { it.optJSONArray("Output_1")?.objects().orEmpty() }
                .map { j ->
                    Holding(
                        j.getString("iem_cd"),
                        j.getString("iem_nm"),
                        j.getDouble("itg_bnc_qty").toLong(),
                        j.getLong("phs_pr"),
                        j.getLong("now_pr"),
                        j.getLong("eal_pls_amt"),
                    )
                }
                .filter { it.quantity > 0 }
        // Duplicate tax/credit lots are grouped for display; automatic sells still require cash
        // availability.
        val grouped =
            holdings
                .groupBy { it.symbol }
                .map { (_, lots) ->
                    val qty = lots.sumOf { it.quantity }
                    lots
                        .first()
                        .copy(
                            quantity = qty,
                            average =
                                lots.sumOf { Math.multiplyExact(it.average, it.quantity) } / qty,
                            pnl = lots.sumOf { it.pnl },
                        )
                }
        return Portfolio(
            summary.getLong("dca").coerceAtLeast(0),
            summary.getLong("nas_amt"),
            summary.getLong("tot_eal_pls"),
            grouped,
            Instant.now(),
        )
    }

    /** 매수는 미수 없는 현금 가능수량, 매도는 현금 매도 가능수량을 사용한다. */
    override suspend fun available(
        account: Account,
        symbol: String,
        side: Side,
        price: Long,
    ): Long {
        // Every account-scoped operation must reject a foreign broker/environment before I/O.
        require(account.validFor(environment) && account.brokerId == id)
        require(symbol.matches(Regex("[0-9]{6}")) && price > 0)
        val j =
            if (side == Side.BUY)
                transport.call(
                    "/krstock/inquiry/v1/buyableQuantity",
                    json(
                        "ost_dit_cd" to "1",
                        "act_no" to account.number,
                        "iem_cd" to symbol,
                        "nmn_pr_tp_cd" to "01",
                        "orr_pr" to price,
                    ),
                )
            else
                transport.call(
                    "/krstock/inquiry/v1/sellableQuantity",
                    json("act_no" to account.number, "iem_cd" to symbol, "cfd_lon_cd" to "00"),
                )
        return j.getJSONObject("Output_0")
            .getDouble(if (side == Side.BUY) "csh_orr_pbl_qty" else "sll_pbl_qty")
            .toLong()
            .coerceAtLeast(0)
    }

    /** 실거래 빌드 잠금·계좌 일치를 검사한 뒤 지정가 IOC를 한 번 전송한다. 시장주문번호만 반환한다. */
    override suspend fun place(account: Account, intent: OrderIntent): String {
        check(environment == Environment.MOCK || BuildConfig.LIVE_TRADING_ENABLED) {
            "실거래 검증 게이트가 잠겨 있습니다."
        }
        require(
            account.validFor(environment) &&
                intent.environment == environment &&
                intent.account == account.number &&
                account.brokerId == id &&
                intent.brokerId == id
        )
        require(intent.quantity > 0 && intent.limitPrice > 0)
        val j =
            transport
                .pages(
                    "/krstock/order/v1/" + if (intent.side == Side.BUY) "cashBuy" else "cashSell",
                    json(
                        "act_no" to account.number,
                        "iem_cd" to intent.symbol,
                        "orr_qty" to intent.quantity,
                        "orr_pr" to intent.limitPrice,
                        "orr_amt" to Math.multiplyExact(intent.quantity, intent.limitPrice),
                        "nmn_pr_tp_cd" to "01",
                        "orr_cnd_dit_cd" to "01",
                        "ssl_nmn_pr_dit_cd" to "00",
                        "rmt_mkt_cd" to "KRX",
                        "sor_mkt_sli_yn" to "N",
                    ),
                    dispatchGuard = {
                        check(intent.dispatchable(Instant.now())) { "주문 전송 유효시간 초과" }
                    },
                )
                .single()
        return j.getJSONObject("Output_0").getLong("mkt_orr_no").also { check(it > 0) }.toString()
    }

    /** 당일 계좌 전체 통합주문 체결을 조회한다. 앱 저널의 시장주문번호와 임의 대응시키지 않는다. */
    override suspend fun executions(account: Account, date: LocalDate): List<Execution> {
        require(account.validFor(environment) && account.brokerId == id)
        return transport
            .pages(
                "/krstock/inquiry/v1/dailyOrderExecution",
                json(
                    "act_no" to account.number,
                    "orr_dt" to date.format(DateTimeFormatter.BASIC_ISO_DATE),
                    "orr_mkt_cd" to "00",
                    "ost_cns_dit" to "0",
                ),
                true,
            )
            .flatMap { it.optJSONArray("Output_1")?.objects().orEmpty() }
            .map { j ->
                Execution(
                    j.getString("itg_orr_no"),
                    j.getString("iem_cd"),
                    j.getString("iem_nm"),
                    j.getString("sby_dit_cd_nm"),
                    j.getLong("orr_qty"),
                    j.getLong("tot_cns_qty"),
                    j.getLong("ny_cns_qty"),
                    j.getDouble("cns_avg_uit_pr"),
                )
            }
    }

    /** 최근 30일 계좌 손익을 일자순으로 반환한다. 수수료/세금은 중복 차감하지 않고 원천값을 보존한다. */
    override suspend fun dailyPnl(account: Account): List<DailyPnl> {
        require(account.validFor(environment) && account.brokerId == id)
        val today = LocalDate.now(SEOUL)
        return transport
            .pages(
                "/krstock/inquiry/v1/dailyPnl",
                json(
                    "act_no" to account.number,
                    "iqr_sta_dt" to today.minusDays(30).format(DateTimeFormatter.BASIC_ISO_DATE),
                    "iqr_end_dt" to today.format(DateTimeFormatter.BASIC_ISO_DATE),
                ),
                true,
            )
            .flatMap { it.optJSONArray("Output_1")?.objects().orEmpty() }
            .map {
                DailyPnl(
                    LocalDate.parse(it.getString("sby_dt"), DateTimeFormatter.BASIC_ISO_DATE),
                    it.getLong("pls_amt"),
                    it.getLong("byn_fee"),
                    it.getLong("sll_tax_sum"),
                )
            }
            .sortedBy { it.date }
    }
}

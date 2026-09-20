package com.sinpie.stocknhplug.marketdata

import com.sinpie.stocknhplug.domain.*
import com.sinpie.stocknhplug.infrastructure.json.json
import com.sinpie.stocknhplug.infrastructure.json.objects
import com.sinpie.stocknhplug.infrastructure.nh.NhTransport
import com.sinpie.stocknhplug.research.*
import java.time.*
import java.time.format.DateTimeFormatter

/** NHPlug 가격이력 어댑터. 주문 포트와 분리하며 미확정 수정주가 상태를 그대로 전달한다. */
class NhPriceHistoryProvider(private val transport: NhTransport) : PriceHistoryProvider {
    /** 전일까지 일봉을 조회해 시간순으로 반환한다. 수정주가라는 보장은 여기서 만들지 않는다. */
    override suspend fun history(symbol: String): PriceHistory {
        val today = LocalDate.now(SEOUL)
        val j =
            transport.historyWindow(
                json(
                    "market_cd" to "KRX",
                    "iem_cd" to symbol,
                    "view_main_yn" to "Y",
                    "edate" to today.minusDays(1).format(DateTimeFormatter.BASIC_ISO_DATE),
                    "array_cnt" to "250",
                    "gubun" to "1",
                    "out1_scale_change" to "0",
                    "out2_scale_change" to "0",
                ),
            )
        val candles =
            j.getJSONArray("Output_1")
                .objects()
                .take(250)
                .map { c ->
                    // The published spec does not guarantee a total-return adjustment basis.
                    // Research
                    // layer records this explicitly.
                    Candle(
                        LocalDate.parse(c.getString("bsop_date"), DateTimeFormatter.BASIC_ISO_DATE),
                        c.getDouble("stck_prpr"),
                        c.getDouble("stck_hgpr"),
                        c.getDouble("stck_lwpr"),
                        c.getDouble("vol"),
                    )
                }
                .sortedBy { it.date }
        return PriceHistory(symbol, candles, false, DataSource.NHPLUG, Instant.now())
    }
}

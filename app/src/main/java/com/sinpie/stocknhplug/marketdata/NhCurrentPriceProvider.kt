package com.sinpie.stocknhplug.marketdata

import com.sinpie.stocknhplug.domain.*
import com.sinpie.stocknhplug.infrastructure.json.json
import com.sinpie.stocknhplug.infrastructure.nh.NhTransport
import java.time.*
import java.time.format.DateTimeFormatter
import org.json.JSONObject

/** 공식 currentPrice의 KRX 호가/상하한만 정규화한다. 누락 호가를 현재가로 대체하지 않는다. */
class NhCurrentPriceProvider(private val transport: NhTransport) : CurrentPriceProvider {
    override suspend fun current(symbol: String): PriceSnapshot {
        require(symbol.matches(Regex("[0-9]{6}")))
        val result =
            transport.call(
                "/krstock/quote/v1/currentPrice",
                json("iem_cd" to symbol, "market_cd" to "KRX"),
            )
        return parse(symbol, result, Instant.now())
    }

    companion object {
        fun parse(symbol: String, json: JSONObject, received: Instant): PriceSnapshot {
            val row = json.getJSONObject("Output_0")
            require(row.getString("iem_cd") == symbol)
            val digits = row.getString("hoga_bsop_hour").replace(":", "")
            require(digits.matches(Regex("[0-9]{6}")))
            val day = received.atZone(SEOUL).toLocalDate()
            val time =
                day.atTime(LocalTime.parse(digits, DateTimeFormatter.ofPattern("HHmmss")))
                    .atZone(SEOUL)
                    .toInstant()
            val state = json.getJSONObject("Output_2").getString("cncc_aspr_code")
            // 확인되지 않은 상태를 정규장으로 추정하지 않는다.
            val quote =
                Quote(
                    symbol,
                    row.getLong("stck_prpr"),
                    row.getLong("bidp"),
                    row.getLong("askp"),
                    received,
                    time,
                    state == "0",
                )
            require(
                quote.price > 0 && quote.bid > 0 && quote.ask >= quote.bid && quote.fresh(received)
            )
            val kind =
                when (row.getString("scrt_grp_isnm").trim()) {
                    "ETF" -> InstrumentKind.ETF
                    "주식",
                    "주권",
                    "보통주",
                    "우선주",
                    "STOCK" -> InstrumentKind.STOCK
                    else -> InstrumentKind.UNKNOWN
                }
            val rules = MarketRules(day, row.getLong("stck_llam"), row.getLong("stck_mxpr"), kind)
            require(rules.contains(quote.price, received))
            return PriceSnapshot(quote, rules, PriceSource.REST).also {
                require(it.valid(received))
            }
        }
    }
}

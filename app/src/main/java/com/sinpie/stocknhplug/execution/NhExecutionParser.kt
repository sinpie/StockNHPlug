package com.sinpie.stocknhplug.execution

import com.sinpie.stocknhplug.domain.Execution
import org.json.JSONArray
import org.json.JSONObject

/** Official current Output_0 rows and the older Output_1 envelope are distinct dialects. */
internal object NhExecutionParser {
    fun rows(page: JSONObject): List<JSONObject> {
        val current = page.opt("Output_0")
        val legacy = page.opt("Output_1")
        val block =
            when {
                current is JSONArray -> {
                    require(!page.has("Output_1"))
                    current
                }
                page.has("Output_1") -> {
                    require(legacy is JSONArray)
                    legacy
                }
                !page.has("Output_0") ->
                    return emptyList() // ResponseGuard already validated empty success.
                else -> error("Invalid execution block")
            }
        return (0 until block.length()).map { block.getJSONObject(it) }
    }

    fun parse(page: JSONObject): List<Execution> =
        rows(page).map { row ->
            fun quantity(name: String): Long =
                row.get(name).toString().toBigDecimal().longValueExact()
            val ordered = quantity("orr_qty")
            val filled = quantity("tot_cns_qty")
            val remaining = quantity("ny_cns_qty")
            require(ordered > 0 && filled in 0..ordered && remaining in 0..(ordered - filled))
            val number = row.get("itg_orr_no").toString()
            require(number.toBigDecimal().longValueExact() > 0)
            val symbol = row.getString("iem_cd")
            require(symbol.matches(Regex("[0-9]{6}")))
            // Use cumulative execution amount / quantity, never guess a price scale (e.g. ×1000).
            val average =
                if (filled == 0L) 0.0
                else if (row.has("cns_amt")) {
                    val amount = quantity("cns_amt")
                    require(amount > 0)
                    amount.toDouble() / filled
                } else {
                    require(page.opt("Output_0") !is JSONArray)
                    row.getDouble("cns_avg_uit_pr")
                }
            require(average.isFinite() && (filled == 0L || average > 0))
            Execution(
                number,
                symbol,
                row.getString("iem_nm"),
                row.getString("sby_dit_cd_nm"),
                ordered,
                filled,
                remaining,
                average,
            )
        }
}

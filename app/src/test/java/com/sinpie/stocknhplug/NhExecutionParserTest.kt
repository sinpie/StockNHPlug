package com.sinpie.stocknhplug

import com.sinpie.stocknhplug.execution.NhExecutionParser
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NhExecutionParserTest {
    private fun row() =
        JSONObject()
            .put("itg_orr_no", 123)
            .put("iem_cd", "005930")
            .put("iem_nm", "test")
            .put("sby_dit_cd_nm", "매수")
            .put("orr_qty", 2)
            .put("tot_cns_qty", 1)
            .put("ny_cns_qty", 1)
            .put("cns_amt", 70000)
            .put("cns_avg_uit_pr", 70.0)

    @Test
    fun currentArrayIsNotSilentlyLostAndAmountDeterminesAverage() {
        val result = NhExecutionParser.parse(JSONObject().put("Output_0", JSONArray().put(row())))
        assertEquals(1, result.size)
        assertEquals(70000.0, result.single().average, 0.0)
    }

    @Test
    fun legacyEnvelopeAndEmptyCurrentArrayAreSupported() {
        assertEquals(
            1,
            NhExecutionParser.parse(
                    JSONObject()
                        .put("Output_0", JSONObject())
                        .put("Output_1", JSONArray().put(row()))
                )
                .size,
        )
        assertTrue(NhExecutionParser.parse(JSONObject().put("Output_0", JSONArray())).isEmpty())
    }

    @Test
    fun malformedOrAmbiguousBlocksFailRatherThanLookingEmpty() {
        for (page in
            listOf(
                JSONObject().put("Output_0", JSONObject()),
                JSONObject().put("Output_1", "bad"),
                JSONObject().put("Output_0", JSONArray()).put("Output_1", JSONArray()),
            )) {
            assertTrue(runCatching { NhExecutionParser.parse(page) }.isFailure)
        }
    }

    @Test
    fun fractionalAndInconsistentQuantitiesAreRejected() {
        for (bad in
            listOf(
                row().put("tot_cns_qty", 1.5),
                row().put("ny_cns_qty", 2),
                row().put("cns_amt", -1),
            )) {
            assertTrue(
                runCatching {
                        NhExecutionParser.parse(JSONObject().put("Output_0", JSONArray().put(bad)))
                    }
                    .isFailure
            )
        }
    }
}

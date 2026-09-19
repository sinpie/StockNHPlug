package com.sinpie.stocknhplug.research

import com.sinpie.stocknhplug.execution.objects
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/** Official OpenDART only. No HTML crawl, no article text, no third-party proxy. */
class DartClient(private val apiKey: String) {
    private val client=OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).callTimeout(20,TimeUnit.SECONDS).build()
    private suspend fun get(path: String, params: Map<String,String>): JSONObject = withContext(Dispatchers.IO) {
        require(apiKey.matches(Regex("[A-Za-z0-9]{40}"))) { "OpenDART 인증키 형식을 확인하세요." }
        val url="https://opendart.fss.or.kr/api/$path.json".toHttpUrl().newBuilder().addQueryParameter("crtfc_key",apiKey)
        params.forEach { (k,v)->url.addQueryParameter(k,v) }
        delay(300)
        client.newCall(Request.Builder().url(url.build()).build()).execute().use { response ->
            check(response.isSuccessful) { "공시 API 통신 실패" }
            val j=JSONObject(response.body?.string() ?: error("공시 응답 없음"))
            check(j.optString("status") in setOf("000","013")) { "공시 API 인증·호출 한도를 확인하세요." }; j
        }
    }
    suspend fun disclosures(corp: String, from: LocalDate, to: LocalDate): List<Disclosure> {
        require(corp.matches(Regex("[0-9]{8}")))
        val list=mutableListOf<Disclosure>(); var page=1; var total=1
        do {
            val j=get("list",mapOf("corp_code" to corp,"bgn_de" to from.format(DateTimeFormatter.BASIC_ISO_DATE),"end_de" to to.format(DateTimeFormatter.BASIC_ISO_DATE),"page_count" to "100","page_no" to page.toString()))
            total=j.optInt("total_page",1); check(total<=20) { "공시 조회 범위를 줄이세요." }
            list += j.optJSONArray("list")?.objects().orEmpty().map { Disclosure(it.getString("rcept_no"),it.getString("report_nm"),LocalDate.parse(it.getString("rcept_dt"),DateTimeFormatter.BASIC_ISO_DATE)) }
            page++
        } while(page<=total)
        return list.distinctBy { it.receipt }
    }
    suspend fun financials(corp: String, year: Int, reportCode: String): FinancialReport? {
        require(corp.matches(Regex("[0-9]{8}")) && year>=2015 && reportCode in setOf("11011","11012","11013","11014"))
        val j=get("fnlttSinglAcntAll",mapOf("corp_code" to corp,"bsns_year" to year.toString(),"reprt_code" to reportCode,"fs_div" to "CFS"))
        val rows=j.optJSONArray("list")?.objects().orEmpty(); if(rows.isEmpty()) return null
        // Use standard XBRL IDs, never fuzzy-match company-specific labels to invent financial values.
        fun amount(vararg ids: String)=rows.firstOrNull { it.optString("account_id") in ids }?.optString("thstrm_amount")?.replace(",","")?.toBigDecimalOrNull()
        return FinancialReport(year,reportCode,rows.first().getString("rcept_no"),true,amount("ifrs-full_Revenue","ifrs_Revenue"),
            amount("dart_OperatingIncomeLoss"),amount("ifrs-full_Equity","ifrs_Equity"),amount("ifrs-full_Liabilities","ifrs_Liabilities"),Instant.now())
    }
}

package com.sinpie.stocknhplug.execution

import com.sinpie.stocknhplug.data.SecureVault
import com.sinpie.stocknhplug.domain.Environment
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class NhTransport(private val vault: SecureVault, val environment: Environment) {
    val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS).retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build()
    private val gate = Mutex()
    private var lastCall = 0L
    private val base = if (environment == Environment.MOCK) "https://moapi.nhplug.com:8443" else "https://api.nhplug.com:8443"
    suspend fun token(): String = withContext(Dispatchers.IO) { gate.withLock { tokenLocked() } }
    private suspend fun throttle() {
        val elapsed = (System.nanoTime() - lastCall) / 1_000_000
        if (elapsed < 250) delay(250 - elapsed)
        lastCall = System.nanoTime()
    }
    private suspend fun tokenLocked(): String {
        val saved = vault.read("token")
        if (saved != null && saved.getLong("expires") > System.currentTimeMillis() + 60_000) return saved.getString("value")
        val credentials = vault.read("credentials") ?: error("앱키를 먼저 저장하세요.")
        val url = "https://api.nhplug.com:8443/oauth2/token".toHttpUrl().newBuilder()
            .addQueryParameter("appkey",credentials.getString("key")).addQueryParameter("appsecretkey",credentials.getString("secret"))
            .addQueryParameter("grant_type","client_credentials").addQueryParameter("scope","oob").build()
        throttle()
        client.newCall(Request.Builder().url(url).post("".toRequestBody("application/x-www-form-urlencoded".toMediaType())).build()).execute().use { r ->
            check(r.isSuccessful) { "NHPlug 인증 실패 (HTTP ${r.code})" }
            val j=JSONObject(r.body?.string() ?: error("인증 응답 없음"))
            val token=j.getString("access_token"); check(token.isNotBlank())
            val seconds=j.getLong("expires_in"); check(seconds in 60..172800)
            vault.write("token",JSONObject().put("value",token).put("expires",System.currentTimeMillis()+seconds*1000))
            return token
        }
    }
    suspend fun pages(path: String, input: JSONObject, paginate: Boolean = false, dispatchGuard: () -> Unit = {}): List<JSONObject> = withContext(Dispatchers.IO) {
        gate.withLock {
            require(path.startsWith("/krstock/") || path == "/n2/acctinfo")
            val token=tokenLocked(); var cts=""; val seen=mutableSetOf<String>(); val result=mutableListOf<JSONObject>()
            do {
                throttle(); currentCoroutineContext().ensureActive(); dispatchGuard()
                val request=Request.Builder().url(base+path).header("Authorization","Bearer $token")
                    .post(JSONObject().put("Input_0",input).toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                if (cts.isNotEmpty()) request.header("cts",cts).header("cts_flag","Y")
                client.newCall(request.build()).execute().use { r ->
                    if (r.code==401) vault.delete("token") // no hidden retry, especially for orders
                    check(r.isSuccessful) { "NHPlug 요청 실패 (HTTP ${r.code})" }
                    val j=JSONObject(r.body?.string() ?: error("응답 없음"))
                    ResponseGuard.validate(j)
                    result += j
                    val more=r.header("cts_flag")=="Y" || j.optString("rsp_cd") in setOf("00165","00218")
                    cts=if(more) r.header("cts").orEmpty() else ""
                    if(more) {
                        check(paginate && cts.isNotBlank() && seen.add(cts) && result.size < 100) { "연속조회가 완결되지 않았습니다." }
                    }
                }
            } while(cts.isNotEmpty())
            result
        }
    }
    suspend fun call(path: String, input: JSONObject) = pages(path,input).single()
}

object ResponseGuard {
    fun validate(j: JSONObject) {
        val message=j.optJSONObject("message")
        val level=message?.optString("msg_lv_code").orEmpty()
        val msg=j.optString("rsp_msg", message?.optString("usr_msg").orEmpty())
        check(level !in setOf("E","F") && !Regex("오류|실패|불가|거부|입력하|유효하지|초과|error|fail",RegexOption.IGNORE_CASE).containsMatchIn(msg)) { "NHPlug 업무 오류 (${safeCode(j)})" }
        val hasData=j.keys().asSequence().filter { it.startsWith("Output_") }.any { key ->
            when(val b=j.opt(key)) { is JSONObject -> b.length()>0; is org.json.JSONArray -> b.length()>0; else -> false }
        }
        check(hasData || Regex("정상|완료|내역.*없|자료.*없|조회.*없").containsMatchIn(msg)) { "확인할 수 없는 NHPlug 응답 (${safeCode(j)})" }
    }
    private fun safeCode(j: JSONObject)=j.optString("rsp_cd",j.optJSONObject("message")?.optString("msg_code").orEmpty()).filter { it.isLetterOrDigit() }.take(16)
}

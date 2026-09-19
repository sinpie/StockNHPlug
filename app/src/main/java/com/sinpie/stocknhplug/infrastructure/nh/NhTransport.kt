package com.sinpie.stocknhplug.infrastructure.nh

import com.sinpie.stocknhplug.data.SecureVault
import com.sinpie.stocknhplug.domain.Environment
import com.sinpie.stocknhplug.infrastructure.json.json
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * NHPlug 실제 HTTP 전송 계층. gate는 토큰 생성과 모든 REST 요청을 직렬화한다. 블로킹 OkHttp 호출은 Dispatchers.IO에서 수행하며 자동
 * 재시도/리다이렉트를 금지한다.
 */
class NhTransport(private val vault: SecureVault, val environment: Environment) {
    val client =
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    private val gate = Mutex()
    private var lastCall = 0L
    private val base =
        if (environment == Environment.MOCK) "https://moapi.nhplug.com:8443"
        else "https://api.nhplug.com:8443"

    /** WebSocket에서 사용할 토큰을 반환한다. 캐시 접근도 같은 gate로 보호한다. */
    suspend fun token(): String = withContext(Dispatchers.IO) { gate.withLock { tokenLocked() } }

    /** 단조 시계로 호출 간격을 제한한다. 휴대폰 날짜 변경이 호출 제한을 우회하지 못하게 한다. */
    private suspend fun throttle() {
        val elapsed = (System.nanoTime() - lastCall) / 1_000_000
        if (elapsed < 250) delay(250 - elapsed)
        lastCall = System.nanoTime()
    }

    /** gate 소유자만 호출한다. 만료 전 토큰을 재사용하고 신규 토큰은 발급 완료 후 암호화 저장한다. */
    private suspend fun tokenLocked(): String {
        val saved = vault.read("token")
        if (saved != null && saved.getLong("expires") > System.currentTimeMillis() + 60_000)
            return saved.getString("value")
        val credentials = vault.read("credentials") ?: error("앱키를 먼저 저장하세요.")
        val url =
            "https://api.nhplug.com:8443/oauth2/token"
                .toHttpUrl()
                .newBuilder()
                .addQueryParameter("appkey", credentials.getString("key"))
                .addQueryParameter("appsecretkey", credentials.getString("secret"))
                .addQueryParameter("grant_type", "client_credentials")
                .addQueryParameter("scope", "oob")
                .build()
        throttle()
        client
            .newCall(
                Request.Builder()
                    .url(url)
                    .post("".toRequestBody("application/x-www-form-urlencoded".toMediaType()))
                    .build()
            )
            .execute()
            .use { r ->
                check(r.isSuccessful) { "NHPlug 인증 실패 (HTTP ${r.code})" }
                val j = JSONObject(r.body?.string() ?: error("인증 응답 없음"))
                val token = j.getString("access_token")
                check(token.isNotBlank())
                val seconds = j.getLong("expires_in")
                check(seconds in 60..172800)
                vault.write(
                    "token",
                    JSONObject()
                        .put("value", token)
                        .put("expires", System.currentTimeMillis() + seconds * 1000),
                )
                return token
            }
    }

    /**
     * Input_0 봉투와 응답 연속조회 헤더를 처리한다. dispatchGuard는 대기 후 실제 전송 직전 주문 유효시간을 재검사한다. 중복/누락 연속키나 미완료
     * 페이지는 부분 결과를 반환하지 않고 실패한다.
     */
    suspend fun pages(
        path: String,
        input: JSONObject,
        paginate: Boolean = false,
        dispatchGuard: () -> Unit = {},
    ): List<JSONObject> =
        withContext(Dispatchers.IO) {
            gate.withLock {
                require(path.startsWith("/krstock/") || path == "/n2/acctinfo")
                val token = tokenLocked()
                var cts = ""
                val seen = mutableSetOf<String>()
                val result = mutableListOf<JSONObject>()
                do {
                    throttle()
                    currentCoroutineContext().ensureActive()
                    dispatchGuard()
                    val request =
                        Request.Builder()
                            .url(base + path)
                            .header("Authorization", "Bearer $token")
                            .post(
                                JSONObject()
                                    .put("Input_0", input)
                                    .toString()
                                    .toRequestBody("application/json; charset=utf-8".toMediaType())
                            )
                    if (cts.isNotEmpty()) request.header("cts", cts).header("cts_flag", "Y")
                    client.newCall(request.build()).execute().use { r ->
                        if (r.code == 401)
                            vault.delete("token") // no hidden retry, especially for orders
                        check(r.isSuccessful) { "NHPlug 요청 실패 (HTTP ${r.code})" }
                        val j = JSONObject(r.body?.string() ?: error("응답 없음"))
                        ResponseGuard.validate(j)
                        result += j
                        val more =
                            r.header("cts_flag") == "Y" ||
                                j.optString("rsp_cd") in setOf("00165", "00218")
                        cts = if (more) r.header("cts").orEmpty() else ""
                        if (more) {
                            check(
                                paginate && cts.isNotBlank() && seen.add(cts) && result.size < 100
                            ) {
                                "연속조회가 완결되지 않았습니다."
                            }
                        }
                    }
                } while (cts.isNotEmpty())
                result
            }
        }

    /** 단일 응답 전용 편의 함수. 페이지가 더 필요한 호출은 pages(..., paginate=true)를 사용한다. */
    suspend fun call(path: String, input: JSONObject) = pages(path, input).single()
}

/** HTTP 200 내부 업무 오류를 걸러내는 보수적 검사. 코드/자유문구의 완전한 명세를 대체하지 않으므로 계좌 통합 검증이 필요하다. */
object ResponseGuard {
    /** 오류 심각도·문구를 먼저 확인하고 실제 데이터 또는 확인 가능한 빈 조회만 허용한다. */
    fun validate(j: JSONObject) {
        val message = j.optJSONObject("message")
        val level = message?.optString("msg_lv_code").orEmpty()
        val msg = j.optString("rsp_msg", message?.optString("usr_msg").orEmpty())
        check(
            level !in setOf("E", "F") &&
                !Regex("오류|실패|불가|거부|입력하|유효하지|초과|error|fail", RegexOption.IGNORE_CASE)
                    .containsMatchIn(msg)
        ) {
            "NHPlug 업무 오류 (${safeCode(j)})"
        }
        val hasData =
            j.keys()
                .asSequence()
                .filter { it.startsWith("Output_") }
                .any { key ->
                    when (val b = j.opt(key)) {
                        is JSONObject -> b.length() > 0
                        is org.json.JSONArray -> b.length() > 0
                        else -> false
                    }
                }
        check(hasData || Regex("정상|완료|내역.*없|자료.*없|조회.*없").containsMatchIn(msg)) {
            "확인할 수 없는 NHPlug 응답 (${safeCode(j)})"
        }
    }

    /** 문의용 코드만 제한적으로 노출한다. 응답 본문이나 사용자 메시지 전체를 로그로 반환하지 않는다. */
    private fun safeCode(j: JSONObject) =
        j.optString("rsp_cd", j.optJSONObject("message")?.optString("msg_code").orEmpty())
            .filter { it.isLetterOrDigit() }
            .take(16)
}

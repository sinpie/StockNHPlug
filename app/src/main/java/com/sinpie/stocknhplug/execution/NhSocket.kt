package com.sinpie.stocknhplug.execution

import com.sinpie.stocknhplug.application.MarketStream
import com.sinpie.stocknhplug.domain.*
import com.sinpie.stocknhplug.infrastructure.json.*
import com.sinpie.stocknhplug.infrastructure.nh.NhTransport
import java.time.*
import java.time.format.DateTimeFormatter
import okhttp3.*
import org.json.JSONObject

/**
 * A disconnected stream invalidates prices. Reconnection is explicit and requires account resync.
 */
class NhSocket(
    private val transport: NhTransport,
    private val onQuote: (Quote) -> Unit,
    private val onEvent: (String) -> Unit,
    private val onDisconnect: () -> Unit,
) : MarketStream {
    private var socket: WebSocket? = null
    @Volatile private var generation = 0

    /** 기존 연결 세대를 폐기하고 새 구독을 요청한다. 오래된 콜백은 generation으로 배제한다. */
    override suspend fun connect(symbols: List<String>) {
        close()
        require(symbols.size <= 10 && symbols.all { it.matches(Regex("[0-9]{6}")) })
        val token = transport.token()
        val id = ++generation
        val url =
            if (transport.environment == Environment.MOCK) "wss://moapi.nhplug.com:17070/websocket"
            else "wss://api.nhplug.com:7070/websocket"
        socket =
            transport.client.newWebSocket(
                Request.Builder().url(url).build(),
                object : WebSocketListener() {
                    /** 시세와 계좌 체결통보를 구독한다. 연결 성공 자체가 가격 데이터 유효성을 의미하지 않는다. */
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        if (id != generation) {
                            webSocket.close(1000, "stopped")
                            return
                        }
                        symbols.forEach { symbol ->
                            webSocket.send(
                                json(
                                        "header" to json("token" to token, "tr_type" to "1"),
                                        "body" to json("tr_cd" to "oc", "tr_key" to symbol),
                                    )
                                    .toString()
                            )
                        }
                        // Account execution notifications trigger authoritative REST refresh, never
                        // additive fill accounting.
                        webSocket.send(
                            json(
                                    "header" to json("token" to token, "tr_type" to "1"),
                                    "body" to json("tr_cd" to "d2", "tr_key" to ""),
                                )
                                .toString()
                        )
                        onEvent("WebSocket 연결 · 실시간 시세 대기")
                    }

                    /** 서버 JSON을 채널별로 분기한다. 체결 통보를 보유수량에 가산하지 않고 REST 조회를 정본으로 유지한다. */
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        if (id != generation) return
                        try {
                            val j = JSONObject(text)
                            if (j.has("rsp_cd") && j.optString("rsp_cd").startsWith("WSS")) {
                                onDisconnect()
                                return
                            }
                            val header = j.optJSONObject("header") ?: return
                            val body = j.optJSONObject("body") ?: return
                            when (header.optString("tr_cd")) {
                                "oc" ->
                                    parseQuote(body, Instant.now())
                                        ?.takeIf { it.symbol in symbols }
                                        ?.let(onQuote)
                                "d2" -> onEvent("체결 통보 수신 · 다음 잔고 동기화에서 확인")
                            }
                        } catch (_: Exception) {
                            onEvent("시세 형식 오류 · 해당 메시지 제외")
                        }
                    }

                    override fun onFailure(
                        webSocket: WebSocket,
                        t: Throwable,
                        response: Response?,
                    ) {
                        if (id == generation) onDisconnect()
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        if (id == generation) onDisconnect()
                    }
                },
            )
    }

    /** 세대 번호를 먼저 변경하므로 의도적으로 닫은 소켓의 종료 콜백이 새 세션을 정지시키지 않는다. */
    override fun close() {
        generation++
        socket?.close(1000, "session ended")
        socket = null
    }

    companion object {
        /** 서울 거래일과 거래소 시각을 결합한다. 잘못된 종목/호가·오래된 시각은 null로 제외한다. */
        fun parseQuote(body: JSONObject, now: Instant): Quote? {
            val symbol = body.getString("code")
            if (!symbol.matches(Regex("[0-9]{6}"))) return null
            val digits = body.getString("time").replace(":", "")
            if (digits.length != 6) return null
            val exchange =
                LocalDateTime.of(
                        now.atZone(SEOUL).toLocalDate(),
                        LocalTime.parse(digits, DateTimeFormatter.ofPattern("HHmmss")),
                    )
                    .atZone(SEOUL)
                    .toInstant()
            return Quote(
                    symbol,
                    body.getLong("price"),
                    body.getLong("bid"),
                    body.getLong("offer"),
                    now,
                    exchange,
                    body.optString("janggubun") == "0",
                )
                .takeIf { it.price > 0 && it.bid > 0 && it.ask >= it.bid && it.fresh(now) }
        }
    }
}

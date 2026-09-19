package com.sinpie.stocknhplug.execution

import com.sinpie.stocknhplug.application.MarketStream
import com.sinpie.stocknhplug.domain.*
import com.sinpie.stocknhplug.infrastructure.json.*
import com.sinpie.stocknhplug.infrastructure.nh.NhTransport
import java.time.*
import java.time.format.DateTimeFormatter
import okhttp3.*
import org.json.JSONObject

/** 단일 시세 연결을 공유한다. 주문/체결은 모의 REST로 대사해 별도 통보 WS 세션을 쓰지 않는다. */
class NhSocket(
    private val transport: NhTransport,
    private val onQuote: (Quote) -> Unit,
    private val onEvent: (String) -> Unit,
    private val onDisconnect: () -> Unit,
) : MarketStream {
    private var socket: WebSocket? = null
    @Volatile private var generation = 0
    @Volatile private var opened = false
    private var token = ""
    private var desired = emptySet<String>()
    private val acked = mutableSetOf<String>()

    override suspend fun connect(symbols: List<String>) {
        close()
        require(symbols.size <= 10 && symbols.all { it.matches(Regex("[0-9]{6}")) })
        val value = transport.token()
        val id: Int
        synchronized(this) {
            token = value
            desired = symbols.toSet()
            id = ++generation
        }
        val created =
            transport.client.newWebSocket(
                Request.Builder().url("wss://api.nhplug.com:7070/websocket").build(),
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        synchronized(this@NhSocket) {
                            if (id != generation) {
                                webSocket.close(1000, "stopped")
                                return
                            }
                            socket = webSocket
                            opened = true
                            desired.forEach { send(it, true) }
                        }
                        onEvent("시세 WebSocket 연결 · 구독 확인 대기")
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        if (id != generation) return
                        try {
                            val j = JSONObject(text)
                            val header = j.optJSONObject("header") ?: return
                            val body = j.optJSONObject("body")
                            if (header.has("rsp_cd")) {
                                val accepted = acknowledgedSymbols(j)
                                synchronized(this@NhSocket) {
                                    if (id != generation) return
                                    acked += accepted.intersect(desired)
                                }
                                if (header.optString("rsp_cd") != "00000")
                                    onEvent("시세 구독 미확인 · REST 조회 유지")
                                return
                            }
                            if (header.optString("tr_cd") != "oc" || body == null) return
                            val quote = parseQuote(body, Instant.now()) ?: return
                            val valid =
                                synchronized(this@NhSocket) {
                                    id == generation &&
                                        quote.symbol in acked &&
                                        quote.symbol in desired
                                }
                            if (valid) onQuote(quote)
                        } catch (_: Exception) {
                            onEvent("시세 형식 오류 · 해당 메시지 제외")
                        }
                    }

                    private fun disconnected() {
                        synchronized(this@NhSocket) {
                            if (id != generation) return
                            opened = false
                            acked.clear()
                        }
                        onDisconnect()
                    }

                    override fun onFailure(
                        webSocket: WebSocket,
                        t: Throwable,
                        response: Response?,
                    ) = disconnected()

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) =
                        disconnected()
                },
            )
        synchronized(this) {
            if (id == generation) socket = created else created.close(1000, "stopped")
        }
    }

    /** 연결 재생성 없이 차이만 전송한다. ACK 전 가격은 상위로 전달하지 않는다. */
    @Synchronized
    override fun replaceSubscriptions(symbols: Set<String>) {
        require(symbols.size <= 10 && symbols.all { it.matches(Regex("[0-9]{6}")) })
        val removed = desired - symbols
        val added = symbols - desired
        desired = symbols.toSet()
        acked.retainAll(desired)
        if (opened) {
            removed.forEach { send(it, false) }
            added.forEach { send(it, true) }
        }
    }

    private fun send(symbol: String, subscribe: Boolean) {
        if (
            socket?.send(
                json(
                        "header" to
                            json("token" to token, "tr_type" to if (subscribe) "1" else "2"),
                        "body" to json("tr_cd" to "oc", "tr_key" to symbol),
                    )
                    .toString()
            ) != true
        )
            acked -= symbol
    }

    @Synchronized override fun acknowledged() = acked.toSet()

    override fun isConnected() = opened

    @Synchronized
    override fun close() {
        generation++
        opened = false
        acked.clear()
        desired = emptySet()
        token = ""
        socket?.close(1000, "session ended")
        socket = null
    }

    companion object {
        /** NH ACK는 header의 결과/채널과 body.tr_key 배열을 함께 확인해야 한다. */
        fun acknowledgedSymbols(message: JSONObject): Set<String> {
            val header = message.optJSONObject("header") ?: return emptySet()
            if (
                header.optString("rsp_cd") != "00000" ||
                    header.optString("tr_cd") != "oc" ||
                    header.optString("tr_type") != "1"
            )
                return emptySet()
            val body = message.optJSONObject("body") ?: return emptySet()
            val keys = body.optJSONArray("tr_key")
            val values =
                if (keys == null) listOf(body.optString("tr_key"))
                else (0 until keys.length()).map { keys.optString(it) }
            return values.filter { it.matches(Regex("[0-9]{6}")) }.toSet()
        }

        /** 서울 거래일과 거래소 시각을 결합한다. 낡은 시세는 추적·주문 양쪽에서 제외한다. */
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

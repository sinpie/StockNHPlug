package com.sinpie.stocknhplug.application

import com.sinpie.stocknhplug.domain.*
import com.sinpie.stocknhplug.research.ResearchRepository

/** 응용 계층의 영속 저장 포트. Android/JSON/Keystore 타입을 호출자에게 노출하지 않는다. */
interface ApplicationStorage : OrderJournal {
    fun strategyBook(): StrategyBook

    fun saveStrategyBook(book: StrategyBook)

    fun groupFills(): List<GroupFillReport>

    fun saveGroupFills(reports: List<GroupFillReport>)

    fun settings(): Strategy

    fun saveSettings(settings: Strategy)

    fun events(): List<Event>

    fun log(level: String, message: String)

    fun snapshots(): List<HoldingSnapshot>

    fun snapshot(account: Account, environment: Environment, portfolio: Portfolio)

    fun hasCredentials(): Boolean

    /** 키는 쓰기 전용 입력이다. 화면/컨트롤러가 원문 키를 조회하는 API를 제공하지 않는다. */
    fun saveCredentials(key: String, secret: String, dart: String)

    /** 진행 중인 작업이 모두 끝난 뒤에만 호출한다. 서버 토큰 폐기는 아니다. */
    fun clear()
}

/** 실시간 채널 수명 포트. 전송 프로토콜과 토큰은 구현체만 알고 있다. */
interface MarketStream {
    suspend fun connect(symbols: List<String>)

    /** 연결을 재생성하지 않고 차이만 구독/해제한다. ACK 전 시세는 거래에 사용하지 않는다. */
    fun replaceSubscriptions(symbols: Set<String>)

    fun acknowledged(): Set<String>

    fun isConnected(): Boolean

    fun close()
}

/** 하나의 연결에 소속되는 의존성 묶음. 데이터 제공자는 주문 Broker와 별도로 주입한다. */
data class BrokerSession(
    val broker: Broker,
    val stream: MarketStream,
    val research: ResearchRepository,
    val groupExecutions: GroupExecutionSource? = null,
    val currentPrices: CurrentPriceProvider? = null,
)

/** 객체 생성은 composition root로 위임한다. 응용 계층에서 NHPlug/Android 클래스를 생성하지 않는다. */
fun interface SessionFactory {
    fun create(
        onQuote: (Quote) -> Unit,
        onEvent: (String) -> Unit,
        onDisconnect: () -> Unit,
    ): BrokerSession
}

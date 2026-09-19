package com.sinpie.stocknhplug.application

import com.sinpie.stocknhplug.domain.*
import com.sinpie.stocknhplug.research.*
import com.sinpie.stocknhplug.trading.TradingEngine
import java.time.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Compose에 노출되는 화면 상태. 키·토큰은 포함하지 않는다. connected는 초기 동기화와 구독 요청 완료이며 시세 신선도는 별도 검사한다. */
data class AppState(
    val settings: Strategy = Strategy(),
    val accounts: List<Account> = emptyList(),
    val selected: Account? = null,
    val portfolio: Portfolio? = null,
    val candidates: List<Candidate> = emptyList(),
    val research: List<ResearchEvidence> = emptyList(),
    val quotes: Map<String, Quote> = emptyMap(),
    val orders: List<OrderRecord> = emptyList(),
    val executions: List<Execution> = emptyList(),
    val events: List<Event> = emptyList(),
    val snapshots: List<HoldingSnapshot> = emptyList(),
    val pnl: List<DailyPnl> = emptyList(),
    val busy: Boolean = false,
    val running: Boolean = false,
    val connected: Boolean = false,
    val message: String = "NHPlug 모의계좌를 연결해 시작하세요.",
    val hasCredentials: Boolean = false,
    val storageError: Boolean = false,
)

/** Application layer owns orchestration, never endpoint fields or order price math. */
class TradingController(
    private val store: ApplicationStorage,
    private val sessionFactory: SessionFactory,
    private val strategy: TradingStrategy,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var broker: Broker? = null
    private var socket: MarketStream? = null
    private var engine: TradingEngine? = null
    private var loop: Job? = null
    private var researchRepository: ResearchRepository? = null
    private val mutable = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = mutable

    init {
        try {
            mutable.value =
                mutable.value.copy(
                    settings = store.settings(),
                    orders = store.records(),
                    events = store.events(),
                    snapshots = store.snapshots(),
                    hasCredentials = store.hasCredentials(),
                )
        } catch (_: Exception) {
            mutable.value =
                mutable.value.copy(
                    storageError = true,
                    message = "보안 저장소를 열 수 없습니다. 자동매매를 잠갔습니다. 삭제 전 증권사 주문을 확인하세요.",
                )
        }
    }

    /** Main dispatcher에서 불변 상태를 교체한다. 소켓 콜백도 scope.launch를 거쳐 들어와야 한다. */
    private fun change(block: (AppState) -> AppState) {
        mutable.value = block(mutable.value)
    }

    /** 통제된 앱 메시지만 저장한다. 저장 실패는 거래 잠금이며 원시 예외/HTTP 본문을 전달하지 않는다. */
    private fun log(message: String, level: String = "INFO") {
        try {
            store.log(level, message)
            change { it.copy(events = store.events(), orders = store.records(), message = message) }
        } catch (_: Exception) {
            engine?.stop()
            change { it.copy(running = false, storageError = true, message = "기록 저장 실패 · 자동매매 잠금") }
        }
    }

    /** 정지 상태에서 키를 교체한다. 이전 계정의 토큰·브로커·조회 결과를 함께 무효화한다. */
    fun saveCredentials(key: String, secret: String, dart: String) = task {
        check(!state.value.running)
        require(key.isNotBlank() && secret.isNotBlank())
        store.saveCredentials(key, secret, dart)
        socket?.close()
        broker = null
        engine = null
        researchRepository = null
        change {
            it.copy(
                hasCredentials = true,
                connected = false,
                accounts = emptyList(),
                selected = null,
                portfolio = null,
                quotes = emptyMap(),
                executions = emptyList(),
                pnl = emptyList(),
                research = emptyList(),
                candidates = emptyList(),
            )
        }
        log("인증정보를 기기 내부에 암호화 저장했습니다.")
    }

    /** 전략 검증과 암호화 저장 후 구독을 해제한다. 새 종목 목록은 명시적 재연결 때 적용된다. */
    fun saveSettings(settings: Strategy) = task {
        check(!state.value.running)
        store.saveSettings(settings)
        socket?.close()
        change {
            it.copy(
                settings = settings,
                candidates = emptyList(),
                research = emptyList(),
                connected = false,
                quotes = emptyMap(),
            )
        }
        log("전략 설정 저장 완료 · 변경된 관심종목을 적용하려면 계좌를 다시 연결하세요.")
    }

    /** 연결 버튼 진입점: 준비 상태 초기화 → 인증/계좌 목록 → 잔고·체결 → WebSocket 구독 순서다. 중간 실패는 connected=false를 유지한다. */
    fun connect() = task {
        check(!state.value.running && !state.value.storageError)
        socket?.close()
        change {
            it.copy(
                connected = false,
                accounts = emptyList(),
                selected = null,
                portfolio = null,
                executions = emptyList(),
                pnl = emptyList(),
                quotes = emptyMap(),
            )
        }
        val session =
            sessionFactory.create(
                { quote ->
                    scope.launch {
                        change { it.copy(quotes = it.quotes + (quote.symbol to quote)) }
                    }
                },
                { msg -> scope.launch { log(msg) } },
                {
                    scope.launch {
                        stop("실시간 연결 끊김 · 주문 정지")
                        change { it.copy(connected = false, quotes = emptyMap()) }
                    }
                },
            )
        broker = session.broker
        check(session.broker.environment == Environment.MOCK) { "현재 배포 구성은 모의 거래만 허용합니다." }
        socket = session.stream
        researchRepository = session.research
        engine = TradingEngine(session.broker, store, strategy)
        val accounts = session.broker.accounts()
        check(
            accounts.isNotEmpty() &&
                accounts.all {
                    it.brokerId == session.broker.id && it.validFor(session.broker.environment)
                }
        ) {
            "사용 가능한 계좌가 없습니다. API 신청 상태를 확인하세요."
        }
        change { it.copy(accounts = accounts, selected = accounts.first(), quotes = emptyMap()) }
        refreshInternal()
        subscribeSelectedAccount()
        change { it.copy(connected = true) }
        log("모의투자 연결 완료 · ${accounts.size}개 계좌")
    }

    /** 계좌 선택 진입점. 이전 계좌의 가격과 손익을 지우고 새 계좌의 보유종목을 다시 구독한다. */
    fun select(account: Account) = task {
        check(!state.value.running && account in state.value.accounts)
        socket?.close()
        change {
            it.copy(
                connected = false,
                selected = account,
                portfolio = null,
                executions = emptyList(),
                pnl = emptyList(),
                quotes = emptyMap(),
            )
        }
        refreshInternal()
        subscribeSelectedAccount()
        change { it.copy(connected = true) }
    }

    /** 보유종목 보호를 관심종목보다 우선한다. 구독 상한을 넘으면 조용히 일부를 제외하지 않는다. */
    private suspend fun subscribeSelectedAccount() {
        val symbols =
            (state.value.portfolio!!.holdings.map { it.symbol } + state.value.settings.symbols)
                .distinct()
        check(symbols.size <= 10) { "관심종목과 보유종목 합계가 실시간 관리 한도를 초과합니다." }
        socket!!.connect(symbols)
    }

    /** 선택 계좌의 잔고·체결을 모두 조회한 후 스냅샷을 저장한다. 일부 응답만 성공한 상태를 완성된 화면으로 게시하지 않는다. */
    private suspend fun refreshInternal() {
        val account = state.value.selected ?: error("계좌를 먼저 연결하세요.")
        val portfolio = broker!!.portfolio(account)
        val executions = broker!!.executions(account, LocalDate.now(SEOUL))
        withContext(Dispatchers.IO) { store.snapshot(account, Environment.MOCK, portfolio) }
        change {
            it.copy(
                portfolio = portfolio,
                executions = executions,
                orders = store.records(),
                snapshots = store.snapshots(),
            )
        }
    }

    /** 사용자 새로고침 요청. 네트워크 오류는 task 경계에서 안전한 앱 문구로 변환한다. */
    fun refresh() = task {
        refreshInternal()
        log("잔고·주문체결 동기화 완료")
    }

    /** 계좌 전체의 증권사 손익을 조회한다. 앱의 특정 전략 성과와 구별해서 표시한다. */
    fun refreshPnl() = task {
        val account = state.value.selected ?: error("계좌 연결 필요")
        val items = broker!!.dailyPnl(account)
        change { it.copy(pnl = items) }
        log("최근 30일 증권사 손익 조회 완료")
    }

    /** 종목-기업 매핑을 검증하고 공식 데이터와 지표를 조합한다. 점수 계산은 매수 승인과 별개다. */
    fun analyze(corpMapping: String, year: Int, reportCode: String) = task {
        check(!state.value.running)
        val repository = researchRepository ?: error("계좌를 먼저 연결하세요.")
        val mapping =
            corpMapping
                .split(',', '\n')
                .filter { it.isNotBlank() }
                .associate { entry ->
                    val parts = entry.trim().split(':')
                    require(
                        parts.size == 2 &&
                            parts[0].matches(Regex("[0-9]{6}")) &&
                            parts[1].matches(Regex("[0-9]{8}"))
                    ) {
                        "기업 매핑 형식: 종목코드:공시고유번호"
                    }
                    parts[0] to parts[1]
                }
        val evidence = mutableListOf<ResearchEvidence>()
        val candidates = mutableListOf<Candidate>()
        for (symbol in state.value.settings.symbols) {
            val item = repository.inspect(symbol, mapping[symbol], year, reportCode)
            evidence += item
            strategy.evaluate(symbol, item.prices!!.candles, LocalDate.now(SEOUL))?.let {
                candidates += it
            }
            change {
                it.copy(
                    research = evidence.toList(),
                    candidates = candidates.sortedByDescending { c -> c.score },
                )
            }
        }
        log("분석 완료 · 데이터 사용권한과 수정주가 검증 전 신규 매수는 차단됩니다.")
    }

    /** TradingService에서만 호출하는 세션 시작점. 매도 동의/매수 근거·미확인 주문을 검사한 뒤 반복 작업을 만든다. */
    fun startSession() {
        val s = state.value
        check(s.connected && !s.busy && !s.storageError && s.portfolio != null)
        check(
            s.settings.manageHoldings || s.research.any { it.buyBlockers(Instant.now()).isEmpty() }
        ) {
            "자동관리 동의 또는 매수 데이터 검증이 필요합니다."
        }
        check(!s.running)
        check(
            store.records().none {
                it.intent.brokerId == s.selected?.brokerId &&
                    it.intent.account == s.selected?.number &&
                    it.intent.environment == Environment.MOCK &&
                    it.status in setOf(OrderStatus.UNKNOWN, OrderStatus.SUBMITTING)
            }
        ) {
            "미확인 주문을 증권사에서 확인하세요."
        }
        engine!!.start(s.portfolio)
        change { it.copy(running = true) }
        log("모의 자동매매 시작 · 최대 6시간 세션")
        loop =
            scope.launch {
                val until = Instant.now().plusSeconds(6 * 3600)
                try {
                    while (isActive && state.value.running && Instant.now() < until) {
                        refreshInternal()
                        val current = state.value
                        val settings = current.settings
                        val portfolio = current.portfolio!!
                        if (settings.manageHoldings)
                            for (holding in portfolio.holdings) {
                                val quote = state.value.quotes[holding.symbol] ?: continue
                                val reason =
                                    engine!!.exitReason(holding, quote, settings) ?: continue
                                if (alreadyOrdered(holding.symbol, Side.SELL)) continue
                                engine!!.submit(
                                    current.selected!!,
                                    portfolio,
                                    quote,
                                    Side.SELL,
                                    reason,
                                    settings,
                                )
                                log("${holding.symbol} 매도 주문 접수 · 체결 확인 대기")
                            }
                        for (candidate in
                            current.candidates.filter { it.score >= settings.minScore }) {
                            val evidence =
                                current.research.find { it.symbol == candidate.symbol } ?: continue
                            if (
                                evidence.buyBlockers(Instant.now()).isNotEmpty() ||
                                    alreadyOrdered(candidate.symbol, Side.BUY)
                            )
                                continue
                            val quote = state.value.quotes[candidate.symbol] ?: continue
                            val proposedBudget = strategy.orderBudget(candidate, settings)
                            if (proposedBudget < 10_000) continue
                            val sized =
                                settings.copy(
                                    orderBudget = proposedBudget.coerceAtMost(settings.orderBudget)
                                )
                            engine!!.submit(
                                current.selected!!,
                                portfolio,
                                quote,
                                Side.BUY,
                                candidate.reason,
                                sized,
                            )
                            log("${candidate.symbol} 매수 주문 접수 · 체결 확인 대기")
                        }
                        delay(15_000)
                    }
                    stop("자동매매 세션 종료")
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    stop("안전 점검으로 자동매매 정지 · 잔고·시세·주문 내역을 확인하세요.")
                }
            }
        loop!!.invokeOnCompletion { scope.launch { change { it.copy(busy = false) } } }
    }

    /** 불필요한 반복 주문 시도를 줄이는 응용 계층 필터. 최종 원자적 중복 방지는 TradingEngine에서 다시 수행한다. */
    private fun alreadyOrdered(symbol: String, side: Side) =
        store.records().any {
            it.intent.brokerId == state.value.selected?.brokerId &&
                it.intent.account == state.value.selected?.number &&
                it.intent.symbol == symbol &&
                it.intent.side == side &&
                it.intent.at.atZone(SEOUL).toLocalDate() == LocalDate.now(SEOUL)
        }

    /** 엔진 플래그를 먼저 내리고 반복 작업을 취소한다. 완료되기 전에는 데이터 삭제/재시작을 막는다. 기존 증권사 주문 취소는 아니다. */
    fun stop(reason: String = "사용자 요청으로 자동매매 정지") {
        engine?.stop()
        val stopping = loop?.isCompleted == false
        loop?.cancel()
        change { it.copy(running = false, busy = it.busy || stopping) }
        log(reason)
    }

    /** 사용자 확인 후 로컬 데이터를 지운다. 진행 중인 네트워크 결과가 삭제한 저널을 재생성하지 않도록 작업 종료를 확인한다. */
    fun deleteAll() {
        if (loop?.isCompleted == false || state.value.busy) {
            log("진행 중인 요청이 끝날 때까지 기다리세요.")
            return
        }
        stop()
        socket?.close()
        scope.coroutineContext.cancelChildren()
        store.clear()
        broker = null
        engine = null
        researchRepository = null
        change { AppState(message = "기기 내 키와 기록을 삭제했습니다. 증권사 기록은 유지됩니다.") }
    }

    /** 화면 액션의 직렬 진입 경계. 실행 중에는 반복 작업만 조회를 소유한다. 중복 클릭을 막고 busy를 복구하며 취소는 호출자에게 전파한다. */
    private fun task(block: suspend () -> Unit) {
        if (
            state.value.running ||
                state.value.busy ||
                state.value.storageError ||
                (!state.value.running && loop?.isCompleted == false)
        )
            return
        scope.launch {
            change { it.copy(busy = true) }
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                log("요청 실패 · 네트워크·입력값·API 신청 상태를 확인하세요. 주문은 재시도하지 않습니다.", "ERROR")
            } finally {
                change { it.copy(busy = !it.running && loop?.isCompleted == false) }
            }
        }
    }
}

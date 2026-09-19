package com.sinpie.stocknhplug.application

import com.sinpie.stocknhplug.domain.*
import com.sinpie.stocknhplug.research.*
import com.sinpie.stocknhplug.trading.QuoteRouteStatus
import com.sinpie.stocknhplug.trading.TradingEngine
import java.time.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Compose에 노출되는 화면 상태. 키·토큰은 포함하지 않는다. connected는 초기 동기화와 구독 요청 완료이며 시세 신선도는 별도 검사한다. */
data class AppState(
    val settings: Strategy = Strategy(),
    val book: StrategyBook = StrategyBook.defaults(),
    val groupAlgorithms: Map<String, String> = emptyMap(),
    val groupPositions: Map<String, List<GroupPosition>> = emptyMap(),
    val groupExecutionReady: Boolean = false,
    val tracking: List<TargetStatus> = emptyList(),
    val quoteRoutes: List<QuoteRouteStatus> = emptyList(),
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
    private val executionGate: ExecutionGate,
    private val groupRegistry: GroupAlgorithmRegistry = GroupAlgorithmRegistry.defaults(),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var broker: Broker? = null
    private var socket: MarketStream? = null
    private var engine: TradingEngine? = null
    private var loop: Job? = null
    private var groups: GroupTradingCoordinator? = null
    private var monitor: HybridPriceMonitor? = null
    private var priceProvider: CurrentPriceProvider? = null
    private var researchRepository: ResearchRepository? = null
    private val mutable = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = mutable

    init {
        try {
            com.sinpie.stocknhplug.trading.GroupLedger.merge(
                store.records(),
                emptyList(),
                store.groupFills(),
            )
            mutable.value =
                mutable.value.copy(
                    settings = store.settings(),
                    book = store.strategyBook(),
                    groupAlgorithms = groupRegistry.names,
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
        groups = null
        monitor?.clear()
        monitor = null
        priceProvider = null
        executionGate.clear()
        change {
            it.copy(
                hasCredentials = true,
                connected = false,
                accounts = emptyList(),
                selected = null,
                portfolio = null,
                groupPositions = emptyMap(),
                groupExecutionReady = false,
                tracking = emptyList(),
                quoteRoutes = emptyList(),
                quotes = emptyMap(),
                executions = emptyList(),
                pnl = emptyList(),
                research = emptyList(),
                candidates = emptyList(),
            )
        }
        log("인증정보를 기기 내부에 암호화 저장했습니다.")
    }

    /** 그룹/전략 설정은 정지 상태에서만 변경한다. 기록이 있는 그룹의 소유권을 바꾸거나 삭제하지 않는다. */
    fun saveBook(book: StrategyBook) = task {
        book.validate()
        book.plans.forEach { groupRegistry.get(it.algorithmId) }
        val previous = state.value.book
        val recorded = store.records().map { it.intent.groupId }.filter { it.isNotBlank() }.toSet()
        check(
            ParkingPolicy.GROUP_ID !in recorded || book.parking.symbol == previous.parking.symbol
        ) {
            "파킹 거래 기록이 있으면 종목을 변경할 수 없습니다. 기존 원장을 보존하세요."
        }
        previous.groups
            .filter { it.id in recorded }
            .forEach { old ->
                val next =
                    book.groups.find { it.id == old.id } ?: error("거래 기록이 있는 그룹은 삭제 대신 정지하세요.")
                check(
                    next.strategyId == old.strategyId &&
                        next.symbols.map { it.symbol }.containsAll(old.symbols.map { it.symbol })
                )
                check(
                    book.plans.single { it.id == next.strategyId }.algorithmId ==
                        previous.plans.single { it.id == old.strategyId }.algorithmId
                )
            }
        store.saveStrategyBook(book)
        socket?.close()
        change {
            it.copy(
                book = book,
                connected = false,
                quotes = emptyMap(),
                research = emptyList(),
                candidates = emptyList(),
            )
        }
        log("전략·그룹 설정 저장 완료 · 계좌를 다시 연결하세요.")
    }

    /** 여러 그룹의 같은 종목은 한 번만 연구·구독한다. 주문 소유권은 그룹 ID로 따로 유지한다. */
    private fun configuredSymbols(): List<String> =
        state.value.book
            .ledgerGroups()
            .flatMap { it.symbols.map { row -> row.symbol } }
            .distinct()
            .ifEmpty { state.value.settings.symbols }

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
                groupPositions = emptyMap(),
                executions = emptyList(),
                pnl = emptyList(),
                quotes = emptyMap(),
            )
        }
        val session =
            sessionFactory.create(
                { quote -> scope.launch { monitor?.onWebsocket(quote) } },
                { msg -> scope.launch { log(msg) } },
                { scope.launch { log("시세 WebSocket 연결 끊김 · 검증된 REST 시세로 추적, 재연결 대기") } },
            )
        broker = session.broker
        check(session.broker.environment == Environment.MOCK) { "현재 배포 구성은 모의 거래만 허용합니다." }
        socket = session.stream
        priceProvider = session.currentPrices
        executionGate.clear()
        monitor?.clear()
        monitor =
            session.currentPrices?.let { prices ->
                HybridPriceMonitor(
                    prices,
                    session.stream,
                    executionGate,
                    { q -> change { it.copy(quotes = it.quotes + (q.symbol to q)) } },
                    { log(it) },
                )
            }
        researchRepository = session.research
        engine = TradingEngine(session.broker, store, strategy)
        groups =
            GroupTradingCoordinator(
                store,
                session.groupExecutions,
                groupRegistry,
                engine!!,
                executionGate,
            )
        change { it.copy(groupExecutionReady = session.groupExecutions != null) }
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
                groupPositions = emptyMap(),
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
            (state.value.portfolio!!.holdings.map { it.symbol } + configuredSymbols()).distinct()
        check(symbols.size <= 100) { "현재 가격 추적은 고유 종목 100개까지 지원합니다." }
        executionGate.clear()
        monitor?.clear()
        socket!!.connect(emptyList())
        monitor?.step(symbols.toSet())
    }

    /** 선택 계좌의 잔고·체결을 모두 조회한 후 스냅샷을 저장한다. 일부 응답만 성공한 상태를 완성된 화면으로 게시하지 않는다. */
    private suspend fun refreshInternal() {
        val account = state.value.selected ?: error("계좌를 먼저 연결하세요.")
        val portfolio = broker!!.portfolio(account)
        val executions = broker!!.executions(account, LocalDate.now(SEOUL))
        groups?.reconcile(account)
        val groupPositions =
            state.value.book.ledgerGroups().associate {
                it.id to
                    com.sinpie.stocknhplug.trading.GroupLedger.positions(
                        it,
                        account,
                        store.records(),
                        store.groupFills(),
                    )
            }
        withContext(Dispatchers.IO) { store.snapshot(account, Environment.MOCK, portfolio) }
        change {
            it.copy(
                portfolio = portfolio,
                groupPositions = groupPositions,
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
        for (symbol in configuredSymbols()) {
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
        check(monitor != null && priceProvider != null) { "검증된 REST 시세 제공자가 필요합니다." }
        check(s.groupExecutionReady) { "NHPlug 그룹별 체결 대사 검증 전 자동주문은 잠겨 있습니다." }
        val enabled =
            s.book.groups.filter {
                it.enabled && s.book.plans.any { p -> p.id == it.strategyId && p.enabled }
            }
        check(enabled.isNotEmpty() || s.book.parking.enabled) { "실행할 전략그룹 또는 파킹을 켜세요." }
        check(!s.book.parking.enabled || s.settings.manageHoldings) { "파킹 실행에는 자동매도 동의가 필요합니다." }
        check(enabled.sumOf { it.capital } <= s.portfolio.equity) { "그룹 자본 합계가 계좌 순자산보다 큽니다." }
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
        log("모의 자동매매 시작 · 사용자가 정지할 때까지 백그라운드 감시")
        loop =
            scope.launch {
                try {
                    var nextBalance = 0L
                    // 서비스가 수명을 소유한다. 화면 종료나 임의의 시간 제한으로 매매를 끊지 않는다.
                    // OS 종료·네트워크/원장 오류는 여전히 안전 정지하며 주문을 자동 재전송하지 않는다.
                    while (isActive && state.value.running) {
                        val nanos = System.nanoTime()
                        if (nanos >= nextBalance) {
                            refreshInternal()
                            nextBalance = System.nanoTime() + 15_000_000_000L
                        }
                        val watched =
                            (configuredSymbols() +
                                    state.value.portfolio!!.holdings.map { it.symbol })
                                .toSet()
                        check(watched.size <= 100)
                        monitor!!.step(watched)
                        val current = state.value
                        val result =
                            if (!trackingSession(Instant.now())) null
                            else
                                groups!!.tick(
                                    current.selected!!,
                                    current.portfolio!!,
                                    current.quotes,
                                    current.research,
                                    current.settings,
                                    Instant.now(),
                                )
                        if (result != null) log("${result.intent.reason} · 주문 접수, 체결 대사 대기")
                        if (result != null) nextBalance = 0L
                        change {
                            it.copy(
                                tracking = executionGate.targets(),
                                quoteRoutes = monitor!!.statuses(),
                            )
                        }
                        delay(1_000)
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

    /** 엔진 플래그를 먼저 내리고 반복 작업을 취소한다. 완료되기 전에는 데이터 삭제/재시작을 막는다. 기존 증권사 주문 취소는 아니다. */
    fun stop(reason: String = "사용자 요청으로 자동매매 정지") {
        engine?.stop()
        val stopping = loop?.isCompleted == false
        loop?.cancel()
        socket?.close()
        monitor?.clear()
        executionGate.clear()
        change {
            it.copy(
                running = false,
                connected = false,
                quotes = emptyMap(),
                tracking = emptyList(),
                quoteRoutes = emptyList(),
                busy = it.busy || stopping,
            )
        }
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
        groups = null
        monitor = null
        priceProvider = null
        change {
            AppState(
                groupAlgorithms = groupRegistry.names,
                message = "기기 내 키와 기록을 삭제했습니다. 증권사 기록은 유지됩니다.",
            )
        }
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

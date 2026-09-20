package com.sinpie.stocknhplug.application

import com.sinpie.stocknhplug.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** 화면 선택은 관찰 대상만 바꾼다. 각 runtime은 자체 Job/엔진/원장/시세/전략/저장소를 소유한다. */
class MultiAccountController(
    private val discovery: AccountRuntime,
    private val directory: AccountDirectory,
    private val factory: AccountRuntimeFactory,
    private val legacySettings: () -> Pair<StrategyBook, Strategy>,
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) : TradingWorkspace {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val runtimes = linkedMapOf<Account, AccountRuntime>()
    private val observers = linkedMapOf<Account, Job>()
    private var profiles = emptyList<AccountProfile>()
    private var selected: Account? = null
    private var discovering = false
    private var notice: String? = null
    private var failed = false
    private val mutable = MutableStateFlow(AppState())
    override val state: StateFlow<AppState> = mutable

    init {
        try {
            profiles = directory.profiles()
            profiles.forEach { add(it.account) }
            selected = profiles.firstOrNull()?.account
        } catch (_: Exception) {
            failed = true
            notice = "계좌 저장소 오류 · 운용 잠금"
        }
        scope.launch {
            discovery.state.collect { s ->
                if (discovering && !s.busy) {
                    discovering = false
                    if (!s.messageError && s.accounts.isNotEmpty()) {
                        try {
                            val known = profiles.map { it.account }.toSet()
                            val next =
                                profiles +
                                    s.accounts.filter { it !in known }.map { AccountProfile(it) }
                            directory.save(next)
                            next.forEach { add(it.account) }
                            profiles = next
                            if (selected == null) selected = s.accounts.first()
                            notice = "계좌 목록 갱신 · 계좌별 연결 후 자동운용을 켜세요."
                        } catch (_: Exception) {
                            failed = true
                            notice = "계좌 등록 실패 · 운용 잠금"
                        }
                    } else notice = s.message
                }
                publish()
            }
        }
        publish()
    }

    private fun add(account: Account) {
        if (account in runtimes) return
        val runtime = factory.create(account)
        runtimes[account] = runtime
        observers[account] = scope.launch { runtime.state.collect { publish() } }
    }

    private fun current() = selected?.let { runtimes[it] }

    override fun accountWorkspace(account: Account?): TradingWorkspace = runtimes[account] ?: this

    private fun idle() =
        !discovering &&
            !discovery.state.value.busy &&
            runtimes.values.none { it.state.value.let { s -> s.running || s.busy } }

    private fun publish() {
        val base =
            current()?.state?.value
                ?: discovery.state.value.copy(
                    settings = Strategy(),
                    book = StrategyBook.defaults(),
                    orders = emptyList(),
                    snapshots = emptyList(),
                    executions = emptyList(),
                    pnl = emptyList(),
                    history = emptyList(),
                    portfolio = null,
                    selected = null,
                )
        val runs =
            profiles.map { p ->
                val s = runtimes[p.account]?.state?.value ?: AppState()
                AccountRun(p.account, p.enabled, s.running, s.connected, s.busy, s.message)
            }
        val enabled = profiles.filter { it.enabled }
        val ready =
            !failed &&
                !discovery.state.value.storageError &&
                !discovering &&
                !discovery.state.value.busy &&
                enabled.isNotEmpty() &&
                enabled.all { p ->
                    val r = runtimes[p.account]
                    r != null &&
                        (r.state.value.running || runCatching { r.validateStart() }.isSuccess)
                }
        mutable.value =
            base.copy(
                fleetCanStart = ready,
                accountRequired = selected == null,
                accounts = profiles.map { it.account },
                selected = selected,
                accountRuns = runs,
                fleetRunning = runs.any { it.running },
                fleetBusy = discovering || discovery.state.value.busy || runs.any { it.busy },
                hasCredentials = discovery.state.value.hasCredentials,
                storageError = base.storageError || failed || discovery.state.value.storageError,
                message = notice ?: base.message,
                busy = base.busy || discovering || discovery.state.value.busy,
                operation =
                    if (discovery.state.value.busy) discovery.state.value.operation
                    else base.operation,
            )
    }

    private fun selectedAction(action: (AccountRuntime) -> Unit) {
        if (failed || discovery.state.value.storageError) return
        notice = null
        val runtime = current()
        if (runtime == null) notice = "설정에서 계좌 목록을 조회하고 관리할 계좌를 선택하세요." else action(runtime)
        publish()
    }

    override fun select(account: Account) {
        if (account !in runtimes) return
        // 기존 계좌의 실행/요청을 취소하지 않는다. 새 화면은 별도 상태 객체를 관찰한다.
        selected = account
        notice = null
        publish()
    }

    override fun connect() =
        if (selected == null) discoverAccounts() else selectedAction { it.connect() }

    override fun discoverAccounts() {
        if (!idle() || failed) {
            notice = "계좌 목록 갱신은 모든 계좌 정지 후 가능합니다."
            publish()
            return
        }
        notice = null
        discovering = true
        discovery.connect()
        publish()
    }

    override fun enable(account: Account, enabled: Boolean) {
        if (failed || profiles.none { it.account == account } || discovering) return
        try {
            val next =
                profiles.map { if (it.account == account) it.copy(enabled = enabled) else it }
            directory.save(next)
            profiles = next
            if (!enabled) runtimes[account]?.stop("계좌 자동운용 해제")
            notice = "자동운용 대상 저장 · 켜기는 즉시 주문을 시작하지 않습니다."
        } catch (_: Exception) {
            notice = "자동운용 대상 저장 실패"
        }
        publish()
    }

    override fun startSession() {
        // 서비스 진입점도 UI와 동일하게 공유 자격증명 저장소 오류를 거절해야 한다.
        check(
            !failed &&
                !discovering &&
                !discovery.state.value.busy &&
                !discovery.state.value.storageError
        )
        val targets = profiles.filter { it.enabled }.map { runtimes.getValue(it.account) }
        check(targets.isNotEmpty()) { "자동운용 계좌를 켜세요." }
        val pending = targets.filter { !it.state.value.running }
        // 하나라도 준비되지 않으면 새 계좌는 전혀 시작하지 않는다. 모든 공통 게이트를 사전 검사한다.
        pending.forEach { it.validateStart() }
        try {
            pending.forEach { it.startSession() }
        } catch (e: Exception) {
            pending.forEach { it.stop("동시 시작 실패 · 계좌 점검 필요") }
            throw e
        }
        notice = null
        publish()
    }

    override fun stop(reason: String) {
        discovering = false
        discovery.stop(reason)
        runtimes.values.forEach { it.stop(reason) }
        notice = reason
        publish()
    }

    override fun cancelRequest() {
        if (discovering) {
            discovering = false
            discovery.cancelRequest()
        } else current()?.cancelRequest()
        notice = null
        publish()
    }

    override fun saveCredentials(key: String, secret: String, dart: String) {
        if (!idle()) {
            notice = "키 변경은 모든 계좌의 실행·요청 종료 후 가능합니다."
            publish()
            return
        }
        try {
            // 공유 키 교체 시 모든 계좌의 자동운용 동의를 해제하고 세션을 폐기한다. 저장 이력은 유지한다.
            val next = profiles.map { it.copy(enabled = false) }
            directory.save(next)
            profiles = next
            observers.values.forEach { it.cancel() }
            observers.clear()
            runtimes.values.forEach { it.dispose() }
            runtimes.clear()
            discovery.saveCredentials(key, secret, dart)
            profiles.forEach { add(it.account) }
            notice = "키 저장 요청 · 계좌 목록과 각 계좌 연결을 다시 확인하세요."
        } catch (_: Exception) {
            failed = true
            notice = "키 변경 처리 실패 · 운용 잠금"
        }
        publish()
    }

    override fun deleteAll() {
        if (!idle()) {
            notice = "모든 계좌의 요청이 끝난 뒤 삭제하세요."
            publish()
            return
        }
        observers.values.forEach { it.cancel() }
        observers.clear()
        runtimes.values.forEach { it.dispose() }
        runtimes.clear()
        try {
            discovery.deleteAll()
        } catch (_: Exception) {
            failed = true
            notice = "전체 삭제 실패 · 운용 잠금"
            publish()
            return
        }
        profiles = emptyList()
        selected = null
        failed = discovery.state.value.storageError
        notice = null
        publish()
    }

    override fun importLegacySettings() = selectedAction { runtime ->
        if (runtime.state.value.running || runtime.state.value.busy) return@selectedAction
        try {
            val (book, _) = legacySettings()
            runtime.saveBook(book)
        } catch (_: Exception) {
            notice = "기존 설정 가져오기 실패 · 저장소를 확인하세요."
        }
    }

    override fun saveBook(book: StrategyBook) = selectedAction { it.saveBook(book) }

    override fun saveSettings(settings: Strategy) = selectedAction { it.saveSettings(settings) }

    override fun refresh() = selectedAction { it.refresh() }

    override fun refreshPrice(symbol: String) = selectedAction { it.refreshPrice(symbol) }

    override fun refreshPnl() = selectedAction { it.refreshPnl() }

    override fun analyze(corpMapping: String, year: Int, reportCode: String) = selectedAction {
        it.analyze(corpMapping, year, reportCode)
    }
}

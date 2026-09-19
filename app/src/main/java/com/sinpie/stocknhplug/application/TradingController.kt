package com.sinpie.stocknhplug.application

import android.content.Context
import com.sinpie.stocknhplug.data.*
import com.sinpie.stocknhplug.domain.*
import com.sinpie.stocknhplug.execution.*
import com.sinpie.stocknhplug.research.*
import com.sinpie.stocknhplug.trading.TradingEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.*

data class AppState(val settings: Strategy=Strategy(), val accounts: List<Account> = emptyList(), val selected: Account?=null,
    val portfolio: Portfolio?=null, val candidates: List<Candidate> = emptyList(), val research: List<ResearchEvidence> = emptyList(),
    val quotes: Map<String,Quote> = emptyMap(), val orders: List<OrderRecord> = emptyList(), val executions: List<Execution> = emptyList(),
    val events: List<Event> = emptyList(), val snapshots: List<HoldingSnapshot> = emptyList(), val pnl: List<DailyPnl> = emptyList(), val busy: Boolean=false, val running: Boolean=false, val connected: Boolean=false,
    val message: String="NHPlug 모의계좌를 연결해 시작하세요.", val hasCredentials: Boolean=false, val storageError: Boolean=false)

/** Application layer owns orchestration, never endpoint fields or order price math. */
class TradingController(context: Context) {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    val vault=SecureVault(context)
    private var store: LocalStore?=null
    private var broker: NhBroker?=null
    private var socket: NhSocket?=null
    private var engine: TradingEngine?=null
    private var loop: Job?=null
    private val mutable=MutableStateFlow(AppState())
    val state: StateFlow<AppState> = mutable
    init {
        try { store=LocalStore(vault); mutable.value=mutable.value.copy(settings=store!!.settings(),orders=store!!.records(),events=store!!.events.toList(),snapshots=store!!.snapshots(),hasCredentials=vault.read("credentials")!=null) }
        catch (_:Exception) { mutable.value=mutable.value.copy(storageError=true,message="보안 저장소를 열 수 없습니다. 자동매매를 잠갔습니다. 삭제 전 증권사 주문을 확인하세요.") }
    }
    private fun change(block:(AppState)->AppState) { mutable.value=block(mutable.value) }
    private fun log(message:String, level:String="INFO") {
        try { store?.log(level,message); change { it.copy(events=store?.events?.toList().orEmpty(),orders=store?.records().orEmpty(),message=message) } }
        catch (_:Exception) { engine?.stop(); change { it.copy(running=false,storageError=true,message="기록 저장 실패 · 자동매매 잠금") } }
    }
    fun saveCredentials(key:String, secret:String, dart:String) = task {
        check(!state.value.running); require(key.isNotBlank() && secret.isNotBlank())
        vault.write("credentials",json("key" to key.trim(),"secret" to secret.trim(),"dart" to dart.trim())); vault.delete("token")
        socket?.close(); change { it.copy(hasCredentials=true,connected=false,accounts=emptyList(),selected=null,portfolio=null,quotes=emptyMap()) }
        log("인증정보를 기기 내부에 암호화 저장했습니다.")
    }
    fun saveSettings(settings:Strategy) = task {
        check(!state.value.running); store!!.saveSettings(settings); socket?.close()
        change { it.copy(settings=settings,candidates=emptyList(),research=emptyList(),connected=false,quotes=emptyMap()) }; log("전략 설정 저장 완료 · 변경된 관심종목을 적용하려면 계좌를 다시 연결하세요.")
    }
    fun connect()=task {
        check(!state.value.running && !state.value.storageError)
        socket?.close()
        change { it.copy(connected=false,selected=null,portfolio=null,executions=emptyList(),pnl=emptyList(),quotes=emptyMap()) }
        val transport=NhTransport(vault,Environment.MOCK)
        broker=NhBroker(transport); engine=TradingEngine(broker!!,store!!)
        val accounts=broker!!.accounts(); check(accounts.isNotEmpty()) { "모의투자 계좌가 없습니다. NHPlug 신청 상태를 확인하세요." }
        change { it.copy(accounts=accounts,selected=accounts.first(),connected=true,quotes=emptyMap()) }
        socket=NhSocket(transport,{ quote -> scope.launch { change { it.copy(quotes=it.quotes + (quote.symbol to quote)) } } },{ msg->scope.launch { log(msg) } },{
            scope.launch { stop("실시간 연결 끊김 · 주문 정지"); change { it.copy(connected=false,quotes=emptyMap()) } }
        })
        refreshInternal()
        val symbols=(state.value.settings.symbols+state.value.portfolio!!.holdings.map { it.symbol }).distinct().take(10)
        socket!!.connect(symbols); log("NHPlug 모의투자 연결 완료 · ${accounts.size}개 계좌")
    }
    fun select(account:Account)=task {
        check(!state.value.running); change { it.copy(selected=account,portfolio=null,executions=emptyList(),pnl=emptyList()) }; refreshInternal()
    }
    private suspend fun refreshInternal() {
        val account=state.value.selected ?: error("계좌를 먼저 연결하세요.")
        val portfolio=broker!!.portfolio(account)
        val executions=broker!!.executions(account,LocalDate.now(SEOUL))
        withContext(Dispatchers.IO) { store!!.snapshot(account,Environment.MOCK,portfolio) }
        change { it.copy(portfolio=portfolio,executions=executions,orders=store!!.records(),snapshots=store!!.snapshots()) }
    }
    fun refresh()=task { refreshInternal(); log("잔고·주문체결 동기화 완료") }
    fun refreshPnl()=task { val account=state.value.selected ?: error("계좌 연결 필요"); val items=broker!!.dailyPnl(account); change { it.copy(pnl=items) }; log("최근 30일 증권사 손익 조회 완료") }
    fun analyze(corpMapping:String, year:Int, reportCode:String)=task {
        check(!state.value.running); val b=broker ?: error("계좌를 먼저 연결하세요.")
        val mapping=corpMapping.split(',', '\n').filter { it.isNotBlank() }.associate { entry ->
            val parts=entry.trim().split(':'); require(parts.size==2 && parts[0].matches(Regex("[0-9]{6}")) && parts[1].matches(Regex("[0-9]{8}"))) { "기업 매핑 형식: 종목코드:공시고유번호" }; parts[0] to parts[1]
        }
        val dartKey=vault.read("credentials")?.optString("dart").orEmpty()
        val repository=ResearchRepository(b,if(dartKey.isNotBlank()) DartClient(dartKey) else null)
        val evidence=mutableListOf<ResearchEvidence>(); val candidates=mutableListOf<Candidate>()
        for(symbol in state.value.settings.symbols) {
            val item=repository.inspect(symbol,mapping[symbol],year,reportCode); evidence+=item
            SignalEngine.evaluate(symbol,item.prices!!.candles,LocalDate.now(SEOUL))?.let { candidates+=it }
            change { it.copy(research=evidence.toList(),candidates=candidates.sortedByDescending { c->c.score }) }
        }
        log("분석 완료 · 데이터 사용권한과 수정주가 검증 전 신규 매수는 차단됩니다.")
    }
    fun startSession() {
        val s=state.value
        check(s.connected && !s.busy && !s.storageError && s.portfolio!=null)
        check(s.settings.manageHoldings || s.research.any { it.buyBlockers(Instant.now()).isEmpty() }) { "자동관리 동의 또는 매수 데이터 검증이 필요합니다." }
        check(!s.running)
        engine!!.start(s.portfolio); change { it.copy(running=true) }; log("모의 자동매매 시작 · 최대 6시간 세션")
        loop=scope.launch {
            val until=Instant.now().plusSeconds(6*3600)
            try {
                while(isActive && state.value.running && Instant.now()<until) {
                    refreshInternal()
                    val current=state.value; val settings=current.settings; val portfolio=current.portfolio!!
                    if(settings.manageHoldings) for(holding in portfolio.holdings) {
                        val quote=state.value.quotes[holding.symbol] ?: continue
                        val reason=engine!!.exitReason(holding,quote,settings) ?: continue
                        if(alreadyOrdered(holding.symbol,Side.SELL)) continue
                        engine!!.submit(current.selected!!,portfolio,quote,Side.SELL,reason,settings); log("${holding.symbol} 매도 주문 접수 · 체결 확인 대기")
                    }
                    for(candidate in current.candidates.filter { it.score>=settings.minScore }) {
                        val evidence=current.research.find { it.symbol==candidate.symbol } ?: continue
                        if(evidence.buyBlockers(Instant.now()).isNotEmpty() || alreadyOrdered(candidate.symbol,Side.BUY)) continue
                        val quote=state.value.quotes[candidate.symbol] ?: continue
                        // Volatility reduces capital exposure rather than claiming predictive accuracy.
                        val factor=(2.0/candidate.atrPercent.coerceAtLeast(2.0)).coerceAtMost(1.0)
                        val sized=settings.copy(orderBudget=(settings.orderBudget*factor).toLong().coerceAtLeast(10_000))
                        engine!!.submit(current.selected!!,portfolio,quote,Side.BUY,candidate.reason,sized); log("${candidate.symbol} 매수 주문 접수 · 체결 확인 대기")
                    }
                    delay(15_000)
                }
                stop("자동매매 세션 종료")
            } catch (e:CancellationException) { throw e }
            catch (_:Exception) { stop("안전 점검으로 자동매매 정지 · 잔고·시세·주문 내역을 확인하세요.") }
        }
        loop!!.invokeOnCompletion { scope.launch { change { it.copy(busy=false) } } }
    }
    private fun alreadyOrdered(symbol:String,side:Side)=store!!.records().any { it.intent.account==state.value.selected?.number && it.intent.symbol==symbol && it.intent.side==side && it.intent.at.atZone(SEOUL).toLocalDate()==LocalDate.now(SEOUL) }
    fun stop(reason:String="사용자 요청으로 자동매매 정지") {
        engine?.stop(); val stopping=loop?.isCompleted==false; loop?.cancel()
        change { it.copy(running=false,busy=it.busy || stopping) }; log(reason)
    }
    fun deleteAll() {
        if(loop?.isCompleted==false || state.value.busy) { log("진행 중인 요청이 끝날 때까지 기다리세요."); return }
        stop(); socket?.close(); scope.coroutineContext.cancelChildren()
        vault.deleteAll(); store=LocalStore(vault); broker=null; engine=null
        change { AppState(message="기기 내 키와 기록을 삭제했습니다. 증권사 기록은 유지됩니다.") }
    }
    private fun task(block:suspend ()->Unit) {
        if(state.value.busy || state.value.storageError || (!state.value.running && loop?.isCompleted==false)) return
        scope.launch {
            change { it.copy(busy=true) }
            try { block() } catch (e:CancellationException) { throw e }
            catch (_:Exception) { log("요청 실패 · 네트워크·입력값·API 신청 상태를 확인하세요. 주문은 재시도하지 않습니다.","ERROR") }
            finally { change { it.copy(busy=!it.running && loop?.isCompleted==false) } }
        }
    }
    companion object {
        @Volatile private var instance:TradingController?=null
        fun get(context:Context):TradingController=instance ?: synchronized(this) { instance ?: TradingController(context.applicationContext).also { instance=it } }
    }
}

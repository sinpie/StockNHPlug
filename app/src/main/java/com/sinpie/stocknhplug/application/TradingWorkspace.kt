package com.sinpie.stocknhplug.application

import com.sinpie.stocknhplug.domain.*
import kotlinx.coroutines.flow.StateFlow

/** UI와 서비스의 명령 포트. 화면 선택과 실행 수명을 분리한다. */
interface TradingWorkspace {
    val state: StateFlow<AppState>

    /** 화면이 만들어진 계좌에 명령을 고정한다. 전환 직전의 지연 UI 이벤트를 새 계좌로 보내지 않는다. */
    fun accountWorkspace(account: Account?): TradingWorkspace = this

    fun saveCredentials(key: String, secret: String, dart: String)

    fun saveBook(book: StrategyBook)

    fun saveSettings(settings: Strategy)

    fun connect()

    fun discoverAccounts() = connect()

    fun select(account: Account)

    fun refresh()

    fun refreshPrice(symbol: String)

    fun refreshPnl()

    fun analyze(corpMapping: String, year: Int, reportCode: String)

    fun startSession()

    fun stop(reason: String = "사용자 요청으로 자동매매 정지")

    fun cancelRequest()

    fun deleteAll()

    fun enable(account: Account, enabled: Boolean) {}

    fun importLegacySettings() {}
}

/** 계좌 고정 실행 포트. 검증을 모두 마친 뒤에만 startSession이 호출된다. */
interface AccountRuntime : TradingWorkspace {
    fun validateStart()

    fun dispose()
}

data class AccountRun(
    val account: Account,
    val enabled: Boolean,
    val running: Boolean,
    val connected: Boolean,
    val busy: Boolean,
    val message: String,
)

fun interface AccountRuntimeFactory {
    fun create(account: Account): AccountRuntime
}

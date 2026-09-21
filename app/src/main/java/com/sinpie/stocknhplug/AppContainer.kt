package com.sinpie.stocknhplug

import android.content.Context
import com.sinpie.stocknhplug.application.*
import com.sinpie.stocknhplug.data.*
import com.sinpie.stocknhplug.domain.Environment
import com.sinpie.stocknhplug.execution.*
import com.sinpie.stocknhplug.infrastructure.nh.NhTransport
import com.sinpie.stocknhplug.marketdata.NhCurrentPriceProvider
import com.sinpie.stocknhplug.marketdata.NhPriceHistoryProvider
import com.sinpie.stocknhplug.research.*
import com.sinpie.stocknhplug.research.provider.DartClient
import com.sinpie.stocknhplug.research.provider.DartCompanyDirectory
import com.sinpie.stocknhplug.trading.NamuExecutionGate

/**
 * 의존성 조립의 유일한 진입점. Activity/Service는 같은 controller를 공유한다. 제공자 교체는 이곳에서 수행하며 화면·매매 규칙에 HTTP나 키 접근을
 * 추가하지 않는다.
 */
class AppContainer private constructor(context: Context) {
    private val vault = SecureVault(context.applicationContext)
    // 하나의 인증키가 발급한 토큰/REST 제한을 공유한다. 엔진과 계좌 데이터는 공유하지 않는다.
    private val transport = NhTransport(vault, Environment.MOCK)
    private val quotes =
        SharedMarketStream({ quote, event, disconnect ->
            NhSocket(transport, quote, event, disconnect)
        })
    private val directory = DartCompanyDirectory {
        vault.read("credentials")?.optString("dart").orEmpty()
    }

    private val sessions = SessionFactory { onQuote, onEvent, onDisconnect ->
        val dartKey = vault.read("credentials")?.optString("dart").orEmpty()
        BrokerSession(
            NhBroker(transport),
            quotes.lease(onQuote, onEvent, onDisconnect),
            ResearchRepository(
                NhPriceHistoryProvider(transport),
                if (dartKey.isBlank()) null else DartClient(dartKey),
                UnlicensedNewsProvider(),
                directory = directory,
            ),
            currentPrices = NhCurrentPriceProvider(transport),
        )
    }
    private val discovery =
        TradingController(
            EncryptedAppStorage(vault),
            sessions,
            TechnicalStrategy(),
            NamuExecutionGate(EncryptedTrackingStore(vault)),
            GroupAlgorithmRegistry.defaults(),
            discoveryOnly = true,
        )
    val controller: TradingWorkspace =
        MultiAccountController(
            discovery,
            EncryptedAccountDirectory(vault),
            AccountRuntimeFactory { account ->
                val scoped = SecureVault(context.applicationContext, account)
                AccountMigration.migrate(vault, scoped, account)
                TradingController(
                    EncryptedAppStorage(scoped, vault, account),
                    sessions,
                    TechnicalStrategy(),
                    NamuExecutionGate(EncryptedTrackingStore(scoped)),
                    GroupAlgorithmRegistry.defaults(),
                    boundAccount = account,
                    historyStore = EncryptedAccountHistory(scoped),
                )
            },
            { EncryptedAppStorage(vault).let { it.strategyBook() to it.settings() } },
        )

    companion object {
        @Volatile private var instance: AppContainer? = null

        /** applicationContext만 보유한다. Activity 수명에 묶인 객체를 싱글턴에 넣지 않는다. */
        fun get(context: Context): AppContainer =
            instance
                ?: synchronized(this) {
                    instance ?: AppContainer(context.applicationContext).also { instance = it }
                }
    }
}

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
import com.sinpie.stocknhplug.trading.NamuExecutionGate

/**
 * 의존성 조립의 유일한 진입점. Activity/Service는 같은 controller를 공유한다. 제공자 교체는 이곳에서 수행하며 화면·매매 규칙에 HTTP나 키 접근을
 * 추가하지 않는다.
 */
class AppContainer private constructor(context: Context) {
    private val vault = SecureVault(context.applicationContext)
    val controller =
        TradingController(
            EncryptedAppStorage(vault),
            SessionFactory { onQuote, onEvent, onDisconnect ->
                val transport = NhTransport(vault, Environment.MOCK)
                val dartKey = vault.read("credentials")?.optString("dart").orEmpty()
                BrokerSession(
                    NhBroker(transport),
                    NhSocket(transport, onQuote, onEvent, onDisconnect),
                    ResearchRepository(
                        NhPriceHistoryProvider(transport),
                        if (dartKey.isBlank()) null else DartClient(dartKey),
                        UnlicensedNewsProvider(),
                    ),
                    currentPrices = NhCurrentPriceProvider(transport),
                )
            },
            TechnicalStrategy(),
            NamuExecutionGate(),
            GroupAlgorithmRegistry.defaults(),
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

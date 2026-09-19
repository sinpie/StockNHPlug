package com.sinpie.stocknhplug

import com.sinpie.stocknhplug.domain.*
import com.sinpie.stocknhplug.trading.TradingEngine
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class TradingEngineTest {
    private val now=Instant.parse("2026-09-21T01:00:00Z")
    private val account=Account("12345678901","03")
    private val portfolio=Portfolio(1_000_000,1_000_000,0,emptyList(),now)
    private val quote=Quote("005930",10_000,10_000,10_010,now,now,true)
    private class Journal:OrderJournal {
        val list=mutableListOf<OrderRecord>()
        override fun reserve(intent:OrderIntent):Boolean {list+=OrderRecord(intent,OrderStatus.SUBMITTING);return true}
        override fun update(id:String,status:OrderStatus,brokerNumber:String) {val i=list.indexOfFirst {it.intent.id==id};list[i]=list[i].copy(status=status,brokerNumber=brokerNumber)}
        override fun records()=list.toList()
    }
    private class FakeBroker:Broker {
        override val environment=Environment.MOCK
        var sent=0;var fail=false;var beforeSend:()->Unit={}
        override suspend fun accounts()=emptyList<Account>()
        override suspend fun portfolio(account:Account):Portfolio=error("unused")
        override suspend fun candles(symbol:String)=emptyList<Candle>()
        override suspend fun available(account:Account,symbol:String,side:Side,price:Long)=100L
        override suspend fun place(account:Account,intent:OrderIntent):String {beforeSend();sent++;if(fail)error("timeout");return "123"}
        override suspend fun executions(account:Account,date:LocalDate)=emptyList<Execution>()
    }
    @Test fun durableIntentExistsBeforeNetwork()=runBlocking {
        val b=FakeBroker();val j=Journal();val e=TradingEngine(b,j){now};e.start(portfolio)
        b.beforeSend={assertEquals(OrderStatus.SUBMITTING,j.list.single().status)}
        val result=e.submit(account,portfolio,quote,Side.BUY,"test",Strategy())
        assertEquals(9L,result.intent.quantity);assertEquals(OrderStatus.ACCEPTED,result.status)
    }
    @Test fun timeoutStopsAndNeverRetries()=runBlocking {
        val b=FakeBroker().apply {fail=true};val j=Journal();val e=TradingEngine(b,j){now};e.start(portfolio)
        assertTrue(runCatching {e.submit(account,portfolio,quote,Side.BUY,"test",Strategy())}.isFailure)
        assertFalse(e.running);assertEquals(1,b.sent);assertEquals(OrderStatus.UNKNOWN,j.list.single().status)
        e.start(portfolio)
        assertTrue(runCatching {e.submit(account,portfolio,quote.copy(symbol="000660"),Side.BUY,"test",Strategy())}.isFailure)
        assertEquals(1,b.sent)
    }
    @Test fun concurrentSignalsProduceOnlyOneOrder()=runBlocking {
        val b=FakeBroker();val j=Journal();val e=TradingEngine(b,j){now};e.start(portfolio)
        coroutineScope {(1..10).map {async {runCatching {e.submit(account,portfolio,quote,Side.BUY,"test",Strategy())}}}.awaitAll()}
        assertEquals(1,b.sent)
    }
    @Test fun staleOrFutureQuotesNeverTrade()=runBlocking {
        for(q in listOf(quote.copy(exchangeAt=now.minusSeconds(20)),quote.copy(receivedAt=now.plusSeconds(3)),quote.copy(exchangeAt=now.plusSeconds(3)))) {
            val b=FakeBroker();val e=TradingEngine(b,Journal()){now};e.start(portfolio)
            assertTrue(runCatching {e.submit(account,portfolio,q,Side.BUY,"test",Strategy())}.isFailure);assertEquals(0,b.sent)
        }
    }
    @Test fun wrongEnvironmentNeverTrades()=runBlocking {
        val b=FakeBroker();val e=TradingEngine(b,Journal()){now};e.start(portfolio)
        assertTrue(runCatching {e.submit(account.copy(type="01"),portfolio,quote,Side.BUY,"test",Strategy())}.isFailure);assertEquals(0,b.sent)
    }
    @Test fun weekendNeverTrades()=runBlocking {
        val b=FakeBroker();val e=TradingEngine(b,Journal()){Instant.parse("2026-09-19T01:00:00Z")};e.start(portfolio)
        assertTrue(runCatching {e.submit(account,portfolio,quote,Side.BUY,"test",Strategy())}.isFailure);assertEquals(0,b.sent)
    }
    @Test fun lossLimitStopsSession()=runBlocking {
        val b=FakeBroker();val e=TradingEngine(b,Journal()){now};e.start(portfolio)
        assertTrue(runCatching {e.submit(account,portfolio.copy(equity=970000),quote,Side.BUY,"test",Strategy())}.isFailure);assertFalse(e.running);assertEquals(0,b.sent)
    }
    @Test fun pendingBuysCountAgainstPositionLimit()=runBlocking {
        val b=FakeBroker();val j=Journal();val e=TradingEngine(b,j){now};e.start(portfolio)
        e.submit(account,portfolio,quote,Side.BUY,"test",Strategy(maxPositions=1))
        assertTrue(runCatching {e.submit(account,portfolio,quote.copy(symbol="000660"),Side.BUY,"test",Strategy(maxPositions=1))}.isFailure);assertEquals(1,b.sent)
    }
    @Test fun sellNeedsExplicitHoldingsConsent()=runBlocking {
        val b=FakeBroker();val e=TradingEngine(b,Journal()){now};e.start(portfolio)
        val p=portfolio.copy(holdings=listOf(Holding("005930","name",10,10000,10000,0)))
        assertTrue(runCatching {e.submit(account,p,quote,Side.SELL,"test",Strategy())}.isFailure);assertEquals(0,b.sent)
    }
    @Test fun stopAndTakeProfitFollowConfiguredThresholds() {
        val e=TradingEngine(FakeBroker(),Journal()){now}
        val h=Holding("005930","name",10,10000,10000,0)
        assertEquals("손절 조건",e.exitReason(h,quote.copy(price=9600),Strategy()))
        assertEquals("익절 조건",e.exitReason(h,quote.copy(price=10700),Strategy()))
    }
}

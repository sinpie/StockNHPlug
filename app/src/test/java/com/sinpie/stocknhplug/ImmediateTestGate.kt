package com.sinpie.stocknhplug

import com.sinpie.stocknhplug.domain.*
import java.time.Instant

/** 자금 조정 테스트 전용 가짜 추적 포트. production 소스에 즉시실행 우회 구현을 두지 않는다. */
class ImmediateTestGate : ExecutionGate {
    override fun evaluate(request: TargetRequest, quote: Quote, now: Instant) = true

    override fun observe(snapshot: PriceSnapshot, now: Instant) {}

    override fun retain(keys: Set<String>) {}

    override fun clear() {}

    override fun targets() = emptyList<TargetStatus>()
}

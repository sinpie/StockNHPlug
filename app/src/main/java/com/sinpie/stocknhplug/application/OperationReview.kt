package com.sinpie.stocknhplug.application

import com.sinpie.stocknhplug.domain.*
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

enum class ReviewLevel {
    READY,
    ATTENTION,
    BLOCKED,
}

data class ReviewItem(
    val id: String,
    val title: String,
    val level: ReviewLevel,
    val detail: String,
)

data class OperationReport(
    val items: List<ReviewItem>,
    val committedBuy: BigDecimal,
    val remainingBuy: BigDecimal,
)

/** 읽기 전용 운용 점검. READY 항목이 있어도 주문 허가가 아니며 최종 위험/연구 게이트는 그대로 적용된다. */
object OperationReview {
    fun inspect(s: AppState, now: Instant): OperationReport {
        val account = s.selected
        val today = now.atZone(SEOUL).toLocalDate()
        // 다른 계좌/환경 주문을 주입받아도 선택 계좌의 잠금·한도에 섞지 않는다.
        val orders =
            s.orders.filter {
                account != null &&
                    it.intent.account == account.number &&
                    it.intent.brokerId == account.brokerId &&
                    it.intent.environment == account.environment
            }
        val unresolved =
            orders.count { it.status == OrderStatus.UNKNOWN || it.status == OrderStatus.SUBMITTING }
        val committed =
            orders
                .filter {
                    it.status != OrderStatus.REJECTED &&
                        it.intent.side == Side.BUY &&
                        it.intent.at.atZone(SEOUL).toLocalDate() == today
                }
                .fold(BigDecimal.ZERO) { sum, row ->
                    sum + row.intent.quantity.toBigDecimal() * row.intent.limitPrice.toBigDecimal()
                }
        val remaining = (s.settings.dailyBudget.toBigDecimal() - committed).max(BigDecimal.ZERO)
        val portfolio = s.portfolio
        val balanceFresh =
            portfolio != null &&
                !portfolio.at.isAfter(now) &&
                Duration.between(portfolio.at, now) <= Duration.ofSeconds(60)
        val symbols = s.book.ledgerGroups().flatMap { it.symbols }.map { it.symbol }.distinct()
        val researchMissing =
            symbols.filter { symbol ->
                s.research.none { it.symbol == symbol && it.buyBlockers(now).isEmpty() }
            }
        val observed = s.history.find { it.date == today }
        val items =
            listOf(
                ReviewItem(
                    "storage",
                    "보안 저장소",
                    if (s.storageError) ReviewLevel.BLOCKED else ReviewLevel.READY,
                    if (s.storageError) "저장소 오류입니다. 삭제 전 증권사 주문을 확인하세요." else "현재 보고된 저장 오류 없음",
                ),
                ReviewItem(
                    "connection",
                    "계좌 연결",
                    if (s.connected && account != null) ReviewLevel.READY else ReviewLevel.BLOCKED,
                    if (s.connected && account != null) "선택 계좌 연결 완료 · 시세 신선도는 별도 검사"
                    else "설정에서 선택 계좌를 연결하세요.",
                ),
                ReviewItem(
                    "unknown",
                    "미확인 주문",
                    if (unresolved > 0) ReviewLevel.BLOCKED else ReviewLevel.READY,
                    if (unresolved > 0) "${unresolved}건 · 내역의 확인 필요 주문을 증권사에서 대조하세요. 자동 재전송하지 않습니다."
                    else "선택 계좌 미확인 원장 없음",
                ),
                ReviewItem(
                    "reconciliation",
                    "그룹 체결 대사",
                    if (s.groupExecutionReady) ReviewLevel.READY else ReviewLevel.BLOCKED,
                    if (s.groupExecutionReady) "그룹 체결 연결 제공됨 · 접수는 체결 완료가 아닙니다."
                    else "주문번호와 체결 연결 검증 전 자동주문 잠금",
                ),
                ReviewItem(
                    "balance",
                    "잔고 신선도",
                    if (balanceFresh) ReviewLevel.READY else ReviewLevel.ATTENTION,
                    if (balanceFresh) "60초 이내 조회 잔고" else "미조회·60초 초과·미래 시각 잔고입니다. 잔고를 갱신하세요.",
                ),
                ReviewItem(
                    "research",
                    "신규 매수 근거",
                    if (symbols.isNotEmpty() && researchMissing.isEmpty()) ReviewLevel.READY
                    else ReviewLevel.ATTENTION,
                    if (symbols.isEmpty()) "분석할 전략 종목을 설정하세요."
                    else if (researchMissing.isEmpty()) "등록 종목의 현재 연구 차단 사유 없음 · 주문 직전 재검증"
                    else
                        "${researchMissing.size}/${symbols.size}종목 근거 부족 · 시세의 분석에서 수정주가·재무·공시·뉴스를 확인하세요.",
                ),
                ReviewItem(
                    "budget",
                    "일 매수 한도",
                    if (remaining.signum() > 0) ReviewLevel.READY else ReviewLevel.ATTENTION,
                    "거절을 제외한 오늘의 매수 원장으로 보수적으로 예약합니다. 미체결·파킹 매수도 포함하며 체결대금과 다릅니다.",
                ),
                ReviewItem(
                    "history",
                    "오늘 기록",
                    if (
                        observed?.portfolio != null &&
                            observed.executionsAt != null &&
                            observed.pnl != null
                    )
                        ReviewLevel.READY
                    else ReviewLevel.ATTENTION,
                    "현금 ${if (observed?.portfolio != null) "있음" else "미조회"} · 거래 ${if (observed?.executionsAt != null) "있음" else "미조회"} · 손익 ${if (observed?.pnl != null) "있음" else "미조회"}. 휴장·미실행일은 자동으로 채우지 않습니다.",
                ),
            )
        return OperationReport(items, committed, remaining)
    }
}

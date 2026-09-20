package com.sinpie.stocknhplug.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sinpie.stocknhplug.application.*
import com.sinpie.stocknhplug.domain.*
import java.math.BigDecimal

/** 손익/현금/거래를 같은 날짜 축에 표시하되 계산 의미는 분리한다. 원천 자료 없는 날은 합성하지 않는다. */
@Composable
internal fun HistoryScreen(s: AppState, refresh: () -> Unit, pnl: () -> Unit) {
    var period by rememberSaveable { mutableIntStateOf(0) }
    var year by rememberSaveable { mutableStateOf("전체") }
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    var detailCount by remember(expanded) { mutableIntStateOf(14) }
    var count by remember(period, year) { mutableIntStateOf(24) }
    Heading("투자 통계", "${s.selected?.masked ?: "계좌 미선택"} · 저장된 날짜 기준")
    SectionTabs(listOf("일별", "월별", "연도별"), period) {
        period = it
        expanded = null
    }
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        (listOf("전체") + s.history.map { it.date.year.toString() }.distinct().sortedDescending())
            .forEach { y -> FilterChip(year == y, { year = y }, label = { Text(y) }) }
    }
    // 시세 틱으로 상위 화면이 재구성되어도 같은 이력을 매번 집계하지 않는다.
    val rows =
        remember(s.history, year) {
            s.history.filter { year == "전체" || it.date.year.toString() == year }
        }
    val stats =
        remember(rows, period) { HistoryAnalytics.summarize(rows, HistoryPeriod.values()[period]) }
    val known = remember(rows) { rows.mapNotNull { it.pnl } }
    Panel("누적 손익") {
        val total =
            known
                .takeIf { it.isNotEmpty() }
                ?.fold(BigDecimal.ZERO) { a, p -> a + p.amount.toBigDecimal() }
        Text(
            total?.let(::amount) ?: "—",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = if ((total?.signum() ?: 0) < 0) Blue else Red,
        )
        Text("증권사 보고 손익 · ${known.size}일 관측", color = Muted, fontSize = 12.sp)
        Text(
            "수수료·세금은 원천값을 별도 표시하며 중복 차감하지 않습니다. 현금 변화에는 입출금·결제가 포함되므로 수익률로 계산하지 않습니다.",
            color = Muted,
            fontSize = 11.sp,
        )
        val positive = known.count { it.amount > 0 }
        val negative = known.count { it.amount < 0 }
        Text("이익 ${positive}일 · 손실 ${negative}일 · 보합 ${known.count { it.amount == 0L }}일")
        Text(
            "이익일 비율 ${if (known.isEmpty()) "—" else "%.1f%%".format(positive * 100.0 / known.size)}",
            fontSize = 13.sp,
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(refresh, enabled = s.connected && !s.running && !s.busy && !s.storageError) {
            Text("거래·현금 갱신")
        }
        OutlinedButton(pnl, enabled = s.connected && !s.running && !s.busy && !s.storageError) {
            Text("손익 갱신")
        }
    }
    Text(
        "운용 중 거래·현금은 잔고 조회 시, 손익은 15분마다 저장합니다. 앱 미실행일의 현금은 소급 생성하지 않습니다. 손익 API는 최근 30일 창만 조회합니다.",
        fontSize = 11.sp,
        color = Muted,
    )
    if (stats.isEmpty()) Text("저장된 일별 기록이 없습니다.", color = Muted)
    val max = stats.mapNotNull { it.pnl?.abs() }.maxOrNull()?.takeIf { it.signum() > 0 }
    stats.take(count).forEach { stat ->
        Panel(stat.period) {
            Text(
                stat.pnl?.let(::amount) ?: "손익 미조회",
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                color = if ((stat.pnl?.signum() ?: 0) < 0) Blue else Red,
            )
            if (max != null && stat.pnl != null)
                LinearProgressIndicator(
                    {
                        stat.pnl
                            .abs()
                            .divide(max, java.math.MathContext.DECIMAL64)
                            .toFloat()
                            .coerceIn(0f, 1f)
                    },
                    Modifier.fillMaxWidth(),
                    color = if (stat.pnl.signum() < 0) Blue else Red,
                )
            Text("마지막 현금 ${won(stat.lastCash)} · 총자산 ${won(stat.lastEquity)}", fontSize = 12.sp)
            Text("체결 주문 ${stat.filledOrders}건 · 거래대금 ${amount(stat.turnover)}", fontSize = 12.sp)
            Text(
                "매수 수수료 ${stat.fees?.let(::amount) ?: "—"} · 매도세금 ${stat.taxes?.let(::amount) ?: "—"}",
                fontSize = 11.sp,
                color = Muted,
            )
            Text(
                "손익 ${stat.pnlDays}일 · 현금 ${stat.days.count { it.portfolio != null }}일 · 거래 조회 ${stat.days.count { it.executionsAt != null }}일",
                fontSize = 11.sp,
                color = Muted,
            )
            TextButton({ expanded = if (expanded == stat.period) null else stat.period }) {
                Text(if (expanded == stat.period) "기록 접기" else "일별 기록")
            }
            if (expanded == stat.period)
                stat.days.asReversed().take(detailCount).forEach { day ->
                    var tradeCount by remember(day.date) { mutableIntStateOf(40) }
                    Text(day.date.toString(), fontWeight = FontWeight.SemiBold)
                    Text(
                        "현금 ${won(day.portfolio?.cash)} · 평가손익 ${won(day.portfolio?.unrealized)}",
                        fontSize = 12.sp,
                    )
                    day.portfolio?.let {
                        Text("잔고 기준 ${time(it.at)}", fontSize = 11.sp, color = Muted)
                    }
                    Text("보고 손익 ${won(day.pnl?.amount)}", fontSize = 12.sp)
                    if (day.executionsAt == null) Text("거래 미조회", color = Muted, fontSize = 11.sp)
                    else {
                        Text(
                            "거래 기준 ${time(day.executionsAt)} · ${day.executions.size}건",
                            fontSize = 11.sp,
                            color = Muted,
                        )
                        day.executions.take(tradeCount).forEach { e ->
                            Text(
                                "${e.name} · ${e.side} · 체결 ${e.filled}/${e.ordered}주 · 평균 ${e.average}원 · 잔여 ${e.remaining}주",
                                fontSize = 12.sp,
                            )
                        }
                        if (day.executions.size > tradeCount)
                            TextButton({ tradeCount += 40 }) { Text("거래 40건 더 보기") }
                    }
                }
            if (expanded == stat.period && detailCount < stat.days.size)
                TextButton({ detailCount += 14 }) { Text("14일 더 보기") }
        }
    }
    if (count < stats.size) TextButton({ count += 24 }) { Text("24건 더 보기") }
}

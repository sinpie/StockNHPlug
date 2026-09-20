package com.sinpie.stocknhplug.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sinpie.stocknhplug.application.AccountAnalytics
import com.sinpie.stocknhplug.domain.*
import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Locale

internal fun percent(value: Double?) =
    value?.let { String.format(Locale.KOREA, "%+.2f%%", it) } ?: "—"

internal fun amount(value: BigDecimal) =
    NumberFormat.getIntegerInstance(Locale.KOREA).format(value) + "원"

/** 현재 보유 주식 내 비중이다. 예수금/타 계좌/앱 그룹 원장을 섞거나 가격을 생성하지 않는다. */
@Composable
internal fun AllocationPanel(holdings: List<Holding>) {
    val metrics = AccountAnalytics.holdings(holdings).sortedByDescending { it.value }
    Panel("포트폴리오") {
        Text(
            amount(metrics.fold(BigDecimal.ZERO) { total, m -> total + m.value }),
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
        )
        Text("보유주식 평가액 · ${holdings.size}종목", fontSize = 12.sp, color = Muted)
        metrics.take(5).forEach { m ->
            val weight = m.weightPercent
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(m.holding.name, fontSize = 13.sp)
                Text(
                    weight?.let { String.format(Locale.KOREA, "%.1f%%", it) } ?: "—",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (weight != null)
                LinearProgressIndicator(
                    { (weight / 100).toFloat().coerceIn(0f, 1f) },
                    Modifier.fillMaxWidth().height(4.dp),
                    color = Teal,
                    trackColor = Line,
                )
        }
        Text("보유주식 내 비중 상위 5종목 · 예수금 제외", fontSize = 11.sp, color = Muted)
    }
}

/** 공식 API가 제공하는 최우선 1개 호가만 표시한다. 잔량/체결강도/등락률은 추정하지 않는다. */
@Composable
internal fun QuoteBook(quote: Quote) {
    Text("최우선 호가", fontSize = 12.sp, color = Muted)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(
            Modifier.weight(1f)
                .background(Blue.copy(alpha = .05f), RoundedCornerShape(10.dp))
                .padding(12.dp)
        ) {
            Text("매수", color = Blue, fontSize = 11.sp)
            Text(
                won(quote.bid.takeIf { it > 0 }),
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
            )
        }
        Column(
            Modifier.weight(1f)
                .background(Red.copy(alpha = .05f), RoundedCornerShape(10.dp))
                .padding(12.dp)
        ) {
            Text("매도", color = Red, fontSize = 11.sp)
            Text(
                won(quote.ask.takeIf { it > 0 }),
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
            )
        }
    }
    Text(
        "스프레드 ${won(AccountAnalytics.spread(quote))} · ${AccountAnalytics.spreadPercent(quote)?.let { String.format(Locale.KOREA, "%.3f%%", it) } ?: "—"}",
        fontSize = 11.sp,
        color = Muted,
    )
}

/** 실제 조회된 일별 종가만 그린다. 수정 여부를 항상 표시하며 결측을 합성하지 않는다. */
@Composable
internal fun PriceHistoryChart(candles: List<Candle>, adjusted: Boolean) {
    val points =
        candles.filter { it.close.isFinite() && it.close > 0 }.sortedBy { it.date }.takeLast(60)
    if (points.size < 2) return
    val low = points.minOf { it.close }
    val high = points.maxOf { it.close }
    Text("일별 종가 · ${points.size}개 관측", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    Text(if (adjusted) "수정주가" else "원주가 · 수정 기준 미검증", fontSize = 11.sp, color = Muted)
    Canvas(
        Modifier.fillMaxWidth().height(110.dp).semantics {
            contentDescription = "일별 종가 추이. 최저 ${low.toLong()}원, 최고 ${high.toLong()}원"
        }
    ) {
        val range = (high - low).coerceAtLeast(1.0)
        for (i in 0..2) drawLine(
            Line,
            androidx.compose.ui.geometry.Offset(0f, size.height * i / 2),
            androidx.compose.ui.geometry.Offset(size.width, size.height * i / 2),
            1.dp.toPx(),
        )
        val path = Path()
        points.forEachIndexed { index, candle ->
            val x = size.width * index / (points.size - 1)
            val y = (size.height * (1 - (candle.close - low) / range)).toFloat()
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, Teal, style = Stroke(2.dp.toPx()))
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(points.first().date.toString(), fontSize = 10.sp, color = Muted)
        Text(points.last().date.toString(), fontSize = 10.sp, color = Muted)
    }
    Text("최저 ${won(low.toLong())} · 최고 ${won(high.toLong())}", fontSize = 11.sp, color = Muted)
}

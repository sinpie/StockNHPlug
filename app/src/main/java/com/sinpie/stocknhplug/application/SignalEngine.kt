package com.sinpie.stocknhplug.application

import com.sinpie.stocknhplug.domain.*
import java.time.LocalDate
import kotlin.math.*

/** Deterministic, explainable screening. Never reads today's incomplete daily bar. */
object SignalEngine {
    fun evaluate(symbol: String, input: List<Candle>, today: LocalDate): Candidate? {
        val bars = input.filter { it.date < today }.sortedBy { it.date }
        if (bars.size < 61 || bars.map { it.date }.distinct().size != bars.size) return null
        if (bars.any { !it.close.isFinite() || !it.high.isFinite() || !it.low.isFinite() || !it.volume.isFinite() ||
            it.close <= 0 || it.low <= 0 || it.high < it.low || it.close !in it.low..it.high || it.volume < 0 }) return null
        if (java.time.temporal.ChronoUnit.DAYS.between(bars.last().date, today) > 7) return null
        val close = bars.map { it.close }
        val sma20 = close.takeLast(20).average(); val sma60 = close.takeLast(60).average()
        val rsi = rsi(close)
        val atr = atr(bars) / close.last() * 100
        val volumeRatio = bars.last().volume / bars.dropLast(1).takeLast(20).map { it.volume }.average().coerceAtLeast(1.0)
        val momentum = close.last() / close[close.lastIndex - 20] - 1
        val score = (if (close.last() > sma20 && sma20 > sma60) 35 else 0) +
            (if (rsi in 45.0..68.0) 25 else 0) + (if (momentum > 0) 20 else 0) +
            (if (volumeRatio >= 1.2) 10 else 0) + (if (atr in 0.3..4.0) 10 else 0)
        return Candidate(symbol, score, rsi, atr, "추세 ${if (sma20 > sma60) "상승" else "대기"} · RSI ${rsi.roundToInt()} · ATR ${"%.1f".format(java.util.Locale.US, atr)}%")
    }
    fun rsi(values: List<Double>, period: Int = 14): Double {
        require(period > 0 && values.size > period)
        val changes = values.zipWithNext { a, b -> b - a }
        var gain = changes.take(period).sumOf { max(it, 0.0) } / period
        var loss = changes.take(period).sumOf { max(-it, 0.0) } / period
        changes.drop(period).forEach { gain = (gain * (period - 1) + max(it, 0.0)) / period; loss = (loss * (period - 1) + max(-it, 0.0)) / period }
        return if (gain == 0.0 && loss == 0.0) 50.0 else if (loss == 0.0) 100.0 else 100 - 100 / (1 + gain / loss)
    }
    fun atr(bars: List<Candle>, period: Int = 14): Double {
        require(bars.size > period)
        val ranges = bars.zipWithNext { a, b -> max(b.high - b.low, max(abs(b.high - a.close), abs(b.low - a.close))) }
        var result = ranges.take(period).average()
        ranges.drop(period).forEach { result = (result * (period - 1) + it) / period }
        return result
    }
}

package com.sinpie.stocknhplug.application

import com.sinpie.stocknhplug.domain.*
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.math.BigDecimal
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

enum class HistoryExportFormat(val mime: String, val extension: String) {
    CSV("text/csv", "csv"),
    ARCHIVE("application/zip", "zip"),
}

/** 저장 대화상자를 열 때 계좌·필터·자료를 고정한다. 계좌 전체 번호와 인증 자료를 보유하지 않는다. */
class HistoryExport
private constructor(
    val format: HistoryExportFormat,
    private val accountLabel: String,
    private val period: HistoryPeriod,
    private val days: List<AccountDay>,
    private val at: Instant,
) {
    val filename: String =
        "StockNHPlug_${period.name.lowercase()}_${at.epochSecond}.${format.extension}"

    companion object {
        fun capture(
            account: Account,
            rows: List<AccountDay>,
            period: HistoryPeriod,
            format: HistoryExportFormat,
            at: Instant = Instant.now(),
        ): HistoryExport {
            require(rows.isNotEmpty()) { "내보낼 기록이 없습니다." }
            require(rows.map { it.date }.distinct().size == rows.size)
            // 중첩 목록도 복사하여 계좌 전환/새 조회/호출자 목록 변경이 내보내기에 섞이지 않게 한다.
            val snapshot =
                rows
                    .sortedBy { it.date }
                    .map {
                        it.copy(
                            executions = it.executions.toList(),
                            portfolio =
                                it.portfolio?.copy(holdings = it.portfolio.holdings.toList()),
                        )
                    }
            return HistoryExport(
                format,
                "${account.brokerId} / ${account.environment} / ${account.masked}",
                period,
                snapshot,
                at,
            )
        }
    }

    /** 스트리밍 출력. 파일 소유자는 호출자이며 close 성공까지 저장 완료로 간주하지 않는다. */
    fun write(output: OutputStream, checkActive: () -> Unit = {}) {
        if (format == HistoryExportFormat.CSV) {
            csv(output, checkActive) { summary(this, period) }
        } else {
            // ZIP 자체 암호화는 하지 않는다. 내부 앱 저장소의 암호화와 외부 문서를 혼동하지 않는다.
            // close 시 Deflater도 해제하되 하위 출력의 close 책임은 호출자에게 남긴다.
            val zip =
                ZipOutputStream(
                    object : java.io.FilterOutputStream(output) {
                        // FilterOutputStream 기본 구현의 바이트별 쓰기를 피한다. 문서 제공자에는 블록으로 전달한다.
                        override fun write(bytes: ByteArray, offset: Int, length: Int) {
                            output.write(bytes, offset, length)
                        }

                        override fun close() {
                            flush()
                        }
                    }
                )
            zip.use {
                fun entry(name: String, block: () -> Unit) {
                    checkActive()
                    zip.putNextEntry(ZipEntry(name))
                    block()
                    zip.closeEntry()
                }
                HistoryPeriod.values().forEach { p ->
                    entry("${p.name.lowercase()}.csv") {
                        csv(zip, checkActive) { summary(this, p) }
                    }
                }
                entry("balances.csv") {
                    csv(zip, checkActive) {
                        row(
                            "계좌",
                            "날짜",
                            "현금_원",
                            "총자산_원",
                            "평가손익_원",
                            "잔고조회_UTC",
                            "보고손익_원",
                            "손익조회_UTC",
                            "거래조회_UTC",
                        )
                        days.forEach { d ->
                            row(
                                accountLabel,
                                d.date,
                                d.portfolio?.cash,
                                d.portfolio?.equity,
                                d.portfolio?.unrealized,
                                d.portfolio?.at,
                                d.pnl?.amount,
                                d.pnlAt,
                                d.executionsAt,
                            )
                        }
                    }
                }
                entry("trades.csv") {
                    csv(zip, checkActive) {
                        row(
                            "계좌",
                            "날짜",
                            "종목코드",
                            "종목명",
                            "방향",
                            "주문수량",
                            "누적체결수량",
                            "잔여수량",
                            "평균체결가_원",
                            "조회_UTC",
                        )
                        days.forEach { d ->
                            d.executions.forEach { e ->
                                row(
                                    accountLabel,
                                    d.date,
                                    TextId(e.symbol),
                                    e.name,
                                    e.side,
                                    e.ordered,
                                    e.filled,
                                    e.remaining,
                                    BigDecimal.valueOf(e.average),
                                    d.executionsAt,
                                )
                            }
                        }
                    }
                }
                entry("README.txt") { zip.write(notes().toByteArray(Charsets.UTF_8)) }
                zip.finish()
                zip.flush()
            }
        }
    }

    private fun summary(csv: Csv, period: HistoryPeriod) {
        csv.row(
            "계좌",
            "기간",
            "보고손익_원",
            "매수수수료_원",
            "매도세금_원",
            "손익관측일",
            "현금관측일",
            "거래관측일",
            "이익일",
            "손실일",
            "보합일",
            "체결주문수",
            "거래대금근사_원",
            "마지막현금_원",
            "마지막총자산_원",
            "생성_UTC",
            "설명",
        )
        HistoryAnalytics.summarize(days, period).forEach { s ->
            csv.row(
                accountLabel,
                s.period,
                s.pnl,
                s.fees,
                s.taxes,
                s.pnlDays,
                s.days.count { it.portfolio != null },
                s.days.count { it.executionsAt != null },
                s.positiveDays,
                s.negativeDays,
                s.flatDays,
                s.filledOrders,
                s.turnover,
                s.lastCash,
                s.lastEquity,
                at,
                "KRW; 빈칸=미조회; 수수료·세금 별도; 종가·세무 증빙 아님",
            )
        }
    }

    private fun notes() =
        """
        StockNHPlug 개인 계좌 이력 v1
        계좌: $accountLabel
        생성 시각(UTC): $at
        범위: ${days.first().date} ~ ${days.last().date} / 저장된 ${days.size}일
        통화: KRW. UTF-8 BOM CSV. day/month/year는 동일 범위의 집계입니다.
        빈칸은 미조회이며 0이 아닙니다. 앱 미실행일의 현금·거래를 소급 생성하지 않습니다.
        보고 손익은 증권사 원천값입니다. 수수료·세금을 다시 차감하지 않습니다.
        현금/총자산은 마지막 관측값이며 종가가 아닙니다. 입출금 조정 수익률/세무 증빙이 아닙니다.
        거래대금은 평균체결가×누적수량의 근사값입니다. 접수 주문은 체결로 계산하지 않습니다.
        trades는 날짜별 마지막 누적 체결 응답입니다. 주문번호·전체 계좌번호·키·뉴스는 제외합니다.
        종목코드 및 위험한 문자열 앞의 작은따옴표는 엑셀 자동 변환/수식 실행 방지용입니다.
        파일은 암호화되지 않습니다. 삭제·보관·공유는 선택한 저장소에서 직접 관리하세요.
    """
            .trimIndent()

    private data class TextId(val value: String)

    private fun csv(output: OutputStream, checkActive: () -> Unit, block: Csv.() -> Unit) {
        val writer = OutputStreamWriter(output, Charsets.UTF_8)
        writer.write("\uFEFF") // Excel의 한글 인코딩 인식을 돕는다.
        Csv(writer, checkActive).block()
        writer.flush() // ZIP 다음 항목을 위해 이 writer가 하위 스트림을 닫지 않는다.
    }

    private class Csv(val writer: OutputStreamWriter, val checkActive: () -> Unit) {
        fun row(vararg values: Any?) {
            checkActive()
            writer.write(
                values.joinToString(",") { value ->
                    val raw =
                        when (value) {
                            null -> ""
                            is BigDecimal -> value.toPlainString()
                            is TextId -> "'" + value.value
                            is Number -> value.toString()
                            else -> {
                                val text = value.toString()
                                // 따옴표 이스케이프만으로는 수식 실행을 막지 못한다. 숫자는 별도 경로로 보존한다.
                                if (
                                    text.firstOrNull()?.let {
                                        it.isWhitespace() || it.isISOControl()
                                    } == true ||
                                        text.trimStart().firstOrNull() in
                                            listOf('=', '+', '-', '@', '＝', '＋', '－', '＠')
                                )
                                    "'$text"
                                else text
                            }
                        }
                    "\"${raw.replace("\"", "\"\"")}\""
                }
            )
            writer.write("\r\n")
        }
    }
}

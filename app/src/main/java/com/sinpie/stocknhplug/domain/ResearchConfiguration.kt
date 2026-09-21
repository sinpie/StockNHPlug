package com.sinpie.stocknhplug.domain

import java.time.LocalDate

/** Account-scoped research request, never evidence or permission. Zero year follows prior year. */
data class ResearchConfiguration(
    val corpMapping: String = "",
    val year: Int = 0,
    val reportCode: String = "11011",
) {
    fun validate() {
        require(year == 0 || year in 2015..2100)
        require(reportCode in setOf("11011", "11012", "11013", "11014"))
        require(corpMapping.length <= 4000)
        mapping()
    }

    fun resolvedYear(today: LocalDate): Int = if (year == 0) today.year - 1 else year

    fun mapping(): Map<String, String> {
        val result = linkedMapOf<String, String>()
        corpMapping
            .split(',', '\n')
            .filter { it.isNotBlank() }
            .forEach { entry ->
                val parts = entry.trim().split(':').map(String::trim)
                require(
                    parts.size == 2 &&
                        parts[0].matches(Regex("[0-9]{6}")) &&
                        parts[1].matches(Regex("[0-9]{8}"))
                ) {
                    "기업 매핑 형식: 종목코드:공시고유번호"
                }
                require(parts[0] !in result) { "기업 매핑 종목이 중복되었습니다." }
                result[parts[0]] = parts[1]
            }
        return result
    }
}

package com.sinpie.stocknhplug

import com.sinpie.stocknhplug.research.provider.DartCompanyDirectory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

class DartCompanyDirectoryTest {
    private fun archive(
        xml: String,
        name: String = "CORPCODE.xml",
        extra: Boolean = false,
    ): ByteArrayInputStream {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use {
            it.putNextEntry(ZipEntry(name))
            it.write(xml.toByteArray())
            it.closeEntry()
            if (extra) {
                it.putNextEntry(ZipEntry("extra.xml"))
                it.write("extra".toByteArray())
                it.closeEntry()
            }
        }
        return ByteArrayInputStream(output.toByteArray())
    }

    private val row =
        "<list><corp_code>00126380</corp_code><corp_name>테스트</corp_name><stock_code>005930</stock_code></list>"

    @Test
    fun officialStockIdentifiersArePreservedAndUnlistedCompaniesExcluded() {
        val rows =
            DartCompanyDirectory.parseArchive(
                archive(
                    "<result>$row<list><corp_code>00000001</corp_code><stock_code> </stock_code></list></result>"
                )
            )
        assertEquals(mapOf("005930" to "00126380"), rows)
    }

    @Test
    fun unsupportedSymbolsAreExcludedWithoutCoercingThem() {
        val xml =
            "<result>$row<list><corp_code>00000001</corp_code><stock_code>12345A</stock_code></list></result>"
        assertEquals(mapOf("005930" to "00126380"), DartCompanyDirectory.parseArchive(archive(xml)))
        val invalid =
            "<result><list><corp_code>invalid</corp_code><stock_code>123456</stock_code></list></result>"
        assertTrue(runCatching { DartCompanyDirectory.parseArchive(archive(invalid)) }.isFailure)
    }

    @Test
    fun duplicateSymbolFailsRatherThanChoosingLastIdentity() {
        assertTrue(
            runCatching { DartCompanyDirectory.parseArchive(archive("<result>$row$row</result>")) }
                .isFailure
        )
    }

    @Test
    fun externalEntitiesAndDtdAreRejected() {
        val xml = "<!DOCTYPE result [<!ENTITY x SYSTEM 'file:///never-read'>]><result>$row</result>"
        assertTrue(runCatching { DartCompanyDirectory.parseArchive(archive(xml)) }.isFailure)
    }

    @Test
    fun UnexpectedArchiveEntriesAndEmptyApiErrorsFail() {
        for (data in
            listOf(
                archive("<result>$row</result>", "../CORPCODE.xml"),
                archive("<result>$row</result>", extra = true),
                archive("<result><status>010</status></result>"),
            )) assertTrue(runCatching { DartCompanyDirectory.parseArchive(data) }.isFailure)
    }
}

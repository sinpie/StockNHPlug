package com.sinpie.stocknhplug

import com.sinpie.stocknhplug.research.provider.DartCompanyDirectory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

class DartDirectoryAndroidTest {
    private fun parse(xml: String): Map<String, String> {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use {
            it.putNextEntry(ZipEntry("CORPCODE.xml"))
            it.write(xml.toByteArray())
            it.closeEntry()
        }
        return DartCompanyDirectory.parseArchive(ByteArrayInputStream(bytes.toByteArray()))
    }

    @Test
    fun androidParserSupportsOfficialIdentityArchive() {
        assertEquals(
            mapOf("005930" to "00126380"),
            parse(
                "<result><list><corp_code>00126380</corp_code><stock_code>005930</stock_code></list></result>"
            ),
        )
    }

    @Test
    fun androidParserRejectsDoctypeBeforeEntities() {
        assertTrue(
            runCatching { parse("<!DOCTYPE result [<!ENTITY a 'entity'>]><result>&a;</result>") }
                .isFailure
        )
    }
}

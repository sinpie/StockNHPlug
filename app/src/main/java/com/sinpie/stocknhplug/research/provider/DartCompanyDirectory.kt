package com.sinpie.stocknhplug.research.provider

import com.sinpie.stocknhplug.research.CorporateDirectory
import java.io.FilterInputStream
import java.io.InputStream
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import javax.xml.parsers.SAXParserFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.ext.DefaultHandler2

/**
 * Official OpenDART corpCode archive; memory-only cache, no external entities or extracted files.
 */
class DartCompanyDirectory(private val keyProvider: () -> String) : CorporateDirectory {
    private val gate = Mutex()
    private val client =
        OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
    private var cached = emptyMap<String, String>()
    private var expires = Instant.MIN

    override suspend fun corporation(symbol: String): String? =
        withContext(Dispatchers.IO) {
            require(symbol.matches(Regex("[0-9]{6}")))
            gate.withLock {
                if (cached.isEmpty() || !Instant.now().isBefore(expires)) {
                    val key = keyProvider()
                    require(key.matches(Regex("[A-Za-z0-9]{40}"))) { "OpenDART 인증키 형식을 확인하세요." }
                    val url =
                        "https://opendart.fss.or.kr/api/corpCode.xml"
                            .toHttpUrl()
                            .newBuilder()
                            .addQueryParameter("crtfc_key", key)
                            .build()
                    val fresh =
                        client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                            check(r.isSuccessful) { "기업 식별정보 조회 실패" }
                            parseArchive(r.body?.byteStream() ?: error("기업 식별정보 응답 없음"))
                        }
                    cached = fresh
                    expires = Instant.now().plusSeconds(86400)
                }
                cached[symbol]
            }
        }

    companion object {
        /** Reject malformed/ambiguous identities and ZIP/XML expansion before caching anything. */
        fun parseArchive(input: InputStream): Map<String, String> {
            val result = linkedMapOf<String, String>()
            ZipInputStream(Limited(input, 10L * 1024 * 1024)).use { zip ->
                val entry = zip.nextEntry ?: error("기업 식별정보 ZIP 필요")
                require(
                    !entry.isDirectory && entry.name.equals("CORPCODE.xml", ignoreCase = true)
                ) {
                    "DART_DIRECTORY_ARCHIVE_NAME"
                }
                val handler =
                    object : DefaultHandler2() {
                        var field = ""
                        var text = StringBuilder()
                        var stock = ""
                        var corp = ""

                        override fun startDTD(name: String?, publicId: String?, systemId: String?) {
                            throw SAXException("DTD prohibited")
                        }

                        override fun resolveEntity(
                            publicId: String?,
                            systemId: String?,
                        ): InputSource {
                            throw SAXException("External entity prohibited")
                        }

                        override fun startElement(
                            uri: String?,
                            local: String?,
                            name: String,
                            attrs: org.xml.sax.Attributes?,
                        ) {
                            field = name
                            text = StringBuilder()
                            if (name == "list") {
                                stock = ""
                                corp = ""
                            }
                        }

                        override fun characters(chars: CharArray, start: Int, length: Int) {
                            require(text.length + length <= 4096) { "DART_DIRECTORY_FIELD_SIZE" }
                            text.append(chars, start, length)
                        }

                        override fun endElement(uri: String?, local: String?, name: String) {
                            when (name) {
                                "stock_code" -> stock = text.toString().trim()
                                "corp_code" -> corp = text.toString().trim()
                                "list" ->
                                    // This app's execution port accepts numeric six-digit symbols
                                    // only. Other directory identifiers must not invalidate the
                                    // supported universe or be coerced into a tradable symbol.
                                    if (stock.matches(Regex("[0-9]{6}"))) {
                                        require(corp.matches(Regex("[0-9]{8}"))) {
                                            "DART_DIRECTORY_ID_FORMAT"
                                        }
                                        require(stock !in result) { "DART_DIRECTORY_DUPLICATE" }
                                        require(result.size < 50000) { "DART_DIRECTORY_COUNT" }
                                        result[stock] = corp
                                    }
                            }
                            text = StringBuilder()
                        }
                    }
                val reader = SAXParserFactory.newInstance().newSAXParser().xmlReader
                reader.setFeature("http://xml.org/sax/features/external-general-entities", false)
                reader.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                reader.setProperty("http://xml.org/sax/properties/lexical-handler", handler)
                reader.contentHandler = handler
                reader.entityResolver = handler
                // The parser must not close the ZIP before the extra-entry check.
                reader.parse(
                    InputSource(
                        object : FilterInputStream(Limited(zip, 64L * 1024 * 1024)) {
                            override fun close() {}
                        }
                    )
                )
                zip.closeEntry()
                require(zip.nextEntry == null && result.isNotEmpty()) {
                    "DART_DIRECTORY_EMPTY_OR_EXTRA"
                }
            }
            return result.toMap()
        }

        private class Limited(input: InputStream, private val maximum: Long) :
            FilterInputStream(input) {
            private var count = 0L

            override fun read(): Int = super.read().also { if (it >= 0) check(++count <= maximum) }

            override fun read(b: ByteArray, off: Int, len: Int): Int =
                `in`.read(b, off, len).also {
                    if (it > 0) {
                        count += it
                        check(count <= maximum)
                    }
                }
        }
    }
}

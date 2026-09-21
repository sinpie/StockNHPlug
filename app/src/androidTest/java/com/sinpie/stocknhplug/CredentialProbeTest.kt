package com.sinpie.stocknhplug

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import com.sinpie.stocknhplug.application.HistoryExport
import com.sinpie.stocknhplug.application.HistoryExportFormat
import com.sinpie.stocknhplug.application.HistoryPeriod
import com.sinpie.stocknhplug.data.EncryptedAccountHistory
import com.sinpie.stocknhplug.data.SecureVault
import com.sinpie.stocknhplug.domain.Environment
import com.sinpie.stocknhplug.domain.SEOUL
import com.sinpie.stocknhplug.domain.Side
import com.sinpie.stocknhplug.execution.NhBroker
import com.sinpie.stocknhplug.execution.NhSocket
import com.sinpie.stocknhplug.infrastructure.nh.NhTransport
import com.sinpie.stocknhplug.marketdata.NhCurrentPriceProvider
import com.sinpie.stocknhplug.marketdata.NhPriceHistoryProvider
import com.sinpie.stocknhplug.research.provider.DartClient
import java.io.File
import java.nio.ByteBuffer
import java.security.KeyPairGenerator
import java.security.spec.MGF1ParameterSpec
import java.time.LocalDate
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Explicitly opted-in, read-only credential probe on a disposable debug install. No order methods,
 * service/controller startup, raw responses or exception messages. RSA private key lives only in
 * memory. Host sends an AES-GCM envelope via adb stdin. This test is excluded by default and is
 * never bundled in a distributable APK.
 */
class CredentialProbeTest {
    @Test
    fun readOnlyOfficialApis(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("credentialProbe") == "1")
        val context = instrumentation.targetContext
        check(context.packageName == "com.sinpie.stocknhplug.debug")
        val vault = SecureVault(context)
        // Refuse to overwrite any previous credentials/data, even on a debug install.
        check(context.noBackupFilesDir.listFiles().orEmpty().none { it.extension == "enc" })
        val publicFile = File(context.cacheDir, "probe-public.der")
        val envelopeFile = File(context.cacheDir, "probe-envelope.bin")
        check(!publicFile.exists() && !envelopeFile.exists())
        var transport: NhTransport? = null
        var failures = 0
        fun report(step: String, outcome: String) {
            instrumentation.sendStatus(0, Bundle().apply { putString("probe", "$step=$outcome") })
        }
        suspend fun step(name: String, action: suspend () -> Unit): Boolean =
            try {
                action()
                report(name, "PASS")
                true
            } catch (e: Exception) {
                failures++
                // Even a network exception can contain a credential-bearing URL: type only.
                val http =
                    Regex("^NHPlug (?:인증|요청) 실패 \\(HTTP ([0-9]{3})\\)$")
                        .matchEntire(e.message.orEmpty())
                        ?.groupValues
                        ?.get(1)
                val business =
                    Regex("^NHPlug 업무 오류 \\(([A-Za-z0-9]{0,16})\\)$")
                        .matchEntire(e.message.orEmpty())
                        ?.groupValues
                        ?.get(1)
                report(
                    name,
                    if (http != null) "FAIL_HTTP_$http"
                    else if (business != null) "FAIL_BUSINESS_$business"
                    else if (e.message.orEmpty().matches(Regex("DART_DIRECTORY_[A-Z_]{1,30}")))
                        "FAIL_${e.message}"
                    else "FAIL_${e.javaClass.simpleName.filter { it.isLetterOrDigit() }}",
                )
                false
            }
        try {
            val pair =
                KeyPairGenerator.getInstance("RSA").apply { initialize(3072) }.generateKeyPair()
            publicFile.writeBytes(pair.public.encoded)
            report("handoff", "READY")
            val deadline = android.os.SystemClock.elapsedRealtime() + 120_000
            while (!envelopeFile.exists() && android.os.SystemClock.elapsedRealtime() < deadline) {
                kotlinx.coroutines.delay(200)
            }
            check(envelopeFile.exists())
            val bytes = envelopeFile.readBytes()
            val buffer = ByteBuffer.wrap(bytes)
            val keySize = buffer.int
            check(keySize == 384 && bytes.size in 417..16384)
            val wrappedKey = ByteArray(keySize).also { buffer.get(it) }
            val iv = ByteArray(12).also { buffer.get(it) }
            val encrypted = ByteArray(buffer.remaining()).also { buffer.get(it) }
            val rsa = Cipher.getInstance("RSA/ECB/OAEPPadding")
            rsa.init(
                Cipher.DECRYPT_MODE,
                pair.private,
                OAEPParameterSpec(
                    "SHA-256",
                    "MGF1",
                    MGF1ParameterSpec.SHA256,
                    PSource.PSpecified.DEFAULT,
                ),
            )
            val key = rsa.doFinal(wrappedKey)
            val aes = Cipher.getInstance("AES/GCM/NoPadding")
            aes.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            aes.updateAAD("StockNHPlug read-only probe v1".toByteArray())
            val plain = aes.doFinal(encrypted)
            val credentials =
                try {
                    JSONObject(String(plain, Charsets.UTF_8))
                } finally {
                    plain.fill(0)
                    key.fill(0)
                }
            vault.write("credentials", credentials)
            check(vault.read("credentials")!!.getString("key") == credentials.getString("key"))
            report("encrypted_storage", "PASS")
            val nh = NhTransport(vault, Environment.MOCK)
            transport = nh
            val directoryOnly =
                InstrumentationRegistry.getArguments().getString("directoryProbeOnly") == "1"
            report("profile", if (directoryOnly) "DART_ONLY" else "FULL_READ_ONLY")
            val authenticated = !directoryOnly && step("nh_auth") { check(nh.token().isNotBlank()) }
            if (authenticated) {
                val broker = NhBroker(nh)
                step("mock_accounts") {
                    val accounts = broker.accounts()
                    check(accounts.isNotEmpty())
                    check(accounts.distinct().size == accounts.size)
                    report("mock_account_count", accounts.size.toString())
                    accounts.forEachIndexed { index, account ->
                        // Account number and financial data are never included in probe output.
                        check(
                            account.environment == Environment.MOCK && account.brokerId == broker.id
                        )
                        val prefix = "account_${index + 1}"
                        step("${prefix}_balance") { broker.portfolio(account) }
                        step("${prefix}_executions") {
                            broker.executions(account, LocalDate.now(SEOUL))
                        }
                        step("${prefix}_pnl") { broker.dailyPnl(account) }
                        // Availability is an inquiry, never an order. The price is read from NH.
                        step("${prefix}_availability") {
                            val response =
                                nh.call(
                                    "/krstock/quote/v1/currentPrice",
                                    JSONObject().put("iem_cd", "005930").put("market_cd", "KRX"),
                                )
                            val row = response.getJSONObject("Output_0")
                            check(row.getString("iem_cd") == "005930")
                            val price = row.getLong("stck_prpr")
                            check(price > 0)
                            // Availability inquiries do not require an executable quote. Keep
                            // freshness validation independent so a stale quote cannot mask APIs.
                            step("${prefix}_buy_available") {
                                check(broker.available(account, "005930", Side.BUY, price) >= 0)
                            }
                            step("${prefix}_sell_available") {
                                check(broker.available(account, "005930", Side.SELL, price) >= 0)
                            }
                        }
                        step("${prefix}_history_export") {
                            val history = EncryptedAccountHistory(SecureVault(context, account))
                            val portfolio = broker.portfolio(account)
                            val today = portfolio.at.atZone(SEOUL).toLocalDate()
                            val executions = broker.executions(account, today)
                            val at = java.time.Instant.now()
                            history.record(today, portfolio, executions, at)
                            history.mergePnl(broker.dailyPnl(account), java.time.Instant.now())
                            val restored =
                                EncryptedAccountHistory(SecureVault(context, account)).days()
                            check(restored.single { it.date == today }.portfolio == portfolio)
                            check(restored.single { it.date == today }.executions == executions)
                            // Exercise real account data through the exporter in memory only.
                            for (format in HistoryExportFormat.values()) {
                                val output = java.io.ByteArrayOutputStream()
                                HistoryExport.capture(account, restored, HistoryPeriod.DAY, format)
                                    .write(output)
                                check(output.size() > 0)
                                output.reset()
                            }
                        }
                    }
                }
                step("current_price_response") {
                    val response =
                        nh.call(
                            "/krstock/quote/v1/currentPrice",
                            JSONObject().put("iem_cd", "005930").put("market_cd", "KRX"),
                        )
                    check(response.getJSONObject("Output_0").getString("iem_cd") == "005930")
                    // Only structural predicates are emitted, never market values or raw fields.
                    step("current_price_diagnostics") {
                        val row = response.getJSONObject("Output_0")
                        val now = java.time.Instant.now()
                        val digits = row.getString("hoga_bsop_hour").replace(":", "")
                        val exchange =
                            now.atZone(SEOUL)
                                .toLocalDate()
                                .atTime(
                                    java.time.LocalTime.parse(
                                        digits,
                                        java.time.format.DateTimeFormatter.ofPattern("HHmmss"),
                                    )
                                )
                                .atZone(SEOUL)
                                .toInstant()
                        val q =
                            com.sinpie.stocknhplug.domain.Quote(
                                "005930",
                                row.getLong("stck_prpr"),
                                row.getLong("bidp"),
                                row.getLong("askp"),
                                now,
                                exchange,
                                response.getJSONObject("Output_2").getString("cncc_aspr_code") ==
                                    "0",
                            )
                        report("rest_fresh", if (q.fresh(now)) "YES" else "NO")
                        report(
                            "rest_positive_ordered_quotes",
                            if (q.price > 0 && q.bid > 0 && q.ask >= q.bid) "YES" else "NO",
                        )
                        val lower = row.getLong("stck_llam")
                        val upper = row.getLong("stck_mxpr")
                        report(
                            "rest_prices_within_limits",
                            if (
                                lower > 0 &&
                                    upper > lower &&
                                    listOf(q.price, q.bid, q.ask).all { it in lower..upper }
                            )
                                "YES"
                            else "NO",
                        )
                        report("rest_regular_session", if (q.regular) "YES" else "NO")
                    }
                    step("current_price_validation") {
                        NhCurrentPriceProvider.parse("005930", response, java.time.Instant.now())
                    }
                }
                step("price_history") {
                    val history = NhPriceHistoryProvider(nh).history("005930")
                    check(!history.adjusted && history.candles.isNotEmpty())
                }
                step("quote_websocket_ack") {
                    val received = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
                    val socket =
                        NhSocket(
                            nh,
                            { if (it.fresh(java.time.Instant.now())) received.add(it.symbol) },
                            {},
                            {},
                        )
                    try {
                        socket.connect(listOf("005930"))
                        kotlinx.coroutines.withTimeout(20_000) {
                            while ("005930" !in socket.acknowledged()) kotlinx.coroutines.delay(100)
                        }
                        step("quote_websocket_tick") {
                            kotlinx.coroutines.withTimeout(30_000) {
                                while ("005930" !in received) kotlinx.coroutines.delay(100)
                            }
                        }
                        socket.replaceSubscriptions(setOf("000660"))
                        step("quote_websocket_replace") {
                            kotlinx.coroutines.withTimeout(20_000) {
                                while (socket.acknowledged() != setOf("000660")) kotlinx.coroutines
                                    .delay(100)
                            }
                        }
                        step("quote_websocket_replaced_tick") {
                            kotlinx.coroutines.withTimeout(30_000) {
                                while ("000660" !in received) kotlinx.coroutines.delay(100)
                            }
                        }
                        socket.close()
                        check(!socket.isConnected() && socket.acknowledged().isEmpty())
                        received.clear()
                        socket.connect(listOf("005930"))
                        step("quote_websocket_reconnect") {
                            kotlinx.coroutines.withTimeout(30_000) {
                                while (
                                    "005930" !in socket.acknowledged() || "005930" !in received
                                ) kotlinx.coroutines.delay(100)
                            }
                        }
                    } finally {
                        report("quote_websocket_open", if (socket.isConnected()) "YES" else "NO")
                        socket.close()
                    }
                }
                step("shared_quote_three_leases") {
                    var created = 0
                    val hub =
                        com.sinpie.stocknhplug.application.SharedMarketStream({ q, e, d ->
                            created++
                            NhSocket(nh, q, e, d)
                        })
                    val counts = java.util.concurrent.atomic.AtomicIntegerArray(3)
                    val leases =
                        (0..2).map { index -> hub.lease({ counts.incrementAndGet(index) }, {}, {}) }
                    try {
                        leases.forEach { it.connect(listOf("005930")) }
                        kotlinx.coroutines.withTimeout(30_000) {
                            while ((0..2).any { counts.get(it) == 0 }) kotlinx.coroutines.delay(100)
                        }
                        check(created == 1)
                        leases.first().close()
                        check(
                            !leases.first().isConnected() && leases.drop(1).all { it.isConnected() }
                        )
                        val before = (0..2).map { counts.get(it) }
                        kotlinx.coroutines.withTimeout(30_000) {
                            while ((1..2).any { counts.get(it) <= before[it] }) kotlinx.coroutines
                                .delay(100)
                        }
                        check(counts.get(0) == before[0])
                    } finally {
                        leases.forEach { it.close() }
                    }
                }
            }
            val dart = credentials.optString("dart")
            if (dart.isNotBlank()) {
                step("dart_company_directory") {
                    val directory =
                        com.sinpie.stocknhplug.research.provider.DartCompanyDirectory { dart }
                    check(directory.corporation("005930") == "00126380")
                    check(directory.corporation("000660") != null)
                }
                val provider = DartClient(dart)
                val today = LocalDate.now(SEOUL)
                step("dart_disclosures") {
                    provider.disclosures("00126380", today.minusDays(7), today)
                }
                step("dart_financials") {
                    checkNotNull(provider.financials("00126380", 2025, "11011"))
                }
            } else report("dart", "NO_KEY")
        } catch (e: Exception) {
            failures++
            report("probe_setup", "FAIL_${e.javaClass.simpleName.filter { it.isLetterOrDigit() }}")
        } finally {
            transport?.client?.connectionPool?.evictAll()
            transport?.client?.dispatcher?.executorService?.shutdown()
            publicFile.delete()
            envelopeFile.delete()
            vault.deleteAll()
            val clean = vault.read("credentials") == null && vault.read("token") == null
            report("local_cleanup", if (clean) "PASS" else "FAIL")
            if (!clean) failures++
        }
        report("overall", if (failures == 0) "PASS" else "FAIL")
        // Status-only failure used to appear as a green JUnit run. Fail after cleanup, with no
        // data.
        check(failures == 0) { "Read-only probe failed; inspect sanitized stage statuses" }
    }
}

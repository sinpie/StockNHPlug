package com.sinpie.stocknhplug

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import com.sinpie.stocknhplug.data.SecureVault
import com.sinpie.stocknhplug.domain.Environment
import com.sinpie.stocknhplug.domain.SEOUL
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
 * Explicitly opted-in, read-only credential probe on a disposable debug install.
 * No order methods, service/controller startup, raw responses or exception messages.
 * RSA private key lives only in memory. Host sends an AES-GCM envelope via adb stdin.
 * This test is excluded by default and is never bundled in a distributable APK.
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
        fun report(step: String, outcome: String) {
            instrumentation.sendStatus(0, Bundle().apply { putString("probe", "$step=$outcome") })
        }
        suspend fun step(name: String, action: suspend () -> Unit): Boolean = try {
            action()
            report(name, "PASS")
            true
        } catch (e: Exception) {
            // Even a network exception can contain a credential-bearing URL: type only.
            val http = Regex("^NHPlug (?:인증|요청) 실패 \\(HTTP ([0-9]{3})\\)$")
                .matchEntire(e.message.orEmpty())?.groupValues?.get(1)
            val business = Regex("^NHPlug 업무 오류 \\(([A-Za-z0-9]{0,16})\\)$")
                .matchEntire(e.message.orEmpty())?.groupValues?.get(1)
            report(name, if (http != null) "FAIL_HTTP_$http"
                else if (business != null) "FAIL_BUSINESS_$business"
                else "FAIL_${e.javaClass.simpleName.filter { it.isLetterOrDigit() }}")
            false
        }
        try {
            val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(3072) }.generateKeyPair()
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
            rsa.init(Cipher.DECRYPT_MODE, pair.private,
                OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT))
            val key = rsa.doFinal(wrappedKey)
            val aes = Cipher.getInstance("AES/GCM/NoPadding")
            aes.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            aes.updateAAD("StockNHPlug read-only probe v1".toByteArray())
            val plain = aes.doFinal(encrypted)
            val credentials = try { JSONObject(String(plain, Charsets.UTF_8)) } finally {
                plain.fill(0); key.fill(0)
            }
            vault.write("credentials", credentials)
            check(vault.read("credentials")!!.getString("key") == credentials.getString("key"))
            report("encrypted_storage", "PASS")
            val nh = NhTransport(vault, Environment.MOCK)
            transport = nh
            val authenticated = step("nh_auth") { check(nh.token().isNotBlank()) }
            if (authenticated) {
                val broker = NhBroker(nh)
                step("mock_accounts") {
                    val accounts = broker.accounts()
                    if (accounts.isEmpty()) report("mock_account_availability", "NONE")
                    else {
                        // Account number and financial data are never included in probe output.
                        val account = accounts.first()
                        step("mock_balance") { broker.portfolio(account) }
                        step("mock_executions") { broker.executions(account, LocalDate.now(SEOUL)) }
                        step("mock_pnl") { broker.dailyPnl(account) }
                    }
                }
                step("current_price_response") {
                    val response = nh.call("/krstock/quote/v1/currentPrice",
                        JSONObject().put("iem_cd", "005930").put("market_cd", "KRX"))
                    check(response.getJSONObject("Output_0").getString("iem_cd") == "005930")
                    val parsed = runCatching {
                        NhCurrentPriceProvider.parse("005930", response, java.time.Instant.now())
                    }
                    report("current_price_validation", if (parsed.isSuccess) "PASS" else "REJECTED")
                }
                step("price_history") {
                    val history = NhPriceHistoryProvider(nh).history("005930")
                    check(!history.adjusted && history.candles.isNotEmpty())
                }
                step("quote_websocket_ack") {
                    val socket = NhSocket(nh, {}, {}, {})
                    try {
                        socket.connect(listOf("005930"))
                        kotlinx.coroutines.withTimeout(20_000) {
                            while ("005930" !in socket.acknowledged()) kotlinx.coroutines.delay(100)
                        }
                    } finally {
                        report("quote_websocket_open", if (socket.isConnected()) "YES" else "NO")
                        socket.close()
                    }
                }
            }
            val dart = credentials.optString("dart")
            if (dart.isNotBlank()) {
                val provider = DartClient(dart)
                val today = LocalDate.now(SEOUL)
                step("dart_disclosures") { provider.disclosures("00126380", today.minusDays(7), today) }
                step("dart_financials") { checkNotNull(provider.financials("00126380", 2025, "11011")) }
            } else report("dart", "NO_KEY")
        } catch (e: Exception) {
            report("probe_setup", "FAIL_${e.javaClass.simpleName.filter { it.isLetterOrDigit() }}")
        } finally {
            transport?.client?.connectionPool?.evictAll()
            transport?.client?.dispatcher?.executorService?.shutdown()
            publicFile.delete()
            envelopeFile.delete()
            vault.deleteAll()
            report("local_cleanup", if (vault.read("credentials") == null && vault.read("token") == null) "PASS" else "FAIL")
        }
    }
}

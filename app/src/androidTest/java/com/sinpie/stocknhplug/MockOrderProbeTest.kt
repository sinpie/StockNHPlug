package com.sinpie.stocknhplug

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import com.sinpie.stocknhplug.data.SecureVault
import com.sinpie.stocknhplug.domain.Environment
import com.sinpie.stocknhplug.domain.SEOUL
import com.sinpie.stocknhplug.domain.Side
import com.sinpie.stocknhplug.execution.NhBroker
import com.sinpie.stocknhplug.infrastructure.nh.NhTransport
import com.sinpie.stocknhplug.marketdata.NhCurrentPriceProvider
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
 * Explicit opt-in, mock-only protocol test. No strategy startup or production gate changes. One
 * nonmarketable one-share buy and one cancellation; journal before each dispatch. A
 * nonterminal/unknown result retains encrypted evidence and prevents automatic reruns.
 */
class MockOrderProbeTest {
    @Test
    fun isolatedLimitAndCancel(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("mockOrderProbe") == "1")
        check(args.getString("authorizedDate") == LocalDate.now(SEOUL).toString())
        val context = instrumentation.targetContext
        check(context.packageName == "com.sinpie.stocknhplug.debug")
        val vault = SecureVault(context)
        check(context.noBackupFilesDir.listFiles().orEmpty().none { it.extension == "enc" })
        val publicFile = File(context.cacheDir, "probe-public.der")
        val envelopeFile = File(context.cacheDir, "probe-envelope.bin")
        check(!publicFile.exists() && !envelopeFile.exists())
        var transport: NhTransport? = null
        var retain = false
        var passed = false
        fun report(name: String, result: String) {
            instrumentation.sendStatus(0, Bundle().apply { putString("probe", "$name=$result") })
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
            val nh = NhTransport(vault, Environment.MOCK)
            transport = nh
            val buyPath = "/krstock/order/v1/cashBuy"
            val cancelPath = "/krstock/order/v1/cancel"
            for (path in listOf(buyPath, cancelPath)) {
                check(
                    com.sinpie.stocknhplug.infrastructure.nh.NhEndpoints.base(
                        Environment.MOCK,
                        path,
                    ) == "https://moapi.nhplug.com:8443"
                )
            }
            check(nh.environment == Environment.MOCK)
            val now = java.time.ZonedDateTime.now(SEOUL)
            check(now.dayOfWeek.value in 1..5 && now.hour in 9..14)
            val broker = NhBroker(nh)
            val accounts = broker.accounts()
            check(accounts.isNotEmpty())
            kotlinx.coroutines.delay(1_500)
            val snapshot = NhCurrentPriceProvider(nh).current("005930")
            val price = snapshot.rules.lower
            check(snapshot.valid(java.time.Instant.now()))
            check(price in 1..1_000_000 && price < snapshot.quote.bid * 0.9)
            var account: com.sinpie.stocknhplug.domain.Account? = null
            for ((index, candidate) in accounts.withIndex()) {
                kotlinx.coroutines.delay(1_500)
                val holdings = broker.portfolio(candidate)
                kotlinx.coroutines.delay(1_500)
                if (
                    holdings.holdings.isEmpty() &&
                        broker.executions(candidate, LocalDate.now(SEOUL)).isEmpty()
                ) {
                    kotlinx.coroutines.delay(1_500)
                    val available = broker.available(candidate, "005930", Side.BUY, price)
                    report("candidate_${index + 1}_buyable", if (available >= 1) "YES" else "NO")
                    if (available >= 1) {
                        account = candidate
                        break
                    }
                }
            }
            report("eligible_mock_account", if (account != null) "YES" else "NO")
            val selected = checkNotNull(account)
            check(selected.environment == Environment.MOCK && selected.brokerId == "nhplug")
            report("mock_account_isolated", "PASS")
            report("preflight", "PASS")
            val journal =
                JSONObject()
                    .put("environment", "MOCK")
                    .put("account", selected.number)
                    .put("symbol", "005930")
                    .put("quantity", 1)
                    .put("price", price)
                    .put("date", LocalDate.now(SEOUL).toString())
            fun record(phase: String) {
                vault.write("mock_order_probe", journal.put("phase", phase))
            }
            record("PREPARED")
            kotlinx.coroutines.delay(1_500)
            // Refresh immediately before dispatch; never turn a stale quote into permission.
            val fresh = NhCurrentPriceProvider(nh).current("005930")
            check(
                fresh.valid(java.time.Instant.now()) &&
                    price == fresh.rules.lower &&
                    price < fresh.quote.bid * 0.9
            )
            val input =
                JSONObject()
                    .put("act_no", selected.number)
                    .put("iem_cd", "005930")
                    .put("orr_qty", 1)
                    .put("orr_pr", price)
                    .put("orr_amt", price)
                    .put("nmn_pr_tp_cd", "01")
                    .put("orr_cnd_dit_cd", "00")
                    .put("ssl_nmn_pr_dit_cd", "00")
                    .put("rmt_mkt_cd", "KRX")
                    .put("sor_mkt_sli_yn", "N")
            record("SUBMITTING")
            retain = true
            report("buy_dispatch", "STARTED")
            val buy =
                nh.pages(
                        buyPath,
                        input,
                        dispatchGuard = { check(fresh.valid(java.time.Instant.now())) },
                    )
                    .single()
            val marketNumber = buy.getJSONObject("Output_0").getLong("mkt_orr_no")
            check(marketNumber > 0)
            journal.put("marketNumber", marketNumber)
            record("ACCEPTED_NOT_FILLED")
            report("buy_accepted", "PASS")
            kotlinx.coroutines.delay(1_500)
            record("CANCEL_SUBMITTING")
            val cancel =
                nh.call(
                    cancelPath,
                    JSONObject()
                        .put("act_no", selected.number)
                        .put("iem_cd", "005930")
                        .put("org_mkt_orr_no", marketNumber)
                        .put("all_pat_dit_cd", "1"),
                )
            check(cancel.getJSONObject("Output_0").getLong("mkt_orr_no") > 0)
            record("CANCEL_ACCEPTED")
            report("cancel_accepted", "PASS")
            // Only read-only polling after cancellation; never repeat a buy/cancel write.
            var terminal = false
            repeat(5) {
                if (!terminal) {
                    kotlinx.coroutines.delay(2_000)
                    val pages =
                        nh.pages(
                            "/krstock/inquiry/v1/dailyOrderExecution",
                            JSONObject()
                                .put("act_no", selected.number)
                                .put("orr_dt", LocalDate.now(SEOUL).toString().replace("-", ""))
                                .put("orr_mkt_cd", "00")
                                .put("ost_cns_dit", "0"),
                            true,
                        )
                    val rows =
                        pages.flatMap(com.sinpie.stocknhplug.execution.NhExecutionParser::rows)
                    val executions =
                        pages.flatMap(com.sinpie.stocknhplug.execution.NhExecutionParser::parse)
                    // Account-wide isolated evidence, NOT a guessed market/integrated ID mapping.
                    terminal =
                        rows.size == 1 &&
                            executions.single().let {
                                it.symbol == "005930" &&
                                    it.ordered == 1L &&
                                    it.filled == 0L &&
                                    it.remaining == 0L
                            } &&
                            rows.single().getLong("can_qty") == 1L
                }
            }
            check(terminal)
            kotlinx.coroutines.delay(1_500)
            check(broker.portfolio(selected).holdings.isEmpty())
            record("VERIFIED_ACCOUNT_TERMINAL")
            retain = false
            report("cancelled_no_fill_no_holdings", "PASS")
            report("order_id_mapping", "NOT_PROVEN")
            passed = true
        } catch (e: Exception) {
            // Only static source locations, never exception messages or broker payloads.
            e.stackTrace
                .firstOrNull { it.className.startsWith("com.sinpie.stocknhplug.") }
                ?.let { report("failure_source", "${it.fileName}:${it.lineNumber}") }
            val safeHttp = Regex("HTTP ([0-9]{3})").find(e.message.orEmpty())?.groupValues?.get(1)
            report("failure", if (safeHttp != null) "HTTP_$safeHttp" else e.javaClass.simpleName)
        } finally {
            transport?.client?.connectionPool?.evictAll()
            transport?.client?.dispatcher?.executorService?.shutdown()
            publicFile.delete()
            envelopeFile.delete()
            report("retain_install", if (retain) "YES" else "NO")
            if (!retain) {
                vault.deleteAll()
                report("local_cleanup", "PASS")
            }
        }
        report("overall", if (passed) "PASS" else "FAIL")
        check(passed) {
            "Inspect sanitized mock probe statuses; never automatically retry an order"
        }
    }
}

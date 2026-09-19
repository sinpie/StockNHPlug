package com.sinpie.stocknhplug

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sinpie.stocknhplug.data.SecureVault
import java.io.File
import org.json.JSONObject
import org.junit.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VaultInstrumentedTest {
    @Test
    fun roundTripCiphertextAndDeletion() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val vault = SecureVault(context)
        try {
            vault.write("test", JSONObject().put("value", "TEST_ONLY_SECRET"))
            Assert.assertEquals("TEST_ONLY_SECRET", vault.read("test")!!.getString("value"))
            Assert.assertFalse(
                File(context.noBackupFilesDir, "test.enc").readText().contains("TEST_ONLY_SECRET")
            )
            vault.delete("test")
            Assert.assertNull(vault.read("test"))
        } finally {
            vault.delete("test")
        }
    }

    @Test
    fun tamperedCiphertextFailsClosed() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val vault = SecureVault(context)
        try {
            vault.write("test", JSONObject().put("value", "test"))
            val file = File(context.noBackupFilesDir, "test.enc")
            val bytes = file.readBytes()
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
            file.writeBytes(bytes)
            Assert.assertTrue(runCatching { vault.read("test") }.isFailure)
        } finally {
            vault.delete("test")
        }
    }
}

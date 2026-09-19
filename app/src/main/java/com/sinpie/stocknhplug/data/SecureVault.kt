package com.sinpie.stocknhplug.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Android Keystore key never leaves the device. Payloads are versioned, authenticated and atomic. */
class SecureVault(context: Context) {
    private val directory = context.noBackupFilesDir
    private val alias = "stocknhplug.local.v1"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }
    @Synchronized fun write(name: String, payload: JSONObject) {
        require(name.matches(Regex("[a-z]+")))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key()); cipher.updateAAD(name.toByteArray())
        val plain = payload.toString().toByteArray(Charsets.UTF_8)
        val encoded = try { byteArrayOf(1) + cipher.iv + cipher.doFinal(plain) } finally { plain.fill(0) }
        val file = AtomicFile(File(directory, "$name.enc"))
        val stream = file.startWrite()
        try { stream.write(encoded); file.finishWrite(stream) } catch (e: Exception) { file.failWrite(stream); throw e }
    }
    @Synchronized fun read(name: String): JSONObject? {
        require(name.matches(Regex("[a-z]+")))
        val file = AtomicFile(File(directory, "$name.enc"))
        if (!file.baseFile.exists() && !File(directory, "$name.enc.bak").exists()) return null
        val encoded = file.readFully()
        check(encoded.size >= 30 && encoded[0] == 1.toByte()) { "암호화 저장소 형식 오류" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, encoded.copyOfRange(1, 13)))
        cipher.updateAAD(name.toByteArray())
        val plain = cipher.doFinal(encoded, 13, encoded.size - 13)
        return try { JSONObject(String(plain, Charsets.UTF_8)) } finally { plain.fill(0) }
    }
    @Synchronized fun delete(name: String) { AtomicFile(File(directory, "$name.enc")).delete() }
    @Synchronized fun deleteAll() {
        listOf("credentials", "token", "journal", "settings", "research", "snapshot", "events").forEach(::delete)
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
    }
}

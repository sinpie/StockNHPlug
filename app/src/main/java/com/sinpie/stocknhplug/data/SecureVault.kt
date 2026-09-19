package com.sinpie.stocknhplug.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

/**
 * Android Keystore key never leaves the device. Payloads are versioned, authenticated and atomic.
 */
class SecureVault(context: Context) {
    private val directory = context.noBackupFilesDir
    private val alias = "stocknhplug.local.v1"

    /** AndroidKeyStore에서 비추출 AES 키를 가져오거나 최초 생성한다. 비밀번호 입력값을 암호화 키로 사용하지 않는다. */
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let {
            return it
        }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            .apply {
                init(
                    KeyGenParameterSpec.Builder(
                            alias,
                            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                        )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .setRandomizedEncryptionRequired(true)
                        .build()
                )
            }
            .generateKey()
    }

    /** 새 IV와 파일명 AAD로 인증 암호화하고 AtomicFile로 교체한다. 쓰기 실패는 이전 파일을 보존한다. */
    @Synchronized
    fun write(name: String, payload: JSONObject) {
        require(name.matches(Regex("[a-z]+")))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(name.toByteArray())
        val plain = payload.toString().toByteArray(Charsets.UTF_8)
        val encoded =
            try {
                byteArrayOf(1) + cipher.iv + cipher.doFinal(plain)
            } finally {
                plain.fill(0)
            }
        val file = AtomicFile(File(directory, "$name.enc"))
        val stream = file.startWrite()
        try {
            stream.write(encoded)
            file.finishWrite(stream)
        } catch (e: Exception) {
            file.failWrite(stream)
            throw e
        }
    }

    /** 버전과 GCM 태그를 검증한 뒤 JSON을 반환한다. 변조/키 유실은 예외이며 null은 파일 부재에만 사용한다. */
    @Synchronized
    fun read(name: String): JSONObject? {
        require(name.matches(Regex("[a-z]+")))
        val file = AtomicFile(File(directory, "$name.enc"))
        if (!file.baseFile.exists() && !File(directory, "$name.enc.bak").exists()) return null
        val encoded = file.readFully()
        check(encoded.size >= 30 && encoded[0] == 1.toByte()) { "암호화 저장소 형식 오류" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, encoded.copyOfRange(1, 13)))
        cipher.updateAAD(name.toByteArray())
        val plain = cipher.doFinal(encoded, 13, encoded.size - 13)
        return try {
            JSONObject(String(plain, Charsets.UTF_8))
        } finally {
            plain.fill(0)
        }
    }

    /** 지정한 로컬 암호문과 AtomicFile 백업을 삭제한다. 서버 토큰 폐기 요청은 아니다. */
    @Synchronized
    fun delete(name: String) {
        AtomicFile(File(directory, "$name.enc")).delete()
    }

    /** 앱 소유 데이터와 Keystore 키를 함께 제거한다. 호출 전에 모든 작업이 끝나야 한다. */
    @Synchronized
    fun deleteAll() {
        listOf(
                "credentials",
                "token",
                "journal",
                "settings",
                "research",
                "snapshot",
                "events",
                "groupbook",
                "groupfills",
            )
            .forEach(::delete)
        KeyStore.getInstance("AndroidKeyStore").apply {
            load(null)
            deleteEntry(alias)
        }
    }
}

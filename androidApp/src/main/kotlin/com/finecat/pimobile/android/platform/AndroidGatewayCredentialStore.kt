package com.finecat.pimobile.android.platform

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.finecat.pimobile.shared.platform.GatewayCredentialStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.ByteBuffer
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * token/device id 先由 Android Keystore AES-GCM 加密，再写入独立的 no-backup DataStore。
 * 普通配置 DataStore 永远不会出现敏感明文。
 */
class AndroidGatewayCredentialStore(context: Context) : GatewayCredentialStore {
    private val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
        // 文件写坏（截断/垃圾字节）时从空恢复并走重配对，与偏好存储的 IOException 兜底一致；
        // 不抛 CorruptionException，避免每次连接撞同一坏文件只能清数据。
        corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
        produceFile = { context.noBackupFilesDir.resolve(CREDENTIAL_FILE_NAME) }
    )
    private val mutex = Mutex()

    override suspend fun loadOrCreateDeviceId(): String = mutex.withLock {
        when (val read = readEncrypted(DEVICE_ID_KEY, DEVICE_ID_AAD)) {
            // 瞬时不可读（Keystore 抖动）宁可大声失败，也不生成新 ID 漂移安装身份；
            // 但密钥是新建的（旧密钥已丢）则旧密文必然永远解不开，等价缺失：删了重建。
            is CredentialRead.Unreadable -> error("device id temporarily unreadable; keeping ciphertext")
            is CredentialRead.Value -> read.text.takeIf(String::isNotBlank)?.let { return it }
            is CredentialRead.Absent -> Unit
        }
        val value = UUID.randomUUID().toString().lowercase()
        writeEncrypted(DEVICE_ID_KEY, DEVICE_ID_AAD, value)
        value
    }

    override suspend fun loadToken(): String? = mutex.withLock {
        // 不可读只报 null（不断密文）：401 路径本就会处理，比强制重扫码温和。
        when (val read = readEncrypted(TOKEN_KEY, TOKEN_AAD)) {
            is CredentialRead.Value -> read.text.takeIf(String::isNotBlank)?.let { return it }
            else -> Unit
        }
        migrateLegacyToken()
    }

    override suspend fun saveToken(token: String) {
        require(token.isNotBlank()) { "token must not be blank" }
        mutex.withLock { writeEncrypted(TOKEN_KEY, TOKEN_AAD, token) }
    }

    override suspend fun deleteToken() {
        mutex.withLock {
            val legacyNames = legacyTokenKeys()
            dataStore.edit { preferences ->
                preferences.remove(TOKEN_KEY)
                legacyNames.forEach { preferences.remove(stringPreferencesKey(it)) }
            }
        }
    }

    /**
     * 旧版本按 endpoint 哈希分存 token，升级后归并到单一条目：token 是设备级的，
     * 按地址分存只会让「换个入口」变成「没配对」。取末尾条目是因为重新配对会轮换
     * token，先写入的那几条已失效。
     */
    private suspend fun migrateLegacyToken(): String? {
        val legacyNames = legacyTokenKeys()
        if (legacyNames.isEmpty()) return null
        // 先读后删：解密/写新失败不丢可用 token，旧条目仍在可下次再迁（幂等）。
        // 不依赖 keys 迭代序：逐个试解密，首个可用即收（服务端 401 会自然淘汰旧 token）。
        // 全失败时只清确定性损坏（解析形状错）的条目；解密失败的保留待下次再迁。
        var token: String? = null
        val corruptNames = mutableListOf<String>()
        for (name in legacyNames) {
            val ciphertext = runCatching { dataStore.data.first()[stringPreferencesKey(name)] }.getOrNull()
                ?: continue
            val parsed = runCatching { parseCiphertext(ciphertext) }.getOrNull()
            if (parsed == null) {
                corruptNames.add(name)
                continue
            }
            token = runCatching { decryptParsed(parsed, name) }.getOrNull()?.takeIf(String::isNotBlank)
            if (token != null) break
        }
        if (token == null) {
            if (corruptNames.isNotEmpty()) {
                dataStore.edit { preferences ->
                    corruptNames.forEach { preferences.remove(stringPreferencesKey(it)) }
                }
            }
            return null
        }
        // 先落新盘再清理：中间杀进程旧条目仍在，下次再迁。
        writeEncrypted(TOKEN_KEY, TOKEN_AAD, token)
        dataStore.edit { preferences ->
            legacyNames.forEach { preferences.remove(stringPreferencesKey(it)) }
        }
        return token
    }

    private suspend fun legacyTokenKeys(): List<String> = runCatching {
        dataStore.data.first().asMap().keys.map(Preferences.Key<*>::name)
            .filter { it.startsWith(LEGACY_TOKEN_PREFIX) }
    }.getOrDefault(emptyList())

    private suspend fun readEncrypted(key: Preferences.Key<String>, aad: String): CredentialRead {
        val encoded = dataStore.data.first()[key] ?: return CredentialRead.Absent
        // 先解析：Base64/版本/iv 形状错误是确定性损坏，解密永远不会成功 → 删密文 fail closed。
        val parsed = runCatching { parseCiphertext(encoded) }.getOrElse {
            dataStore.edit { preferences -> preferences.remove(key) }
            return CredentialRead.Absent
        }
        // 解密失败时区分两种情况：解密前已确认密钥不存在，说明旧密钥已丢、
        // 本次是新建密钥——同一把密钥加密的整库密文必然永远解不开 → 全清按缺失
        // 处理（调用方重建；先跑 loadToken 还是先读 deviceId 都收敛）；
        // 密钥仍在或查不到（Keystore 本身不可用）→ 保留密文，仅报不可读。
        val keyDefinitelyMissing = keyExists() == false
        return runCatching { decryptParsed(parsed, aad) }.fold(
            onSuccess = { CredentialRead.Value(it) },
            onFailure = {
                if (keyDefinitelyMissing) {
                    clearStaleCredentials()
                    CredentialRead.Absent
                } else {
                    CredentialRead.Unreadable
                }
            }
        )
    }

    /**
     * 密钥已确认丢失：整库密文（deviceId/token）都是旧密钥加密的死数据，一并清除。
     * legacy 条目由迁移/401→deleteToken 路径清理，此处不动。
     */
    private suspend fun clearStaleCredentials() {
        dataStore.edit { preferences ->
            preferences.remove(DEVICE_ID_KEY)
            preferences.remove(TOKEN_KEY)
        }
    }

    private suspend fun writeEncrypted(key: Preferences.Key<String>, aad: String, value: String) {
        val encrypted = encrypt(value, aad)
        dataStore.edit { preferences -> preferences[key] = encrypted }
    }

    private fun encrypt(value: String, aad: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, loadOrCreateKey())
        cipher.updateAAD(aad.toByteArray(Charsets.UTF_8))
        val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val payload = ByteBuffer.allocate(2 + cipher.iv.size + ciphertext.size)
            .put(VERSION)
            .put(cipher.iv.size.toByte())
            .put(cipher.iv)
            .put(ciphertext)
            .array()
        return Base64.encodeToString(payload, Base64.NO_WRAP)
    }

    /** 确定性解析：形状错误说明密文已坏，解密不可能成功。 */
    private fun parseCiphertext(encoded: String): ParsedCiphertext {
        val buffer = ByteBuffer.wrap(Base64.decode(encoded, Base64.NO_WRAP))
        require(buffer.get() == VERSION) { "unsupported credential ciphertext version" }
        val ivLength = buffer.get().toInt() and 0xff
        require(ivLength in 12..32 && buffer.remaining() > ivLength) { "invalid credential ciphertext" }
        val iv = ByteArray(ivLength).also(buffer::get)
        val ciphertext = ByteArray(buffer.remaining()).also(buffer::get)
        return ParsedCiphertext(iv, ciphertext)
    }

    private fun decryptParsed(parsed: ParsedCiphertext, aad: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, loadOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, parsed.iv))
        cipher.updateAAD(aad.toByteArray(Charsets.UTF_8))
        return cipher.doFinal(parsed.ciphertext).toString(Charsets.UTF_8)
    }

    private sealed interface CredentialRead {
        data object Absent : CredentialRead
        data object Unreadable : CredentialRead
        data class Value(val text: String) : CredentialRead
    }

    private data class ParsedCiphertext(val iv: ByteArray, val ciphertext: ByteArray)

    /** 查密钥是否存在：查不到（Keystore 本身不可用）返回 null，调用方按保留密文处理。 */
    private fun keyExists(): Boolean? = runCatching {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        keyStore.getKey(KEY_ALIAS, null) is SecretKey
    }.getOrNull()

    private fun loadOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setUserAuthenticationRequired(false)
                .build()
        )
        return generator.generateKey()
    }

    companion object {
        private const val CREDENTIAL_FILE_NAME = "gateway_credentials.preferences_pb"
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEY_ALIAS = "pi_gateway_credentials_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val DEVICE_ID_AAD = "gateway_device_id"
        private val DEVICE_ID_KEY = stringPreferencesKey(DEVICE_ID_AAD)
        private const val TOKEN_AAD = "gateway_token"
        private val TOKEN_KEY = stringPreferencesKey(TOKEN_AAD)
        /** 旧版本按 endpoint 哈希分存时的键前缀，仅用于升级迁移。 */
        private const val LEGACY_TOKEN_PREFIX = "gateway_token_"
        private const val VERSION: Byte = 1
    }
}

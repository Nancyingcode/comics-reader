package io.github.kai.reader

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import okhttp3.HttpUrl

internal class RemoteAccounts(private val directory: File) {
    fun put(account: RemoteAccount): RemoteLocation {
        val id = remoteDigest("${account.root}\n${account.username}")
        val destination = File(directory, id)
        try {
            val cipher = Cipher.getInstance(CIPHER).apply {
                init(Cipher.ENCRYPT_MODE, key())
                updateAAD(id.toByteArray(Charsets.US_ASCII))
            }
            val accountBytes = ByteArrayOutputStream().use { bytes ->
                DataOutputStream(bytes).apply {
                    writeUTF(account.root.toString())
                    writeUTF(account.username)
                    writeUTF(account.password)
                }
                bytes.toByteArray()
            }
            writeRemoteAtomic(destination) { output ->
                output.writeByte(cipher.iv.size)
                output.write(cipher.iv)
                output.write(cipher.doFinal(accountBytes))
            }
        } catch (failure: GeneralSecurityException) {
            throw IOException("无法在此设备上保存 WebDAV 凭据。", failure)
        }
        return RemoteLocation(account.root, id)
    }

    fun forLocation(location: RemoteLocation): RemoteAccount? {
        val id = location.accountId ?: return null
        if (!id.isRemoteDigest()) throw IOException("WebDAV 连接无效，请重新连接。")
        val source = File(directory, id)
        if (!source.isFile) throw IOException("WebDAV 登录信息不可用，请重新连接。")
        try {
            val account = DataInputStream(source.inputStream().buffered()).use { input ->
                val ivSize = input.readUnsignedByte()
                if (ivSize !in 12..16) throw IOException("WebDAV 登录信息不可用，请重新连接。")
                val iv = ByteArray(ivSize).also(input::readFully)
                val cipher = Cipher.getInstance(CIPHER).apply {
                    init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
                    updateAAD(id.toByteArray(Charsets.US_ASCII))
                }
                val bytes = cipher.doFinal(input.readBytes())
                DataInputStream(ByteArrayInputStream(bytes)).use { data ->
                    RemoteAccount(parseRemoteUrl(data.readUTF(), allowHttp = true), data.readUTF(), data.readUTF())
                }
            }
            if (!account.permits(location.url)) throw IOException("远程目录超出当前 WebDAV 连接范围。")
            return account
        } catch (failure: GeneralSecurityException) {
            throw IOException("WebDAV 登录信息不可用，请重新连接。", failure)
        }
    }

    private fun key(): SecretKey = synchronized(keyLock) {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_NAME, null) as? SecretKey)?.let { return it }
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_NAME, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
        }.generateKey()
    }

    private companion object {
        const val KEY_NAME = "kai-webdav-account-key"
        const val CIPHER = "AES/GCM/NoPadding"
        val keyLock = Any()
    }
}

internal fun webDavRoot(server: String, rootPath: String): HttpUrl {
    val base = parseRemoteUrl(server, allowHttp = true)
    if (base.query != null || !base.safePath()) throw IOException("请输入有效的 WebDAV 服务地址。")
    val path = rootPath.trim()
    if (path.isEmpty()) return base.asRemoteFolder()
    val invalidRoot = path.startsWith("//") || path.any { it == '?' || it == '#' || it == '\\' || it.isISOControl() }
    if (invalidRoot) throw IOException("请输入有效的 WebDAV 根目录。")
    val root = base.asRemoteFolder().resolve(path)
    if (root == null || !root.isSameOrigin(base) || !root.safePath()) throw IOException("请输入有效的 WebDAV 根目录。")
    return root.asRemoteFolder()
}

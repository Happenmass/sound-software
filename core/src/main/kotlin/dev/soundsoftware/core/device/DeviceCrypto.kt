package dev.soundsoftware.core.device

import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.KeyPairGenerator
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Boolean-only probe: never stores bytes, hashes, keys, nonces or public-key coordinates. */
data class CryptoHandshakeDiagnostic(
    val publicKeyBytes: Int,
    val checkBytes: Int,
    val sharedBytes: Int,
    val matchesRaw: Boolean,
)

/** Session-scoped secrets never appear in diagnostic output or data-class toString. */
class DeviceCrypto : AutoCloseable {
    private val keyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private var sessionKey: ByteArray? = null
    fun publicKey(): ByteArray {
        val key = keyPair.public as ECPublicKey
        fun coordinate(n: BigInteger) = n.toByteArray().takeLast(32).toByteArray().let { ByteArray(32 - it.size) + it }
        return byteArrayOf(4) + coordinate(key.w.affineX) + coordinate(key.w.affineY)
    }
    fun establish(devicePublic: ByteArray, deviceSharedCheck: ByteArray,
        onDiagnostic: (CryptoHandshakeDiagnostic) -> Unit = {}) {
        sessionKey?.fill(0); sessionKey = null
        require(devicePublic.size == 65 && devicePublic[0] == 4.toByte() && deviceSharedCheck.size == 32)
        val point = ECPoint(BigInteger(1, devicePublic.copyOfRange(1, 33)), BigInteger(1, devicePublic.copyOfRange(33, 65)))
        val peer = KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, (keyPair.public as ECPublicKey).params))
        val shared = KeyAgreement.getInstance("ECDH").run { init(keyPair.private); doPhase(peer, true); generateSecret() }
        try {
            val matches = MessageDigest.isEqual(shared, deviceSharedCheck)
            onDiagnostic(CryptoHandshakeDiagnostic(devicePublic.size, deviceSharedCheck.size, shared.size, matches))
            require(matches) { "设备密钥握手校验失败" }
            sessionKey = hkdf(shared)
        } finally { shared.fill(0) }
    }

    fun fileKey(encrypted: ByteArray, sessionNonce: ByteArray): ByteArray {
        require(encrypted.size == 46 && sessionNonce.size == 16)
        val key = checkNotNull(sessionKey) { "尚未完成密钥握手" }
        val plain = ctr(key, sessionNonce, encrypted)
        try {
            require(plain.copyOfRange(0, 14).contentEquals("soundcored3200".toByteArray())) { "文件密钥格式校验失败" }
            return plain.copyOfRange(14, 46)
        } finally { plain.fill(0) }
    }
    override fun close() {
        sessionKey?.fill(0); sessionKey = null
        // Provider-owned key storage may not support explicit destruction; never claim secure erasure.
        runCatching { keyPair.private.destroy() }
    }
    companion object {
        internal fun hkdf(shared: ByteArray): ByteArray {
            val marker = byteArrayOf(1, 2, 3)
            fun hmac(key: ByteArray, data: ByteArray) = Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(data) }
            val prk = hmac(marker, shared)
            return try { hmac(prk, marker + byteArrayOf(1)) } finally { prk.fill(0) }
        }
        internal fun ctr(key: ByteArray, nonce: ByteArray, data: ByteArray): ByteArray {
            require(key.size == 32 && nonce.size == 16)
            return Cipher.getInstance("AES/CTR/NoPadding").run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(nonce)); doFinal(data)
            }
        }
        fun decryptSlice(key: ByteArray, nonce: ByteArray, sequence: Long, data: ByteArray): ByteArray {
            require(nonce.size == 16 && data.size == 160 && sequence in 0..0xffffffffL / 10)
            val iv = nonce.copyOfRange(0, 12) + ByteBuffer.allocate(4).putInt((sequence * 10).toInt()).array()
            return try { ctr(key, iv, data) } finally { iv.fill(0) }
        }
    }
}

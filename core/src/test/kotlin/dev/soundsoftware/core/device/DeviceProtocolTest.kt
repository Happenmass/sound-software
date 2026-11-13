package dev.soundsoftware.core.device

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class DeviceProtocolTest {
    private fun reply(payload: ByteArray = byteArrayOf(10, 20)): ByteArray =
        D3200Protocol.command(1, 3, payload).also {
            it[0] = 9; it[1] = 0xff.toByte(); it[4] = 1
            it[it.lastIndex] = D3200Protocol.checksum(it, it.lastIndex).toByte()
        }
    @Test fun splitAndCoalescedFramesSurviveEveryBoundary() {
        val frame = reply()
        for (split in 0..frame.size) {
            val decoder = DeviceFrameDecoder()
            val parsed = decoder.accept(frame.copyOfRange(0, split)) + decoder.accept(frame.copyOfRange(split, frame.size) + frame)
            assertEquals(2, parsed.size); assertTrue(parsed.all { it.successful && it.payload.contentEquals(byteArrayOf(10, 20)) })
        }
    }
    @Test fun garbageAndBadChecksumCannotBecomeValidData() {
        val broken = reply().also { it[9] = 33 }
        val parsed = DeviceFrameDecoder().accept(byteArrayOf(0, 9, 1) + broken + reply())
        assertEquals(1, parsed.size)
    }
    @Test fun rejectsUnknownSliceLayout() {
        assertThrows(IllegalArgumentException::class.java) { DevicePayloads.slices(ByteArray(165)) }
    }
    @Test fun requestUsesLittleEndianAndRetainsOffset() {
        assertArrayEquals(byteArrayOf(0x60, 0, 0, 0, 0x78, 0x56, 0x34, 0x12, 0), D3200Protocol.fileRequest(0x12345678, 96))
    }
    @Test fun missingAndDuplicateSlicesAreDetected() {
        val f = File.createTempFile("slice-test", ".raw")
        try {
            SliceCollector(f, 320).use {
                it.accept(1, ByteArray(160) { 2 }); assertEquals(0L, it.contiguousBytes)
                assertThrows(IllegalStateException::class.java) { it.finish() }
                it.accept(0, ByteArray(160) { 1 }); it.accept(0, ByteArray(160) { 1 })
                assertThrows(IllegalArgumentException::class.java) { it.accept(0, ByteArray(160) { 3 }) }
                it.finish(); assertEquals(320L, f.length())
            }
        } finally { f.delete() }
    }
    @Test fun aesCtrIsSequenceDependentAndReversible() {
        val key = ByteArray(32) { it.toByte() }; val nonce = ByteArray(16) { it.toByte() }; val data = ByteArray(160) { 7 }
        val encrypted = DeviceCrypto.decryptSlice(key, nonce, 2, data)
        assertArrayEquals(data, DeviceCrypto.decryptSlice(key, nonce, 2, encrypted))
        assertFalse(data.contentEquals(DeviceCrypto.decryptSlice(key, nonce, 3, encrypted)))
    }
    @Test fun ecdhRejectsMismatchedDeviceSecret() {
        DeviceCrypto().use { session ->
            val peer = java.security.KeyPairGenerator.getInstance("EC").apply {
                initialize(java.security.spec.ECGenParameterSpec("secp256r1"))
            }.generateKeyPair()
            val pub = peer.public as java.security.interfaces.ECPublicKey
            fun coordinate(value: java.math.BigInteger) = value.toByteArray().takeLast(32).toByteArray().let { ByteArray(32 - it.size) + it }
            val public = byteArrayOf(4) + coordinate(pub.w.affineX) + coordinate(pub.w.affineY)
            var diagnostic: CryptoHandshakeDiagnostic? = null
            assertThrows(IllegalArgumentException::class.java) { session.establish(public, ByteArray(32)) { diagnostic = it } }
            assertFalse(requireNotNull(diagnostic).matchesRaw)
            assertThrows(IllegalStateException::class.java) { session.fileKey(ByteArray(46), ByteArray(16)) }
        }
    }

    @Test fun hashedCheckDoesNotBypassRawHandshakeValidation() {
        DeviceCrypto().use { session ->
            val peer = java.security.KeyPairGenerator.getInstance("EC").apply {
                initialize(java.security.spec.ECGenParameterSpec("secp256r1"))
            }.generateKeyPair()
            val pub = peer.public as java.security.interfaces.ECPublicKey
            fun coordinate(value: java.math.BigInteger) = value.toByteArray().takeLast(32).toByteArray().let { ByteArray(32 - it.size) + it }
            val public = byteArrayOf(4) + coordinate(pub.w.affineX) + coordinate(pub.w.affineY)
            val appWire = session.publicKey()
            val appPublic = java.security.KeyFactory.getInstance("EC").generatePublic(java.security.spec.ECPublicKeySpec(
                java.security.spec.ECPoint(java.math.BigInteger(1, appWire.copyOfRange(1, 33)), java.math.BigInteger(1, appWire.copyOfRange(33, 65))), pub.params))
            val shared = javax.crypto.KeyAgreement.getInstance("ECDH").run { init(peer.private); doPhase(appPublic, true); generateSecret() }
            val hash = java.security.MessageDigest.getInstance("SHA-256").digest(shared)
            var diagnostic: CryptoHandshakeDiagnostic? = null
            assertThrows(IllegalArgumentException::class.java) { session.establish(public, hash) { diagnostic = it } }
            assertFalse(requireNotNull(diagnostic).matchesRaw)
            assertThrows(IllegalStateException::class.java) { session.fileKey(ByteArray(46), ByteArray(16)) }
            shared.fill(0); hash.fill(0)
        }
    }
}

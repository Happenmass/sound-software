package dev.soundsoftware.core.device

import java.io.File
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.KeyAgreement
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DeviceDownloadTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun verifiedHighlightsAreSortedDeduplicatedAndDoNotChangeAudio() {
        fixture(800,listValue=100,wireSize=830).use {f->
            DeviceDownloadAssembler(File(temporary.root,"highlights"),f.header(TransferSizeLayout.FRAMED_166_LIST_20),f.session,DownloadDiagnostic("test"),true).use {a->
                for(seq in listOf(3,0,1,1,2,4)) {
                    val frame=f.slice(seq);frame.payload[4]=(when(seq) {0->2;1,3->1;4->4;else->0}).toByte()
                    a.accept(frame)
                }
                val result=a.accept(DeviceFrame(0x1a,0x0a,1,ByteArray(9)))!!
                assertEquals(listOf(20L,60L),result.hardwareHighlights!!.map {it.positionMs})
                assertEquals(listOf("device-1","device-3"),result.hardwareHighlights!!.map {it.id})
                assertArrayEquals(f.plain,result.decryptedFile.readBytes())
            }
        }
    }
    @Test fun unverifiedLayoutDoesNotGuessHighlightsAndVerifiedEmptyIsDistinct() {
        for(verified in listOf(false,true)) fixture(160,listValue=20,wireSize=166).use {f->
            DeviceDownloadAssembler(File(temporary.root,"empty-$verified"),f.header(TransferSizeLayout.FRAMED_166_LIST_20),f.session,DownloadDiagnostic("test"),verified).use {a->
                a.accept(f.slice(0));val r=a.accept(DeviceFrame(0x1a,0x0a,1,ByteArray(9)))!!
                if(verified)assertEquals(emptyList<Any>(),r.hardwareHighlights) else assertNull(r.hardwareHighlights)
            }
        }
        fixture(160).use {f->DeviceDownloadAssembler(File(temporary.root,"direct"),f.header(),f.session,DownloadDiagnostic("test"),true).use {a->
            val frame=f.slice(0);frame.payload[4]=1;a.accept(frame)
            assertNull(a.accept(DeviceFrame(0x1a,0x0a,1,ByteArray(9)))!!.hardwareHighlights)
        }}
    }
    @Test fun highlightBitCanCoexistWithBoundaryBitsAndDiagnosticsExcludeSecrets() {
        fixture(320,listValue=40,wireSize=332).use {f->DeviceDownloadAssembler(File(temporary.root,"bits"),f.header(TransferSizeLayout.FRAMED_166_LIST_20),f.session,DownloadDiagnostic("test"),true).use {a->
            for(seq in 0..1) {val frame=f.slice(seq);frame.payload[4]=(if(seq==0)3 else 5).toByte();a.accept(frame)}
            assertEquals(listOf(0L,20L),a.accept(DeviceFrame(0x1a,0x0a,1,ByteArray(9)))!!.hardwareHighlights!!.map {it.positionMs})
            val metadata=DeviceWireMetadata.from(DeviceFrame(0x2e,1,1,"SECRET_HANDSHAKE".toByteArray()))
            assertTrue(metadata.slices.isEmpty());assertNull(metadata.recordingStatus);assertFalse(metadata.toString().contains("SECRET_HANDSHAKE"))
        }}
    }

    @Test fun framedLengthVariantRequiresAll367SlicesAndProduces58720PayloadBytes() {
        fixture(58720, listValue = 7340, wireSize = 60922).use { fixture ->
            DeviceDownloadAssembler(File(temporary.root, "framed"), fixture.header(TransferSizeLayout.FRAMED_166_LIST_20),
                fixture.session, DownloadDiagnostic("test", advertisedListValue = 7340)).use { download ->
                repeat(366) { download.accept(fixture.slice(it)) }
                assertThrows(IllegalStateException::class.java) { download.accept(DeviceFrame(0x1a, 0x0a, 1, ByteArray(9))) }
                download.accept(fixture.slice(366))
                val result = requireNotNull(download.accept(DeviceFrame(0x1a, 0x0a, 1, ByteArray(9))))
                assertEquals(60922L, result.diagnostic.headerBytes)
                assertEquals(7340L, result.diagnostic.advertisedListValue)
                assertEquals(367L, result.diagnostic.expectedSlices)
                assertEquals(58720L, result.diagnostic.expectedPayloadBytes)
                assertEquals(58720L, result.decryptedFile.length())
                assertArrayEquals(fixture.plain, result.decryptedFile.readBytes())
                assertFalse(result.decoded)
            }
        }
    }

    @Test fun lengthVariantsNeverSilentlyFallbackOrRoundPartialFrames() {
        assertThrows(IllegalArgumentException::class.java) { TransferLength.resolve(7340, 60921, TransferSizeLayout.FRAMED_166_LIST_20) }
        assertThrows(IllegalArgumentException::class.java) { TransferLength.resolve(7341, 60922, TransferSizeLayout.FRAMED_166_LIST_20) }
        assertThrows(IllegalArgumentException::class.java) { TransferLength.resolve(300, 300, TransferSizeLayout.FRAMED_166_LIST_20) }
        assertThrows(IllegalArgumentException::class.java) { TransferLength.resolve(7340, 60922, TransferSizeLayout.DIRECT_PAYLOAD_BYTES) }
        assertEquals(300L, TransferLength.resolve(300, 300, TransferSizeLayout.DIRECT_PAYLOAD_BYTES).payloadBytes)
    }

    @Test fun unknownCompletionLayoutCannotPublishEvenWhenAllSlicesArrive() {
        fixture(160).use { fixture ->
            val directory = File(temporary.root, "unknown-completion")
            DeviceDownloadAssembler(directory, fixture.header(), fixture.session, DownloadDiagnostic("test")).use { download ->
                download.accept(fixture.slice(0))
                assertThrows(IllegalArgumentException::class.java) { download.accept(DeviceFrame(0x1a, 0x0a, 1, ByteArray(8))) }
                assertFalse(File(directory, "device-audio.bin").exists())
            }
            assertFalse(directory.exists())
        }
    }

    @Test fun sessionToFileDecryptAcceptsOutOfOrderAndDuplicateSlices() {
        fixture(300).use { fixture ->
            val dir = File(temporary.root, "complete")
            DeviceDownloadAssembler(dir, fixture.header(), fixture.session, DownloadDiagnostic("test")).use { download ->
                download.accept(fixture.slice(1)); download.accept(fixture.slice(1)); download.accept(fixture.slice(0))
                val result = requireNotNull(download.accept(DeviceFrame(0x1a, 0x0a, 1, ByteArray(9))))
                assertArrayEquals(fixture.plain, result.decryptedFile.readBytes())
                assertEquals(300L, result.encryptedFile.length())
                assertFalse(result.playable)
                assertEquals(300L, result.diagnostic.contiguousBytes)
                assertEquals(64, result.decryptedSha256.length)
            }
            assertTrue(File(dir, "device-audio.bin").isFile)
        }
    }

    @Test fun missingZeroSequenceCannotBeGuessedAsOneBased() {
        fixture(320).use { fixture ->
            val dir = File(temporary.root, "incomplete")
            DeviceDownloadAssembler(dir, fixture.header(), fixture.session, DownloadDiagnostic("test")).use { download ->
                download.accept(fixture.slice(1))
                assertThrows(IllegalStateException::class.java) { download.accept(DeviceFrame(0x1a, 0x0a, 1, ByteArray(9))) }
                assertEquals(1L, download.diagnostic().firstSequence)
                assertEquals(0L, download.diagnostic().contiguousBytes)
            }
            assertFalse(dir.exists())
        }
    }

    @Test fun mismatchedIdentitySizeErrorAndUnknownHeaderAreRejected() {
        fixture(160).use { fixture ->
            for (offset in listOf(0, 4, 86)) {
                val body = fixture.headerPayload.copyOf().also { it[offset] = (it[offset].toInt() xor 1).toByte() }
                val metadata = EncryptedFileHeader.inspect(DeviceFrame(0x1a, 7, 1, body), fixture.recording)
                assertEquals(offset != 0, metadata.fileIdMatches)
                assertEquals(if (offset == 4) 161L else 160L, metadata.declaredBytes)
                assertEquals(if (offset == 86) 1 else 0, metadata.errorCode)
                assertThrows(IllegalArgumentException::class.java) { EncryptedFileHeader.parse(DeviceFrame(0x1a, 7, 1, body), fixture.recording, TransferSizeLayout.DIRECT_PAYLOAD_BYTES) }
            }
            assertThrows(IllegalArgumentException::class.java) {
                EncryptedFileHeader.parse(DeviceFrame(0x1a, 7, 1, fixture.headerPayload + byteArrayOf(0)), fixture.recording, TransferSizeLayout.DIRECT_PAYLOAD_BYTES)
            }
        }
    }

    @Test fun corruptedFileKeyAndConflictingDuplicateCannotProduceSuccess() {
        fixture(160).use { fixture ->
            val badHeader = fixture.headerPayload.copyOf().also { it[24] = (it[24].toInt() xor 1).toByte() }
            EncryptedFileHeader.parse(DeviceFrame(0x1a, 7, 1, badHeader), fixture.recording, TransferSizeLayout.DIRECT_PAYLOAD_BYTES).use { bad ->
                assertThrows(IllegalArgumentException::class.java) { bad.unlock(fixture.session) }
            }
            DeviceDownloadAssembler(File(temporary.root, "conflict"), fixture.header(), fixture.session, DownloadDiagnostic("test")).use { download ->
                download.accept(fixture.slice(0))
                val changed = fixture.slice(0).also { it.payload[5] = (it.payload[5].toInt() xor 1).toByte() }
                assertThrows(IllegalArgumentException::class.java) { download.accept(changed) }
            }
        }
    }

    internal fun fixture(size: Int, listValue: Long = size.toLong(), wireSize: Int = size, fileId: Long = 12345): Fixture {
        val session = DeviceCrypto()
        val peer = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val public = peer.public as ECPublicKey
        fun coordinate(value: BigInteger) = value.toByteArray().takeLast(32).toByteArray().let { ByteArray(32 - it.size) + it }
        val wirePublic = byteArrayOf(4) + coordinate(public.w.affineX) + coordinate(public.w.affineY)
        val appPublic = session.publicKey()
        val appKey = KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(
            ECPoint(BigInteger(1, appPublic.copyOfRange(1, 33)), BigInteger(1, appPublic.copyOfRange(33, 65))), public.params))
        val shared = KeyAgreement.getInstance("ECDH").run { init(peer.private); doPhase(appKey, true); generateSecret() }
        session.establish(wirePublic, shared)
        val sessionKey = DeviceCrypto.hkdf(shared); shared.fill(0)
        val fileKey = ByteArray(32) { (it + 17).toByte() }
        val nonce = ByteArray(16) { (it + 33).toByte() }
        val sessionNonce = ByteArray(16) { (it + 65).toByte() }
        val encryptedKey = DeviceCrypto.ctr(sessionKey, sessionNonce, "soundcored3200".toByteArray() + fileKey)
        sessionKey.fill(0)
        val payload = ByteBuffer.allocate(87).order(ByteOrder.LITTLE_ENDIAN).putInt(fileId.toInt()).putInt(wireSize)
            .put(nonce).put(encryptedKey).put(sessionNonce).put(0).array()
        return Fixture(session, DeviceRecording(fileId, listValue), payload, fileKey, nonce, ByteArray(size) { (it % 109).toByte() })
    }

    internal class Fixture(val session: DeviceCrypto, val recording: DeviceRecording, val headerPayload: ByteArray,
        val key: ByteArray, val nonce: ByteArray, val plain: ByteArray) : AutoCloseable {
        fun header(layout: TransferSizeLayout = TransferSizeLayout.DIRECT_PAYLOAD_BYTES) = EncryptedFileHeader.parse(DeviceFrame(0x1a, 7, 1, headerPayload.copyOf()), recording, layout)
        fun slice(sequence: Int): DeviceFrame {
            val chunk = ByteArray(160)
            plain.copyInto(chunk, 0, sequence * 160, minOf(plain.size, (sequence + 1) * 160))
            val encrypted = DeviceCrypto.decryptSlice(key, nonce, sequence.toLong(), chunk)
            return DeviceFrame(0x1a, 8, 1, ByteBuffer.allocate(166).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(sequence).put(0).put(encrypted).put(0).array())
        }
        override fun close() { session.close(); key.fill(0); nonce.fill(0); headerPayload.fill(0) }
    }
}

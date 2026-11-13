package dev.soundsoftware.core.device

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class DeviceBindingConfirmationTest {
    private val ack = DeviceFrame(0x0b, 0x87, 1, byteArrayOf())
    private val confirm = DeviceFrame(0x0b, 0x88, 1, byteArrayOf())

    @Test fun ackAloneDoesNotCompleteBinding() = runBlocking {
        val session = DeviceBindingConfirmation()
        var prompted = false
        val result = async(start = CoroutineStart.UNDISPATCHED) { session.awaitAfter(ack) { prompted = true } }
        assertTrue(prompted)
        assertFalse(session.accept(ack))
        assertFalse(result.isCompleted)
        assertTrue(session.accept(confirm))
        assertTrue(result.await())
    }

    @Test fun confirmationBeforeAckConsumerIsNotLost() = runBlocking {
        val session = DeviceBindingConfirmation()
        assertTrue(session.accept(confirm))
        assertTrue(session.awaitAfter(ack) { fail("Already confirmed; no side-key prompt needed") })
    }

    @Test fun rejectionAndDisconnectCannotBecomeSuccess() = runBlocking {
        assertFalse(DeviceBindingConfirmation().awaitAfter(ack.copy(status = 0)) { fail("Rejected ACK") })
        val session = DeviceBindingConfirmation()
        session.accept(confirm.copy(status = 0))
        assertFalse(session.awaitAfter(ack) {})
        val dropped = DeviceBindingConfirmation()
        dropped.fail(IllegalStateException("disconnected"))
        try { dropped.awaitAfter(ack) {}; fail("Disconnect must fail binding") }
        catch (expected: IllegalStateException) { assertEquals("disconnected", expected.message) }
    }
}

package dev.soundsoftware.core.device

import kotlinx.coroutines.CompletableDeferred

/** 0B/87 acknowledges the request; 0B/88 confirms the device-side binding. */
class DeviceBindingConfirmation {
    private val confirmed = CompletableDeferred<Boolean>()

    /** Retains a confirmation even if it arrives in the same notification as the ACK. */
    fun accept(frame: DeviceFrame): Boolean {
        if (frame.type != 0x0b || frame.command != 0x88) return false
        confirmed.complete(frame.successful)
        return true
    }

    suspend fun awaitAfter(ack: DeviceFrame, onAwaitingPress: () -> Unit): Boolean {
        require(ack.type == 0x0b && ack.command == 0x87)
        if (!ack.successful) return false
        if (!confirmed.isCompleted) onAwaitingPress()
        return confirmed.await()
    }

    fun fail(error: Throwable) { confirmed.completeExceptionally(error) }
}

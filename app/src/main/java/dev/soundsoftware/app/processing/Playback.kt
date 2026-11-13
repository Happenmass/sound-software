package dev.soundsoftware.app.processing

import android.media.AudioAttributes
import android.media.MediaPlayer
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class PlaybackState(
    val recordingId: String? = null,
    val playing: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val speed: Float = 1f,
    val skipSilence: Boolean = false,
)

/** One MediaPlayer for the app. Silence skipping jumps over gaps between transcript segments. */
class Playback(private val scope: CoroutineScope) {
    private val mutable = MutableStateFlow(PlaybackState())
    val state = mutable.asStateFlow()
    private var player: MediaPlayer? = null
    private var ticker: Job? = null
    private var gaps: List<LongRange> = emptyList()

    fun load(id: String, file: File, silentGaps: List<LongRange>) {
        gaps = silentGaps
        if (mutable.value.recordingId == id && player != null) return
        release()
        val next = MediaPlayer()
        next.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        next.setDataSource(file.path)
        next.prepare()
        next.setOnCompletionListener { mutable.update { s -> s.copy(playing = false, positionMs = s.durationMs) }; ticker?.cancel() }
        player = next
        mutable.update { PlaybackState(id, false, 0, next.duration.toLong(), it.speed, it.skipSilence) }
    }

    fun toggle() { if (mutable.value.playing) pause() else play() }

    fun play(fromMs: Long? = null) {
        val p = player ?: return
        val target = fromMs?.coerceIn(0, p.duration.toLong())
        if (target != null) p.seekTo(target, MediaPlayer.SEEK_CLOSEST)
        else if (p.currentPosition >= p.duration - 50) p.seekTo(0)
        runCatching { p.playbackParams = p.playbackParams.setSpeed(mutable.value.speed) }
        p.start()
        mutable.update { it.copy(playing = true, positionMs = target ?: p.currentPosition.toLong()) }
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                val pos = p.currentPosition.toLong()
                if (mutable.value.skipSilence) gaps.firstOrNull { pos in it && it.last - pos > 300 }?.let { p.seekTo((it.last + 1).toInt()) }
                mutable.update { it.copy(positionMs = p.currentPosition.toLong()) }
                delay(200)
            }
        }
    }

    fun pause() {
        player?.takeIf { it.isPlaying }?.pause()
        ticker?.cancel()
        mutable.update { it.copy(playing = false, positionMs = player?.currentPosition?.toLong() ?: it.positionMs) }
    }

    fun seekBy(deltaMs: Long) {
        val p = player ?: return
        val target = (p.currentPosition + deltaMs).coerceIn(0, p.duration.toLong())
        p.seekTo(target.toInt()); mutable.update { it.copy(positionMs = target) }
    }

    fun seekTo(ms: Long) { val p = player ?: return; p.seekTo(ms.toInt()); mutable.update { it.copy(positionMs = ms) } }

    fun cycleSpeed() {
        val next = SPEEDS[(SPEEDS.indexOf(mutable.value.speed) + 1) % SPEEDS.size]
        mutable.update { it.copy(speed = next) }
        player?.takeIf { it.isPlaying }?.let { runCatching { it.playbackParams = it.playbackParams.setSpeed(next) } }
    }

    fun setSkipSilence(value: Boolean) = mutable.update { it.copy(skipSilence = value) }

    fun release() {
        ticker?.cancel(); player?.release(); player = null
        mutable.update { PlaybackState(speed = it.speed, skipSilence = it.skipSilence) }
    }

    companion object { val SPEEDS = listOf(1f, 1.25f, 1.5f, 2f) }
}

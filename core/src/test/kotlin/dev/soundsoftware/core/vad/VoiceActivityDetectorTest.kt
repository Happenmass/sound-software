package dev.soundsoftware.core.vad

import dev.soundsoftware.core.audio.PcmWav
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VoiceActivityDetectorTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun voiceRetainsOriginalOffsetAndPadding() = runBlocking {
        val file = File(folder.root, "voice.wav")
        PcmWav.writeMono(file, FloatArray(48000) { if (it in 16000 until 32000) 0.1f else 0f })
        val ranges = EnergyVad(paddingMs = 200).detect(PcmWav.open(file))
        assertEquals(listOf(SpeechRange(12800, 35200)), ranges)
    }

    @Test fun splittingDoesNotDropTailOrCompressGaps() {
        assertEquals(listOf(SpeechRange(100, 1100), SpeechRange(1100, 1250), SpeechRange(2000, 2500)),
            splitSpeechRanges(listOf(SpeechRange(100, 1250), SpeechRange(2000, 2500)), 1000))
    }

    @Test fun silenceIsEmptyForEnergyBaseline() = runBlocking {
        val file = File(folder.root, "silence.wav")
        PcmWav.writeMono(file, FloatArray(16000))
        assertTrue(EnergyVad().detect(PcmWav.open(file)).isEmpty())
    }
}

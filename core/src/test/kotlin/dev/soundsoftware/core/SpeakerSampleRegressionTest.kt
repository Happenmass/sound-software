package dev.soundsoftware.core

import dev.soundsoftware.core.transcript.*
import java.io.File
import kotlinx.serialization.json.*
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Private embeddings stay outside the repository. Provides a fast replay of the actual policy. */
class SpeakerSampleRegressionTest {
    @Test fun authorizedLocalFeatureReplay() {
        val paths=(System.getenv("DIARIZATION_FEATURE_FIXTURES") ?: System.getenv("DIARIZATION_FEATURE_FIXTURE"))
            ?.split(File.pathSeparator)?.filter { it.isNotBlank() }.orEmpty()
        assumeTrue("Opt in with private local feature fixtures",paths.isNotEmpty())
        paths.forEachIndexed { index,path ->
        val input=Json.parseToJsonElement(File(path).readText())
        val rows=if(input is JsonObject)input.getValue("observations").jsonArray else input.jsonArray
        val expected=if(input is JsonObject)input.getValue("expectedSpeakers").jsonPrimitive.int else 2
        val observations=rows.map {e->val r=e.jsonObject
            SpeakerObservation(SpeakerTurn(r.getValue("startMs").jsonPrimitive.long,r.getValue("endMs").jsonPrimitive.long,r.getValue("rawSpeaker").jsonPrimitive.int),
                r["embedding"]?.takeUnless {it is JsonNull}?.jsonArray?.map{it.jsonPrimitive.float}?.toFloatArray(),r.getValue("cleanMs").jsonPrimitive.long)
        }
        val out=SpeakerClustering().cluster(observations)
        assertEquals("Local authorized fixture #${index+1}, automatic global clustering",expected,out.speakers)
        assertEquals(observations.size,out.turns.size)
        println("PRIVATE_FEATURE_REPLAY fixture=${index+1} estimatedSpeakers=${out.speakers} anchors=${out.anchorCount} unknown=${out.unknownTurns} preservedTurns=${out.turns.size}")
        }
    }
}

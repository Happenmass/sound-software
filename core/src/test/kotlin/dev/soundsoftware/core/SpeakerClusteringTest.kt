package dev.soundsoftware.core

import dev.soundsoftware.core.model.Segment
import dev.soundsoftware.core.transcript.*
import org.junit.Assert.*
import org.junit.Test

class SpeakerClusteringTest {
    private fun obs(start:Long,ms:Long,v:FloatArray?,raw:Int=0)=SpeakerObservation(SpeakerTurn(start,start+ms,raw),v,ms)
    @Test fun noisyShortTurnsDoNotCreateExtraPeopleButAreKeptForRecognition() {
        val input=listOf(obs(0,3000,floatArrayOf(1f,0f,0f)),obs(4000,3000,floatArrayOf(0f,1f,0f)),
            obs(8000,500,floatArrayOf(0f,0f,1f)),obs(9000,700,floatArrayOf(.1f,1f,0f)))
        val out=SpeakerClustering().cluster(input)
        assertEquals(2,out.speakers);assertEquals(4,out.turns.size);assertEquals(-1,out.turns[2].speaker);assertEquals(1,out.turns[3].speaker)
        val (segments,speakers)=assignSpeakers(listOf(Segment("short","short",8000,8500)),out.turns)
        assertNull(segments.single().speakerId);assertTrue(speakers.isEmpty())
    }
    @Test fun automaticCountIsNotCappedAtTwo() {
        for(n in 1..4){val input=(0 until n).map{i->obs(i*4000L,3000,FloatArray(4){if(it==i)1f else 0f})};assertEquals(n,SpeakerClustering().cluster(input).speakers)}
    }
    @Test fun distinctLongVoicesWithModerateSimilarityMustRemainThree() {
        val input=listOf(obs(0,5000,floatArrayOf(1f,0f,0f)),obs(6000,5000,floatArrayOf(.451f,.8925f,0f)),obs(12000,5000,floatArrayOf(.450f,.06965f,.8903f)))
        assertEquals(3,SpeakerClustering().cluster(input).speakers)
    }
    @Test fun sameVoiceAcrossDifferentRawClustersIsMergedGlobally() {
        val input=listOf(obs(0,3000,floatArrayOf(1f,.1f),0),obs(4000,3000,floatArrayOf(.1f,1f),1),obs(8000,3000,floatArrayOf(1f,.15f),8))
        val out=SpeakerClustering().cluster(input);assertEquals(2,out.speakers);assertEquals(out.turns[0].speaker,out.turns[2].speaker)
    }
    @Test fun simultaneousCleanAnchorsCannotCollapseIntoOneVoice() {
        val input=listOf(obs(0,4000,floatArrayOf(1f,.1f)),obs(1000,4000,floatArrayOf(1f,.1f),1))
        assertEquals(2,SpeakerClustering().cluster(input).speakers)
    }
    @Test fun briefBoundaryOverlapDoesNotSplitOneVoiceAcrossLongTurns() {
        val input=listOf(
            obs(0,10000,floatArrayOf(1f,0f),0).copy(cleanDurationMs=9600),
            obs(9600,10000,floatArrayOf(1f,.1f),7).copy(cleanDurationMs=9600),
            obs(21000,5000,floatArrayOf(0f,1f),1))
        val out=SpeakerClustering().cluster(input)
        assertEquals(2,out.speakers)
        assertEquals(out.turns[0].speaker,out.turns[1].speaker)
        assertNotEquals(out.turns[0].speaker,out.turns[2].speaker)
    }
    @Test fun overlapMustBeJudgedRelativeToTheShorterTurn() {
        val input=listOf(
            obs(0,3000,floatArrayOf(1f,0f),0).copy(cleanDurationMs=2300),
            obs(2300,3000,floatArrayOf(1f,0f),1).copy(cleanDurationMs=2300))
        assertEquals(2,SpeakerClustering().cluster(input).speakers)
    }
    @Test fun entirelyShortOrInvalidSpeechStaysUnknownWithoutDroppingSpeech() {
        val input=listOf(obs(0,500,floatArrayOf(1f,0f)),obs(1000,4000,floatArrayOf(Float.NaN,1f)),obs(6000,4000,floatArrayOf(0f,0f)))
        val out=SpeakerClustering().cluster(input);assertEquals(0,out.speakers);assertEquals(3,out.unknownTurns);assertEquals(input.map{it.turn.startMs},out.turns.map{it.startMs})
    }
    @Test fun ambiguityDoesNotConfidentlyChooseOneOfTwoSpeakers() {
        val input=listOf(obs(0,3000,floatArrayOf(1f,0f)),obs(4000,3000,floatArrayOf(0f,1f)),obs(8000,600,floatArrayOf(1f,1f)))
        assertEquals(-1,SpeakerClustering().cluster(input).turns.last().speaker)
    }
    @Test fun cleanDurationRatherThanWallClockDurationGatesAnchors() {
        val input=listOf(obs(0,3000,floatArrayOf(1f,0f)).copy(cleanDurationMs=600))
        assertEquals(0,SpeakerClustering().cluster(input).speakers)
    }
    @Test fun overlapIsRemovedFromIdentitySamples() {
        val turn=SpeakerTurn(1000,5000,0)
        val other=SpeakerTurn(2000,3000,1)
        assertEquals(listOf(1000L until 2000,3000L until 5000),cleanSpeechIntervals(turn,listOf(turn,other)))
    }
    @Test fun boundedAnchorsCoverEndOfRecordingAndDoNotLoseSpeechWindows() {
        val input=(0..299).map{i->obs(i*4000L,3000,if(i<150)floatArrayOf(1f,0f) else floatArrayOf(0f,1f))}
        val out=SpeakerClustering(SpeakerClusteringPolicy(maxAnchors=32)).cluster(input)
        assertEquals(32,out.anchorCount);assertEquals(2,out.speakers);assertEquals(300,out.turns.size)
    }
    @Test fun isolatedShortClusterDoesNotInventAThirdIdentityAmongEstablishedVoices() {
        val input=(0 until 20).map {i->obs(i*5000L,4000,if(i%2==0)floatArrayOf(1f,0f,0f)else floatArrayOf(0f,1f,0f),i)}+
            obs(100000,3300,floatArrayOf(.47f,.38f,.8f),20)
        val out=SpeakerClustering().cluster(input)
        assertEquals(2,out.speakers)
        assertEquals(-1,out.turns.last().speaker)
        assertEquals(input.size,out.turns.size)
    }
    @Test fun repeatedBriefThirdVoiceAndLongSingleThirdVoiceRemainDistinct() {
        val established=listOf(
            obs(0,4000,floatArrayOf(1f,0f,0f)),obs(5000,4000,floatArrayOf(0f,1f,0f),1),
            obs(10000,4000,floatArrayOf(1f,.05f,0f),2),obs(15000,4000,floatArrayOf(.05f,1f,0f),3))
        val third=floatArrayOf(.47f,.38f,.8f)
        assertEquals(3,SpeakerClustering().cluster(established+obs(20000,3300,third,4)).speakers)
        assertEquals(3,SpeakerClustering().cluster(established+listOf(obs(20000,2300,third,4),obs(24000,2200,third,5))).speakers)
        assertEquals(3,SpeakerClustering().cluster(established+obs(20000,7000,third,4)).speakers)
    }
    @Test fun repeatedGlobalIdentityCanRecoverFromOneConflictingLocalOverlap() {
        val input=(0 until 10).flatMap {i->listOf(
            obs(i*20000L,5000,floatArrayOf(1f,0f,0f),0).copy(cleanDurationMs=if(i==0)3000 else 5000),
            obs(i*20000L+if(i==0)3000 else 10000,5000,floatArrayOf(.9f,.4f,0f),1).copy(cleanDurationMs=if(i==0)3000 else 5000),
            obs(i*20000L+16000,3000,floatArrayOf(0f,0f,1f),2)) }
        val out=SpeakerClustering().cluster(input)
        assertEquals(2,out.speakers)
        assertEquals(out.turns[0].speaker,out.turns[1].speaker)
    }
    @Test fun repeatedlyConcurrentVoicesRemainSeparateEvenWithSimilarEmbeddings() {
        val input=(0 until 5).flatMap {i->listOf(
            obs(i*10000L,4000,floatArrayOf(1f,0f),0).copy(cleanDurationMs=2500),
            obs(i*10000L+2500,4000,floatArrayOf(1f,0f),1).copy(cleanDurationMs=2500)) }
        assertEquals(2,SpeakerClustering().cluster(input).speakers)
    }
    @Test fun twoWeakBriefObservationsNeedCohesionBeforeCreatingARareIdentity() {
        val main=(0 until 40).map {i->obs(i*5000L,4000,if(i%2==0)floatArrayOf(1f,0f,0f,0f)else floatArrayOf(0f,1f,0f,0f),i)}
        val a=floatArrayOf(.3f,.3f,.9f,0f);val b=floatArrayOf(.3f,.3f,.45f,.78f)
        val weak=SpeakerClustering().cluster(main+listOf(obs(201000,3300,a,40),obs(206000,3300,b,41)))
        assertEquals(2,weak.speakers);assertEquals(listOf(-1,-1),weak.turns.takeLast(2).map {it.speaker})
        val consistent=SpeakerClustering().cluster(main+listOf(obs(201000,3300,a,40),obs(206000,3300,a,41)))
        assertEquals(3,consistent.speakers)
    }
    @Test fun repeatedSimilarButDistinctThreeVoicesDoNotCollapseUnderPrototypeMerging() {
        val voices=listOf(floatArrayOf(1f,0f,0f),floatArrayOf(.451f,.8925f,0f),floatArrayOf(.450f,.06965f,.8903f))
        val input=(0 until 8).flatMap {round->voices.mapIndexed {speaker,v->
            obs((round*3+speaker)*6000L,5000,floatArrayOf(v[0],v[1],v[2],if(round%2==0).08f else -.08f),speaker)
        } }
        assertEquals(3,SpeakerClustering().cluster(input).speakers)
    }
}

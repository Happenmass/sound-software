package dev.soundsoftware.core.transcript

import kotlin.math.sqrt

/** A turn embedding is evidence for identity only when enough non-overlapping speech is present. */
data class SpeakerObservation(val turn: SpeakerTurn, val embedding: FloatArray?, val cleanDurationMs: Long)
data class SpeakerClusteringPolicy(
    val minCleanMs: Long = 2000,
    val mergeDistance: Float = 0.50f,
    val assignmentDistance: Float = 0.80f,
    val assignmentMargin: Float = 0.05f,
    val maxAnchors: Int = 256,
    val minOverlapFraction: Float = 0.20f,
    val minSingletonCleanMs: Long = 6000,
    val maxUnsupportedFraction: Float = 0.05f,
)
data class SpeakerClusteringResult(val turns: List<SpeakerTurn>, val speakers: Int, val anchorCount: Int, val unknownTurns: Int)

/**
 * Fit global identities on clean, sufficiently long turns, then assign short/noisy turns.
 * Short responses never seed extra people. Uncertain attribution stays -1 while keeping speech.
 * These cosine-distance parameters belong to the bundled ERes2NetV2 model, not arbitrary models.
 */
class SpeakerClustering(private val policy: SpeakerClusteringPolicy = SpeakerClusteringPolicy()) {
    init { require(policy.minCleanMs >= 300 && policy.maxAnchors >= 2 && policy.mergeDistance in 0f..2f && policy.assignmentDistance in 0f..2f && policy.assignmentMargin >= 0f && policy.minOverlapFraction in 0f..1f && policy.minSingletonCleanMs>=0 && policy.maxUnsupportedFraction in 0f..1f) }
    fun cluster(observations: List<SpeakerObservation>): SpeakerClusteringResult {
        val dimension=observations.firstOrNull {it.embedding?.let {v->normalize(v)!=null}==true}?.embedding?.size ?: 0
        val vectors=observations.map { it.embedding?.takeIf { v -> v.size==dimension }?.let(::normalize) }
        val candidates=observations.indices.filter { observations[it].cleanDurationMs>=policy.minCleanMs && vectors[it]!=null }
        // Bound the pairwise matrix; sample across the whole recording, not only its beginning.
        val anchors=if(candidates.size<=policy.maxAnchors)candidates else (0 until policy.maxAnchors).map { candidates[it*(candidates.size-1)/(policy.maxAnchors-1)] }
        if(anchors.isEmpty())return SpeakerClusteringResult(observations.map{it.turn.copy(speaker=UNKNOWN)},0,0,observations.size)
        val n=anchors.size
        val similarity=Array(n){DoubleArray(n)}
        val concurrentPairs=Array(n){IntArray(n)}
        for(i in 0 until n) for(j in 0..i) {
            similarity[i][j]=vectors[anchors[i]]!!.indices.sumOf {k->vectors[anchors[i]]!![k].toDouble()*vectors[anchors[j]]!![k]}
            similarity[j][i]=similarity[i][j]
            if(i==j)continue
            val overlap=minOf(observations[anchors[i]].turn.endMs,observations[anchors[j]].turn.endMs)-maxOf(observations[anchors[i]].turn.startMs,observations[anchors[j]].turn.startMs)
            val shorterDuration=minOf(observations[anchors[i]].turn.endMs-observations[anchors[i]].turn.startMs,
                observations[anchors[j]].turn.endMs-observations[anchors[j]].turn.startMs)
            // Local segmentation overlap is evidence, not an infallible global cannot-link.
            val concurrent=overlap>=300 && overlap>=shorterDuration*policy.minOverlapFraction
            concurrentPairs[i][j]=if(concurrent)1 else 0
            concurrentPairs[j][i]=concurrentPairs[i][j]
        }
        val groups=Array(n){mutableListOf(it)};val active=BooleanArray(n){true}
        while(true) {
            var best=policy.mergeDistance.toDouble();var left=-1;var right=-1
            for(i in 0 until n)if(active[i])for(j in 0 until i)if(active[j]) {
                val prototypeDistance=(1-similarity[i][j]/sqrt(similarity[i][i]*similarity[j][j])).coerceIn(0.0,2.0)
                // Repeated concurrency remains strong evidence for distinct people. One bad
                // boundary among many consistent turns must not permanently split a voice.
                val overlapEvidence=(concurrentPairs[i][j].toDouble()/minOf(groups[i].size,groups[j].size)).coerceAtMost(1.0)
                val distance=prototypeDistance+2*overlapEvidence
                if(distance<best){best=distance;left=j;right=i}
            }
            if(left<0)break
            similarity[left][left]+=similarity[right][right]+2*similarity[left][right]
            for(k in 0 until n)if(active[k]&&k!=left&&k!=right){
                similarity[left][k]+=similarity[right][k];similarity[k][left]=similarity[left][k]
                concurrentPairs[left][k]+=concurrentPairs[right][k];concurrentPairs[k][left]=concurrentPairs[left][k]
            }
            groups[left]+=groups[right];active[right]=false
        }
        val identityGroups=groups.indices.filter {active[it]}.map {groups[it]}.sortedBy {g->g.minOf{observations[anchors[it]].turn.startMs}}
        // One short embedding is insufficient evidence for an additional person when stable
        // identities exist. Keep its speech as unknown; never force it into a nearby voice.
        // With only brief speech, retain the original estimate rather than erasing everyone.
        val supported=identityGroups.filter {g->g.size>=3 ||
            (g.size==2 && cosineDistance(vectors[anchors[g[0]]]!!,vectors[anchors[g[1]]]!!)<=policy.mergeDistance/2) ||
            g.any {observations[anchors[it]].cleanDurationMs>=policy.minSingletonCleanMs}}
        val supportedAnchors=supported.flatten().toSet()
        val totalClean=anchors.sumOf {observations[it].cleanDurationMs}
        val unsupportedClean=anchors.indices.filterNot {it in supportedAnchors}.sumOf {observations[anchors[it]].cleanDurationMs}
        // Do not erase a substantial minority voice merely because it spoke in one turn.
        val identities=if(supported.isNotEmpty() && unsupportedClean<=totalClean*policy.maxUnsupportedFraction)supported else identityGroups
        val retainedAnchors=identities.flatMap {g->g.map {anchors[it]}}.toSet()
        val unsupportedAnchors=anchors.filterNot {it in retainedAnchors}.toSet()
        val centroids=identities.map {g->
            val sum=FloatArray(dimension);g.forEach {i->vectors[anchors[i]]!!.forEachIndexed{k,x->sum[k]+=x}}
            normalize(sum)!!
        }
        val known=mutableMapOf<Int,Int>();identities.forEachIndexed {speaker,g->g.forEach {known[anchors[it]]=speaker}}
        var unknown=0
        val turns=observations.mapIndexed {i,obs->
            val assigned=known[i] ?: if(i in unsupportedAnchors)UNKNOWN else vectors[i]?.let {v->
                val ranked=centroids.mapIndexed {k,c->k to cosineDistance(v,c)}.sortedBy {it.second}
                val first=ranked[0];val next=ranked.getOrNull(1)?.second ?: Float.POSITIVE_INFINITY
                if(first.second<=policy.assignmentDistance && next-first.second>=policy.assignmentMargin)first.first else UNKNOWN
            } ?: UNKNOWN
            if(assigned==UNKNOWN)unknown++
            obs.turn.copy(speaker=assigned)
        }
        return SpeakerClusteringResult(turns,identities.size,n,unknown)
    }
    companion object {
        const val UNKNOWN=-1
        const val VERSION="quality-anchors-v2"
        private fun normalize(v:FloatArray):FloatArray? {
            if(v.isEmpty()||v.any{!it.isFinite()})return null
            val norm=sqrt(v.sumOf {it.toDouble()*it});if(norm<1e-8)return null
            return FloatArray(v.size){(v[it]/norm).toFloat()}
        }
        private fun cosineDistance(a:FloatArray,b:FloatArray)= (1f-a.indices.sumOf{a[it].toDouble()*b[it]}.toFloat()).coerceIn(0f,2f)
    }
}

/** Cut out any overlapping speaker activity before building an identity embedding. */
fun cleanSpeechIntervals(turn:SpeakerTurn,others:List<SpeakerTurn>):List<LongRange> {
    var ranges=listOf(turn.startMs until turn.endMs)
    for(other in others) {
        if(other===turn || other.speaker==turn.speaker || other.endMs<=turn.startMs || other.startMs>=turn.endMs)continue
        ranges=ranges.flatMap {r->
            val end=r.last+1
            if(other.endMs<=r.first || other.startMs>=end)listOf(r)
            else buildList {
                if(other.startMs>r.first)add(r.first until minOf(end,other.startMs))
                if(other.endMs<end)add(maxOf(r.first,other.endMs) until end)
            }
        }
    }
    return ranges
}

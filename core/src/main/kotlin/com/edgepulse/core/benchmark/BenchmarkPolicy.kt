package com.edgepulse.core.benchmark

import kotlin.math.ceil

object BenchmarkPolicy {
    fun constructionEligible(tinyP95Ms: Double) = tinyP95Ms.isFinite() && tinyP95Ms > 0 && tinyP95Ms <= 40.0
    fun tierCTargetMet(tinyP95Ms: Double) = constructionEligible(tinyP95Ms) && tinyP95Ms <= 35.0
    fun burstFps(tinyP95Ms: Double): Int = when {
        !constructionEligible(tinyP95Ms) -> 0
        tinyP95Ms <= 18.0 -> 10
        else -> 5
    }
    fun p95(samplesMs: DoubleArray): Double {
        require(samplesMs.isNotEmpty() && samplesMs.all { it.isFinite() && it > 0 })
        return samplesMs.sortedArray()[ceil(samplesMs.size * 0.95).toInt() - 1]
    }
}

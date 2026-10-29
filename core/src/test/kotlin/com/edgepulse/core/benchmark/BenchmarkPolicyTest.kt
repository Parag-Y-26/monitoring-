package com.edgepulse.core.benchmark

import org.junit.Assert.*
import org.junit.Test

class BenchmarkPolicyTest {
    @Test fun `forty milliseconds is eligible and exceeding it blocks construction`() {
        assertTrue(BenchmarkPolicy.constructionEligible(40.0))
        assertFalse(BenchmarkPolicy.constructionEligible(40.001))
        assertFalse(BenchmarkPolicy.constructionEligible(Double.NaN))
        assertFalse(BenchmarkPolicy.constructionEligible(0.0))
        assertFalse(BenchmarkPolicy.constructionEligible(Double.POSITIVE_INFINITY))
    }

    @Test fun `tier C target remains distinct from eligibility`() {
        assertTrue(BenchmarkPolicy.tierCTargetMet(35.0))
        assertFalse(BenchmarkPolicy.tierCTargetMet(35.01))
        assertEquals(10, BenchmarkPolicy.burstFps(18.0))
        assertEquals(5, BenchmarkPolicy.burstFps(35.0))
        assertEquals(5, BenchmarkPolicy.burstFps(40.0))
        assertEquals(0, BenchmarkPolicy.burstFps(41.0))
    }

    @Test fun `nearest rank percentile uses p95 and rejects empty samples`() {
        assertEquals(95.0, BenchmarkPolicy.p95(DoubleArray(100) { (it + 1).toDouble() }), 0.0)
        assertThrows(IllegalArgumentException::class.java) { BenchmarkPolicy.p95(doubleArrayOf()) }
        assertThrows(IllegalArgumentException::class.java) { BenchmarkPolicy.p95(doubleArrayOf(-1.0)) }
    }
}

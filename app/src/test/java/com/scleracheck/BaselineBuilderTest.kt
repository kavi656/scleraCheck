package com.scleracheck

import com.scleracheck.decision.BaselineBuilder
import com.scleracheck.decision.Thresholds
import com.scleracheck.measure.SideMeasurement
import com.scleracheck.quality.QualityGate
import com.scleracheck.quality.RetakeReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BaselineBuilderTest {
    @Test fun needsEnoughCaptures() {
        assertNull(BaselineBuilder.choose(listOf(10.0, 10.5)))
    }

    @Test fun medianOfThreeAgreeing() {
        val r = BaselineBuilder.choose(listOf(10.0, 11.0, 10.4))!!
        assertEquals(10.4, r.bStar, 1e-9)
        assertEquals(1.0, r.spread, 1e-9)
    }

    @Test fun outlierIsIgnored() {
        // One bad capture (e.g. glare) must not drag the baseline.
        val r = BaselineBuilder.choose(listOf(10.0, 25.0, 10.6, 11.0))!!
        assertEquals(10.6, r.bStar, 1e-9)
        assertTrue(25.0 !in r.chosen)
    }

    @Test fun inconsistentCapturesGiveNoBaseline() {
        assertNull(BaselineBuilder.choose(listOf(5.0, 12.0, 20.0)))
    }

    @Test fun spreadLimitIsInclusiveAndBelowChangeThreshold() {
        assertNotNull(BaselineBuilder.choose(listOf(10.0, 10.0 + Thresholds.BASELINE_MAX_SPREAD, 12.0)))
        assertTrue(Thresholds.BASELINE_MAX_SPREAD <= Thresholds.T_CHANGE + 1.0)
    }

    @Test fun implausibleValueIsRetake() {
        // Field data 14 Nov: weak screen light produced b* = −50.
        val m = SideMeasurement(-50.0, 12.0, 24.0, 424, 0.0, 0.1, 0.9, 100.0, 0.2, 4, 15.0, 4, 4, 4)
        assertTrue(RetakeReason.IMPLAUSIBLE in QualityGate.evaluateSide(m).reasons)
    }
}

class ReadinessTest {
    private fun r(d: Double?) = com.scleracheck.ui.Readiness(distanceMm = d, distMinMm = 170.0, distMaxMm = 220.0)

    @Test fun distanceSlackIsGenerousFarAndTightNear() {
        assertTrue(r(240.0).distOk)            // 9% beyond far edge: accepted (estimate is ±10%)
        assertTrue(!r(245.0).distOk)           // >10% beyond: closer
        assertTrue(r(166.0).distOk)            // within 3% of the near edge
        assertTrue(!r(160.0).distOk)           // too close to focus
        assertTrue(!r(null).distOk)
    }
}

class WeightedSidesTest {
    @Test fun equalPixelsIsPlainMean() =
        assertEquals(5.0, com.scleracheck.decision.DecisionEngine.weightedMean(4.0, 6.0, 500, 500), 1e-9)

    @Test fun biggerSideCountsMoreButCappedAt3to1() {
        // Field data: 451 px at b*=1.7 vs 3012 px at −6.2 → weight capped at 3:1 → −4.225.
        assertEquals((1.7 * 451 + -6.2 * 1353) / (451 + 1353),
            com.scleracheck.decision.DecisionEngine.weightedMean(1.7, -6.2, 451, 3012), 1e-9)
    }
}

class OverexposedTest {
    @Test fun clippedEyeSaysOverexposedNotRoomTooBright() {
        // Field data 15:24: glare 1.0, 0 clean px, torch ratio 0.
        val m = SideMeasurement(Double.NaN, Double.NaN, Double.NaN, 0, 1.0, 0.0, 0.0, 244.0, -0.09, 4, null, 4, 4, 4)
        assertEquals(listOf(RetakeReason.OVEREXPOSED), QualityGate.evaluateSide(m).reasons)
    }
}

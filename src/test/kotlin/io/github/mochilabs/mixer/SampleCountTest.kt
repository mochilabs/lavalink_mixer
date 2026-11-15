package io.github.mochilabs.mixer

import kotlin.test.Test
import kotlin.test.assertEquals

class SampleCountTest {
    @Test
    fun `sample counts convert at 48 kHz with a floor of one`() {
        assertEquals(48_000, TrackAnalysisService.sampleCountFor(1_000L))
        assertEquals(1, TrackAnalysisService.sampleCountFor(0L))
        assertEquals(1, TrackAnalysisService.sampleCountFor(-5L))
    }

    @Test
    fun `sample counts saturate instead of wrapping past Int range`() {
        // 44,739,243 ms * 48 = 2,147,483,664 samples, just past Int.MAX_VALUE.
        assertEquals(Int.MAX_VALUE, TrackAnalysisService.sampleCountFor(44_739_243L))
        assertEquals(Int.MAX_VALUE, TrackAnalysisService.sampleCountFor(100_000_000L))
        assertEquals(Int.MAX_VALUE, TrackAnalysisService.sampleCountFor(Long.MAX_VALUE))
    }
}

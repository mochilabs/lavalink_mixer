package io.github.mochilabs.mixer

/**
 * 4th-order Linkwitz-Riley (LR4) stereo crossover filter.
 * Cascades two 2nd-order Butterworth filters per band (24 dB/oct slope).
 * Sum of low and high magnitude response is flat 0dB across the spectrum.
 * Relative group delay between low and high components matches identically.
 */
class CrossoverLR4(
    val sampleRate: Int = 48000,
    val crossoverHz: Float = 200f,
) {
    // Left channel: 2 LP biquads + 2 HP biquads
    private val lp1L = PcmBiquadFilter()
    private val lp2L = PcmBiquadFilter()
    private val hp1L = PcmBiquadFilter()
    private val hp2L = PcmBiquadFilter()

    // Right channel: 2 LP biquads + 2 HP biquads
    private val lp1R = PcmBiquadFilter()
    private val lp2R = PcmBiquadFilter()
    private val hp1R = PcmBiquadFilter()
    private val hp2R = PcmBiquadFilter()

    private var scratchL = FloatArray(1024)
    private var scratchR = FloatArray(1024)

    init {
        configureFilters()
    }

    private fun configureFilters() {
        lp1L.setLowPass(sampleRate, crossoverHz)
        lp2L.setLowPass(sampleRate, crossoverHz)
        hp1L.setHighPass(sampleRate, crossoverHz)
        hp2L.setHighPass(sampleRate, crossoverHz)

        lp1R.setLowPass(sampleRate, crossoverHz)
        lp2R.setLowPass(sampleRate, crossoverHz)
        hp1R.setHighPass(sampleRate, crossoverHz)
        hp2R.setHighPass(sampleRate, crossoverHz)
    }

    fun reset() {
        lp1L.reset(); lp2L.reset(); hp1L.reset(); hp2L.reset()
        lp1R.reset(); lp2R.reset(); hp1R.reset(); hp2R.reset()
    }

    fun preRoll(input: Array<FloatArray>, length: Int, offset: Int = 0) {
        if (length <= 0 || input.isEmpty()) return
        ensureScratch(length)
        val inL = input[0]
        val inR = if (input.size > 1) input[1] else input[0]
        processTrack(inL, inR, scratchL, scratchR, scratchL, scratchR, length, offset)
    }

    fun processTrack(
        inputL: FloatArray,
        inputR: FloatArray,
        lowL: FloatArray,
        lowR: FloatArray,
        highL: FloatArray,
        highR: FloatArray,
        length: Int,
        offset: Int = 0,
    ) {
        ensureScratch(length)
        processChannel(inputL, lowL, highL, lp1L, lp2L, hp1L, hp2L, scratchL, length, offset)
        processChannel(inputR, lowR, highR, lp1R, lp2R, hp1R, hp2R, scratchR, length, offset)
    }

    private fun processChannel(
        input: FloatArray,
        low: FloatArray,
        high: FloatArray,
        lp1: PcmBiquadFilter,
        lp2: PcmBiquadFilter,
        hp1: PcmBiquadFilter,
        hp2: PcmBiquadFilter,
        scratch: FloatArray,
        length: Int,
        offset: Int,
    ) {
        lp1.process(input, scratch, inputOffset = offset, outputOffset = 0, length = length)
        lp2.process(scratch, low, inputOffset = 0, outputOffset = 0, length = length)
        hp1.process(input, scratch, inputOffset = offset, outputOffset = 0, length = length)
        hp2.process(scratch, high, inputOffset = 0, outputOffset = 0, length = length)
    }

    private fun ensureScratch(needed: Int) {
        if (scratchL.size < needed) {
            scratchL = FloatArray(needed)
            scratchR = FloatArray(needed)
        }
    }
}

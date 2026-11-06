package io.github.mochilabs.mixer.audio

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
    private val lp1L = BiquadFilter()
    private val lp2L = BiquadFilter()
    private val hp1L = BiquadFilter()
    private val hp2L = BiquadFilter()

    // Right channel: 2 LP biquads + 2 HP biquads
    private val lp1R = BiquadFilter()
    private val lp2R = BiquadFilter()
    private val hp1R = BiquadFilter()
    private val hp2R = BiquadFilter()

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

    fun preRoll(input: Array<FloatArray>, length: Int) {
        if (length <= 0) return
        ensureScratch(length)
        val inL = input[0]
        val inR = if (input.size > 1) input[1] else input[0]
        processTrack(inL, inR, scratchL, scratchR, scratchL, scratchR, length)
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

        // Process Left Channel
        // Stage 1 LP -> scratch, then Stage 2 LP -> lowL
        lp1L.process(inputL, scratchL, offset, length)
        lp2L.process(scratchL, lowL, 0, length)

        // Stage 1 HP -> scratch, then Stage 2 HP -> highL
        hp1L.process(inputL, scratchL, offset, length)
        hp2L.process(scratchL, highL, 0, length)

        // Process Right Channel
        lp1R.process(inputR, scratchR, offset, length)
        lp2R.process(scratchR, lowR, 0, length)

        hp1R.process(inputR, scratchR, offset, length)
        hp2R.process(scratchR, highR, 0, length)
    }

    private fun ensureScratch(needed: Int) {
        if (scratchL.size < needed) {
            scratchL = FloatArray(needed)
            scratchR = FloatArray(needed)
        }
    }
}

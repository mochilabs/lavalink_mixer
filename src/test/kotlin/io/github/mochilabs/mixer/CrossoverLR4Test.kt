package io.github.mochilabs.mixer

import kotlin.math.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CrossoverLR4Test {
    private val sampleRate = 48000

    @Test
    fun `LR4 crossover magnitude sum is flat 0dB across entire frequency spectrum`() {
        val crossover = CrossoverLR4(sampleRate = sampleRate, crossoverHz = 200f)

        // Test frequencies across the audio spectrum (50Hz sub, 200Hz crossover, 1kHz mid, 10kHz high)
        val frequencies = doubleArrayOf(40.0, 100.0, 150.0, 200.0, 250.0, 500.0, 1000.0, 5000.0, 10000.0)

        for (freq in frequencies) {
            crossover.reset()
            val numSamples = 48000 // 1 second (exact integer cycles for all tested frequencies)
            val input = FloatArray(numSamples)
            val omega = 2.0 * PI * freq / sampleRate
            for (i in 0 until numSamples) {
                input[i] = sin(omega * i).toFloat()
            }

            val lowOut = FloatArray(numSamples)
            val highOut = FloatArray(numSamples)

            crossover.processTrack(
                inputL = input,
                inputR = input,
                lowL = lowOut,
                lowR = lowOut,
                highL = highOut,
                highR = highOut,
                length = numSamples,
            )

            // Measure steady-state magnitude (RMS) of sum vs input (skip first 100ms transient)
            val steadyStart = 4800
            val inputRms = sqrt(input.slice(steadyStart until numSamples).map { it * it }.average()).toFloat()
            val sumRms = sqrt((steadyStart until numSamples).map { i ->
                val s = lowOut[i] + highOut[i]
                s * s
            }.average()).toFloat()

            val magnitudeRatio = sumRms / inputRms
            val deviationDb = abs(20.0 * log10(magnitudeRatio.toDouble()))

            // Summed magnitude response must be flat within 0.05 dB
            assertTrue(
                deviationDb < 0.05,
                "frequency ${freq}Hz: magnitude deviation was ${deviationDb}dB (ratio $magnitudeRatio), expected < 0.05dB",
            )
        }
    }

    @Test
    fun `LR4 isolates sub-bass below 100Hz and suppresses high-pass`() {
        val crossover = CrossoverLR4(sampleRate = sampleRate, crossoverHz = 200f)
        val numSamples = 2400
        val input = FloatArray(numSamples)
        val omega = 2.0 * PI * 60.0 / sampleRate // 60Hz kick bass
        for (i in 0 until numSamples) input[i] = sin(omega * i).toFloat()

        val lowOut = FloatArray(numSamples)
        val highOut = FloatArray(numSamples)
        crossover.processTrack(input, input, lowOut, lowOut, highOut, highOut, numSamples)

        val steadyStart = (0.02 * sampleRate).toInt()
        val lowRms = sqrt(lowOut.slice(steadyStart until numSamples).map { it * it }.average()).toFloat()
        val highRms = sqrt(highOut.slice(steadyStart until numSamples).map { it * it }.average()).toFloat()

        assertTrue(lowRms > 0.65f, "low RMS must pass 60Hz bass (got $lowRms)")
        assertTrue(highRms < 0.15f, "high RMS must heavily attenuate 60Hz bass (got $highRms)")
    }

    @Test
    fun `preRoll warms filter state so entry chunk has no startup transient`() {
        val warmCrossover = CrossoverLR4(sampleRate = sampleRate, crossoverHz = 200f)
        val coldCrossover = CrossoverLR4(sampleRate = sampleRate, crossoverHz = 200f)
        val omega = 2.0 * PI * 60.0 / sampleRate
        val preRollSamples = 4800
        val preRollBuf = FloatArray(preRollSamples) { i -> sin(omega * i).toFloat() }
        warmCrossover.preRoll(arrayOf(preRollBuf, preRollBuf), preRollSamples)

        val chunkSamples = 960
        val chunk = FloatArray(chunkSamples) { i -> sin(omega * (preRollSamples + i)).toFloat() }
        val warmLow = FloatArray(chunkSamples)
        val warmHigh = FloatArray(chunkSamples)
        val coldLow = FloatArray(chunkSamples)
        val coldHigh = FloatArray(chunkSamples)

        warmCrossover.processTrack(chunk, chunk, warmLow, warmLow, warmHigh, warmHigh, chunkSamples)
        coldCrossover.processTrack(chunk, chunk, coldLow, coldLow, coldHigh, coldHigh, chunkSamples)

        // Warmed crossover low-band RMS on the very first 5ms (240 samples) must be higher than cold start
        val warmEarlyRms = sqrt(warmLow.slice(0 until 240).map { it * it }.average()).toFloat()
        val coldEarlyRms = sqrt(coldLow.slice(0 until 240).map { it * it }.average()).toFloat()
        assertTrue(warmEarlyRms > coldEarlyRms, "pre-rolled crossover must eliminate cold-start attenuation ($warmEarlyRms vs $coldEarlyRms)")
    }
}

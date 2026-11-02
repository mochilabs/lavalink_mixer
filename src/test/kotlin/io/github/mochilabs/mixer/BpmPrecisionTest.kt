package io.github.mochilabs.mixer

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BpmPrecisionTest {
    private val sampleRate = 48000

    private fun pulseTrack(
        bpm: Double,
        seconds: Int = 30,
        offsetMs: Double = 250.0,
        jitterMs: Double = 0.0,
        rate: Int = sampleRate,
    ): FloatArray {
        val pcm = FloatArray(seconds * rate)
        val random = Random(91)
        var beat = 0
        while (true) {
            val timeMs = offsetMs + beat * 60_000.0 / bpm
            if (timeMs >= seconds * 1000) break
            val jitter = if (jitterMs > 0.0) (random.nextDouble() * 2 - 1) * jitterMs else 0.0
            addKick(pcm, ((timeMs + jitter) * rate / 1000).toInt(), 0.85f, rate)
            beat++
        }
        return pcm
    }

    private fun addKick(pcm: FloatArray, at: Int, amplitude: Float, rate: Int = sampleRate) {
        for (i in 0 until (0.12 * rate).toInt()) {
            if (at + i !in pcm.indices) continue
            pcm[at + i] += amplitude * (exp(-i / (0.03 * rate)) * sin(2 * PI * 60 * i / rate)).toFloat()
        }
    }

    private fun drumTrack(
        bpm: Double,
        hatRatio: Float = 0f,
        seconds: Int = 30,
        rate: Int = sampleRate,
        offsetMs: Double = 250.0,
        seed: Int = 0,
    ): FloatArray {
        val pcm = FloatArray(seconds * rate)
        var beat = 0
        while (true) {
            val at = ((offsetMs + beat * 60_000.0 / bpm) * rate / 1000).toInt()
            if (at >= pcm.size) break
            if (beat % 2 == 0) {
                addKick(pcm, at, 0.85f, rate)
            } else {
                val random = Random(seed + beat)
                for (i in 0 until minOf((0.08 * rate).toInt(), pcm.size - at)) {
                    val signal = 0.3 * sin(2 * PI * 200 * i / rate) + 0.7 * (random.nextFloat() * 2 - 1)
                    pcm[at + i] += (0.40 * exp(-i / (0.02 * rate)) * signal).toFloat()
                }
            }
            val hatAt = at + (rate * 30.0 / bpm).toInt()
            val random = Random(seed + beat * 10)
            for (i in 0 until minOf((0.02 * rate).toInt(), pcm.size - hatAt)) {
                pcm[hatAt + i] += 0.85f * hatRatio * exp(-i / (0.005 * rate)).toFloat() * (random.nextFloat() * 2 - 1)
            }
            beat++
        }
        return pcm
    }

    private fun frames(pcm: FloatArray) = listOf(arrayOf(pcm))

    private fun phaseErrorMs(grid: BeatGrid, expectedMs: Double): Double {
        val period = 60_000.0 / grid.bpm
        return abs(((grid.phaseMs - expectedMs + period / 2) % period + period) % period - period / 2)
    }

    @Test
    fun `drum tempo and confidence survive quiet levels`() {
        val pcm = drumTrack(128.0)
        val peak = pcm.maxOf { abs(it) }
        val results = listOf(-6, -18, -30).map { db ->
            val gain = 10.0.pow(db / 20.0).toFloat() / peak
            val result = assertNotNull(BpmDetector.analyze(frames(FloatArray(pcm.size) { pcm[it] * gain })), "$db dBFS")
            println("level_dbfs=$db bpm=${result.bpm} confidence=${result.confidence}")
            assertTrue(abs(result.bpm - 128f) < 0.5f, "$db dBFS: $result")
            result
        }
        assertTrue(results.maxOf { it.bpm } - results.minOf { it.bpm } < 0.1f)
        assertTrue(results.maxOf { it.confidence } - results.minOf { it.confidence } < 1e-5f)
    }

    @Test
    fun `noise and pure tones are rejected at every level`() {
        val random = Random(7)
        val noise = FloatArray(12 * sampleRate) { random.nextFloat() * 2 - 1 }
        val tone = FloatArray(noise.size) { sin(2 * PI * 440 * it / sampleRate).toFloat() }
        for ((name, pcm) in listOf("noise" to noise, "tone" to tone)) {
            for (db in listOf(-6, -18, -30)) {
                val gain = 10.0.pow(db / 20.0).toFloat()
                val result = BpmDetector.analyze(frames(FloatArray(pcm.size) { pcm[it] * gain }))
                assertTrue(result == null || result.confidence == 0f, "$name at $db dBFS: $result")
            }
        }
    }

    @Test
    fun `slow kick snare tempos preserve their octave`() {
        for (bpm in listOf(70.0, 75.0, 80.0, 85.0, 90.0)) {
            val result = assertNotNull(BpmDetector.analyze(frames(drumTrack(bpm))))
            assertTrue(abs(result.bpm - bpm) < 0.5, "$bpm BPM: $result")
        }
    }

    @Test
    fun `hat amplitude sweep preserves quarter note tempo`() {
        val failures = mutableListOf<String>()
        for (bpm in listOf(70.0, 90.0)) {
            for (ratio in listOf(0.3f, 0.4f, 0.5f, 0.6f, 0.7f, 0.8f)) {
                val result = assertNotNull(BpmDetector.analyzeGrid(frames(drumTrack(bpm, ratio))))
                println("hat_ratio=$ratio reference_bpm=$bpm bpm=${result.bpm} phase_ms=${result.phaseMs}")
                if (abs(result.bpm - bpm) >= 0.5) {
                    failures.add("$bpm BPM hat ratio $ratio: $result")
                }
                if (phaseErrorMs(result, 250.0) > 15.0) failures.add("offbeat phase at $bpm BPM hat ratio $ratio: $result")
            }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun `strong subdivisions preserve slow and fast quarter note tempos`() {
        val failures = mutableListOf<String>()
        for (bpm in listOf(65.0, 80.0, 110.0, 140.0, 150.0, 174.0)) {
            for (ratio in listOf(0.3f, 0.8f)) {
                val pcm = drumTrack(bpm, ratio, seconds = 16, offsetMs = 137.0, seed = 43)
                val grid = assertNotNull(BpmDetector.analyzeGrid(frames(pcm)))
                println("additional_bpm=$bpm hat_ratio=$ratio bpm=${grid.bpm} phase_ms=${grid.phaseMs}")
                if (abs(grid.bpm - bpm) >= 0.5) failures.add("$bpm BPM hat ratio $ratio: $grid")
                if (phaseErrorMs(grid, 137.0) > 15.0) failures.add("offbeat phase at $bpm BPM hat ratio $ratio: $grid")
            }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun `strong hats survive scan length sample rate gain and chunked anti phase stereo`() {
        for (rate in listOf(44100, 48000)) {
            for (seconds in listOf(12, 30)) {
                val pcm = drumTrack(70.0, 0.8f, seconds, rate, offsetMs = 137.0, seed = 73)
                val mono = assertNotNull(BpmDetector.analyzeGrid(frames(pcm), sampleRate = rate))
                val gain = 10.0.pow(-30.0 / 20.0).toFloat()
                val chunks = mutableListOf<Array<FloatArray>>()
                var pos = 0
                for (size in generateSequence(137) { if (it == 137) 2048 else 137 }) {
                    if (pos >= pcm.size) break
                    val n = minOf(size, pcm.size - pos)
                    chunks.add(arrayOf(FloatArray(n) { pcm[pos + it] * gain }, FloatArray(n) { -pcm[pos + it] * gain }))
                    pos += n
                }
                val stereo = assertNotNull(BpmDetector.analyzeGrid(chunks, sampleRate = rate))
                assertTrue(abs(mono.bpm - 70.0) < 0.5, "$rate Hz $seconds seconds mono: $mono")
                assertTrue(phaseErrorMs(mono, 137.0) <= 15.0, "$rate Hz $seconds seconds phase: $mono")
                assertEquals(mono.bpm, stereo.bpm)
                assertEquals(mono.phaseMs, stereo.phaseMs)
                assertTrue(abs(mono.confidence - stereo.confidence) < 1e-5, "$rate Hz $seconds seconds: $mono / $stereo")
            }
        }
    }

    @Test
    fun `integer 128 bpm snaps exactly`() {
        val grid = assertNotNull(BpmDetector.analyzeGrid(frames(pulseTrack(128.0))))
        assertEquals(128f, grid.bpm)
    }

    @Test
    fun `120 point 10 bpm rejects integer snap`() {
        val grid = assertNotNull(BpmDetector.analyzeGrid(frames(pulseTrack(120.10))))
        assertTrue(abs(grid.bpm - 120.10) < 0.03, "bpm=${grid.bpm}")
        assertTrue(grid.bpm != 120f)
    }

    @Test
    fun `120 point 03 bpm accepts integer snap within residual tolerance`() {
        val grid = assertNotNull(BpmDetector.analyzeGrid(frames(pulseTrack(120.03))))
        assertEquals(120f, grid.bpm)
    }

    @Test
    fun `jittered 127 point 6 bpm stays fractional`() {
        val grid = assertNotNull(BpmDetector.analyzeGrid(frames(pulseTrack(127.6, jitterMs = 10.0))))
        assertTrue(abs(grid.bpm - 127.6) < 0.1, "bpm=${grid.bpm}")
        assertTrue(grid.bpm != round(grid.bpm))
    }

    @Test
    fun `short beat span does not snap a fractional tempo`() {
        val grid = assertNotNull(BpmDetector.analyzeGrid(frames(pulseTrack(128.08, seconds = 5))))
        assertTrue(abs(grid.bpm - 128.08) < 0.15, "bpm=${grid.bpm}")
        assertTrue(grid.bpm != 128f)
    }

    @Test
    fun `signed kick phase error stays within three ms across sample rates and offsets`() {
        for (rate in listOf(44100, 48000)) {
            val errors = (0..9).map { offset ->
                val offsetMs = 250.0 + offset
                val grid = assertNotNull(BpmDetector.analyzeGrid(frames(pulseTrack(120.0, offsetMs = offsetMs, rate = rate)), sampleRate = rate))
                val error = ((grid.phaseMs - offsetMs + 250.0) % 500.0 + 500.0) % 500.0 - 250.0
                assertTrue(abs(error) <= 3.0, "$rate Hz offset=$offsetMs error=$error ms")
                error
            }
            println("phase_sr=$rate mean_error_ms=${errors.average()} min_ms=${errors.min()} max_ms=${errors.max()}")
        }
    }

    @Test
    fun `energy fallback has no spectral onset bias`() {
        val rate = 4000
        val pcm = FloatArray(12 * rate)
        for (at in rate / 4 until pcm.size step rate / 2) {
            for (i in 0 until 4) pcm[at + i] = 1f
        }
        val grid = assertNotNull(BpmDetector.analyzeGrid(frames(pcm), sampleRate = rate))
        assertEquals(120f, grid.bpm)
        assertEquals(250L, grid.phaseMs)
    }

    @Test
    fun `twelve and thirty second scans keep steady tempo and expose fractional drift`() {
        for (seconds in listOf(12, 30)) {
            for (bpm in listOf(70.0, 90.0, 120.03, 120.1, 128.0, 150.0)) {
                val grid = assertNotNull(BpmDetector.analyzeGrid(frames(pulseTrack(bpm, seconds))))
                println("scan_seconds=$seconds reference_bpm=$bpm bpm=${grid.bpm} phase_ms=${grid.phaseMs}")
                assertTrue(abs(grid.bpm - bpm) <= 0.15, "$seconds seconds $bpm BPM: $grid")
            }
        }
    }
}

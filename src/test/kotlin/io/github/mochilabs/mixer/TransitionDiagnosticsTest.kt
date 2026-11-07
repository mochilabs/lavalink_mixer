package io.github.mochilabs.mixer

import ch.qos.logback.classic.Level
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertTrue

class TransitionDiagnosticsTest {
    @Test
    fun `active underflow is quiet at INFO and remains visible at DEBUG`() {
        val manager = DefaultAudioPlayerManager()
        val mixer = GuildMixer(77801L, manager, MixerConfig(), java.util.concurrent.Executor { })
        try {
            mixer.beginXfadeForTest(48000)
            TransitionLogCapture(mixer.guildId).use { logs ->
                repeat(10) { mixer.noteUnderrun(960, 960) }
                assertTrue(logs.messages.isEmpty(), logs.messages.toString())
            }
            TransitionLogCapture(mixer.guildId, debug = true).use { logs ->
                mixer.noteUnderrun(960, 960)
                assertTrue(logs.messages.single().first == Level.DEBUG)
                assertTrue(logs.messages.single().second.contains("crossfade sub starvation"))
            }
        } finally {
            mixer.destroy()
            manager.shutdown()
        }
    }

    @Test
    fun `log describes outgoing tail and incoming head with measured key and confidence`() {
        val outgoing = "diagnostic-out-${UUID.randomUUID()}"
        val incoming = "diagnostic-in-${UUID.randomUUID()}"
        AnalysisCaches.bpmHead[outgoing] = ExtendedBeatGrid(90f, 100L * 48L, 0.2f)
        AnalysisCaches.bpmTail[outgoing] = ExtendedBeatGrid(120f, 2100L * 48L, 0.9f)
        AnalysisCaches.bpmHead[incoming] = ExtendedBeatGrid(125f, 50L * 48L, 0.8f)
        AnalysisCaches.featuresTail[outgoing] = TransitionFeatures(2000, 8000, emptyList(), 0.2f, 0.95f,
            KeyEstimate.Candidate(7, KeyMode.MINOR, 0.7f), emptyList())
        AnalysisCaches.featuresHead[incoming] = TransitionFeatures(0, 4000, emptyList(), 0.1f, 0.85f,
            KeyEstimate.Candidate(0, KeyMode.MAJOR, 0.6f), emptyList())
        val log = transitionAnalysisLog(outgoing, incoming)
        assertTrue(log.contains("bpmMethod=onset_acf_wls keyMethod=chroma_profile"))
        assertTrue(log.contains("outgoing={bpm=120.0 bpmConfidence=0.9 beatPhaseMs=2100 key=G:minor keyConfidence=0.7"), log)
        assertTrue(log.contains("keyWindowMs=2000..8000 featureWindowMs=2000..8000 rms=0.2 rhythmStability=0.95"), log)
        assertTrue(log.contains("incoming={bpm=125.0 bpmConfidence=0.8 beatPhaseMs=50 key=C:major keyConfidence=0.6"), log)
        assertTrue(log.contains("featureWindowMs=0..4000 rms=0.1 rhythmStability=0.85"), log)
    }

    @Test
    fun `unavailable and ambiguous analysis remain explicit instead of invented numbers`() {
        val id = "diagnostic-key-${UUID.randomUUID()}"
        AnalysisCaches.key[id] = KeyAnalysis(0, 5000, KeyEstimate.Unknown("ambiguous_key"))
        val log = transitionAnalysisLog(null, id)
        assertTrue(log.contains("outgoing={bpm=unknown bpmConfidence=unknown beatPhaseMs=unknown key=unknown(not_analyzed)"), log)
        assertTrue(log.contains("incoming={bpm=unknown bpmConfidence=unknown beatPhaseMs=unknown key=unknown(ambiguous_key)"), log)
        assertTrue(log.contains("keyWindowMs=0..5000 featureWindowMs=unknown rms=unknown rhythmStability=unknown featureStatus=not_analyzed"), log)
    }
}

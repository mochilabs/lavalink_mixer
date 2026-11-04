package io.github.mochilabs.mixer

private val diagnosticPitchNames = listOf("C", "Db", "D", "Eb", "E", "F", "Gb", "G", "Ab", "A", "Bb", "B")

/** Snapshot cached planning inputs without another decode or analysis pass. */
internal fun transitionAnalysisLog(outgoingId: String?, incomingId: String?): String {
    fun describe(id: String?, outgoing: Boolean): String {
        val grid = id?.let { if (outgoing) AnalysisCaches.bpmTail[it] ?: AnalysisCaches.bpmHead[it] else AnalysisCaches.bpmHead[it] }
        val features = id?.let { if (outgoing) AnalysisCaches.featuresTail[it] else AnalysisCaches.featuresHead[it] }
        val key = features?.let { KeyAnalysis(it.windowStartMs, it.windowEndMs, it.key) } ?: id?.let { AnalysisCaches.key[it] }
        val keyDescription = when (val estimate = key?.estimate) {
            is KeyEstimate.Candidate -> "${diagnosticPitchNames[estimate.pitchClass]}:${estimate.mode.name.lowercase()} keyConfidence=${estimate.confidence}"
            is KeyEstimate.Unknown -> "unknown(${estimate.reason})"
            null -> "unknown(not_analyzed)"
        }
        return "bpm=${grid?.bpm ?: "unknown"} bpmConfidence=${grid?.confidence ?: "unknown"} " +
            "beatPhaseMs=${grid?.phaseMs ?: "unknown"} key=$keyDescription " +
            "keyWindowMs=${key?.let { "${it.windowStartMs}..${it.windowEndMs}" } ?: "unknown"} " +
            "featureWindowMs=${features?.let { "${it.windowStartMs}..${it.windowEndMs}" } ?: "unknown"} " +
            "rms=${features?.rms ?: "unknown"} rhythmStability=${features?.rhythmicStability ?: "unknown"} " +
            "featureStatus=${features?.unknownReason ?: if (features == null) "not_analyzed" else "ready"}"
    }
    return "bpmMethod=onset_acf_wls keyMethod=chroma_profile " +
        "outgoing={${describe(outgoingId, true)}} incoming={${describe(incomingId, false)}}"
}

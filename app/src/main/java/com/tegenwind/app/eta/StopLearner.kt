package com.tegenwind.app.eta

/** The time standing still the ETA allows for on a segment, and how unsure that is. */
data class StopExpectation(val meanS: Double, val varS2: Double)

/**
 * Where you stand still, learned from your own rides instead of taken from the map. The map says
 * where traffic lights are, but not whether your way passes them (a light on the carriageway beside
 * a cycle path), nor where else you wait (a crossing, a bridge). Each segment's recent passes say
 * how long you really stood there.
 */
object StopLearner {
    /** The latest passes of a segment that count. */
    const val RECENT = 10

    /** With at least this many passes and no standing in any of them, the map's lights there aren't yours. */
    const val MIN_PASSES = 3

    /** Standing shorter than this is a foot down, not a wait. */
    const val STOOD_S = 2.0

    /** A longer stand than this is a break, not something to expect next time. */
    const val MAX_S = 120.0

    /** The map's guess counts as this many passes, so it fades as your own passes come in. */
    private const val PRIOR_PASSES = 2.0

    /** True when the passes say the lights the map puts on this segment never stop you. */
    fun ignoresLights(standingS: List<Double>): Boolean =
        standingS.size >= MIN_PASSES && standingS.all { it < STOOD_S }

    /** The lights that count on a segment the map gives [signals]: none once [ignoresLights]. */
    fun lights(standingS: List<Double>, signals: Int): Int = if (ignoresLights(standingS)) 0 else signals

    /**
     * What to expect on a segment given how long you stood on its latest passes ([standingS],
     * seconds) and the lights the map puts there. Without passes it is the map's guess: every light
     * costs [perLightMeanS] on average. With passes it moves to their average, lights or not.
     */
    fun expect(standingS: List<Double>, signals: Int, perLightMeanS: Double, perLightVarS2: Double): StopExpectation {
        if (ignoresLights(standingS)) return StopExpectation(0.0, 0.0)
        val x = standingS.map { it.coerceIn(0.0, MAX_S) }
        val priorMean = signals * perLightMeanS
        val priorVar = signals * perLightVarS2
        if (x.isEmpty()) return StopExpectation(priorMean, priorVar)
        val mean = (x.sum() + PRIOR_PASSES * priorMean) / (x.size + PRIOR_PASSES)
        val variance = (x.sumOf { (it - mean) * (it - mean) } + PRIOR_PASSES * priorVar) / (x.size + PRIOR_PASSES)
        return StopExpectation(mean, variance)
    }
}

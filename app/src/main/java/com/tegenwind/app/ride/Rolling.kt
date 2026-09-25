package com.tegenwind.app.ride

/** One measurement on a time axis, e.g. speed in km/h or heart rate in bpm. */
data class Sample(val timeMs: Long, val value: Double)

/**
 * Rolling median at every sample of a time-sorted series, over the preceding [windowMs].
 * Gaps in the data shorten the window instead of stretching it, and the median rides through a
 * GPS spike that a mean would smear over the whole window.
 */
fun rollingMedian(samples: List<Sample>, windowMs: Long): DoubleArray {
    val n = samples.size
    val median = DoubleArray(n)
    val window = DoubleArray(n)
    var start = 0
    for (i in 0 until n) {
        while (samples[start].timeMs <= samples[i].timeMs - windowMs) start++
        val size = i - start + 1
        for (j in 0 until size) window[j] = samples[start + j].value
        java.util.Arrays.sort(window, 0, size)
        median[i] = if (size % 2 == 1) window[size / 2] else (window[size / 2 - 1] + window[size / 2]) / 2
    }
    return median
}

const val TWO_MINUTES_MS = 120_000L

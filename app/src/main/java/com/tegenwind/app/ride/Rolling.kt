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

/**
 * Rolling mean at every sample of a time-sorted series, centred: the average of the samples within
 * half of [windowMs] on either side. With [complete] false (a ride still going) the last half
 * window has no value yet, NaN, because the samples it needs haven't arrived; a finished series
 * averages over what there is.
 */
fun rollingMean(samples: List<Sample>, windowMs: Long, complete: Boolean = true): DoubleArray {
    val n = samples.size
    val mean = DoubleArray(n)
    val half = windowMs / 2
    val prefix = DoubleArray(n + 1)
    for (i in 0 until n) prefix[i + 1] = prefix[i] + samples[i].value
    var start = 0
    var end = 0 // exclusive
    for (i in 0 until n) {
        val t = samples[i].timeMs
        if (!complete && t + half > samples[n - 1].timeMs) {
            mean[i] = Double.NaN
            continue
        }
        while (samples[start].timeMs < t - half) start++
        while (end < n && samples[end].timeMs <= t + half) end++
        mean[i] = (prefix[end] - prefix[start]) / (end - start)
    }
    return mean
}

const val TWO_MINUTES_MS = 120_000L

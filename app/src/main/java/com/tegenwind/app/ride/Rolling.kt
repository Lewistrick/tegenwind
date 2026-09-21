package com.tegenwind.app.ride

/** One measurement on a time axis, e.g. speed in km/h or heart rate in bpm. */
data class Sample(val timeMs: Long, val value: Double)

/**
 * Time-based rolling median: gaps in the data shorten the window instead of stretching it.
 * The median rides through a GPS spike or one long wait that a mean would smear over two minutes.
 */
class RollingMedian(private val windowMs: Long) {
    private val times = ArrayDeque<Long>()
    private val values = ArrayDeque<Double>()

    fun add(timeMs: Long, value: Double): Double {
        times.addLast(timeMs)
        values.addLast(value)
        while (times.first() <= timeMs - windowMs) {
            times.removeFirst()
            values.removeFirst()
        }
        val sorted = values.toDoubleArray()
        sorted.sort()
        return quantile(sorted, sorted.size, 0.5)
    }
}

/** The middle of the data and the range most of it sits in, at every sample. */
class Bands(val p25: DoubleArray, val median: DoubleArray, val p75: DoubleArray)

/** Rolling quartiles at every sample of a time-sorted series, over the preceding [windowMs]. */
fun rollingBands(samples: List<Sample>, windowMs: Long): Bands {
    val n = samples.size
    val p25 = DoubleArray(n)
    val median = DoubleArray(n)
    val p75 = DoubleArray(n)
    val window = DoubleArray(n)
    var start = 0
    for (i in 0 until n) {
        while (samples[start].timeMs <= samples[i].timeMs - windowMs) start++
        val size = i - start + 1
        for (j in 0 until size) window[j] = samples[start + j].value
        java.util.Arrays.sort(window, 0, size)
        p25[i] = quantile(window, size, 0.25)
        median[i] = quantile(window, size, 0.5)
        p75[i] = quantile(window, size, 0.75)
    }
    return Bands(p25, median, p75)
}

/** Quantile [q] of the first [size] values of a sorted array, interpolating between neighbours. */
private fun quantile(sorted: DoubleArray, size: Int, q: Double): Double {
    if (size == 1) return sorted[0]
    val pos = q * (size - 1)
    val below = pos.toInt()
    val above = (below + 1).coerceAtMost(size - 1)
    return sorted[below] + (pos - below) * (sorted[above] - sorted[below])
}

const val TWO_MINUTES_MS = 120_000L

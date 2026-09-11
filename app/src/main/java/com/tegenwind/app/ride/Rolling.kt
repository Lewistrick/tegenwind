package com.tegenwind.app.ride

/** One measurement on a time axis, e.g. speed in km/h or heart rate in bpm. */
data class Sample(val timeMs: Long, val value: Double)

/** Time-based rolling average: gaps in the data shorten the window instead of stretching it. */
class RollingWindow(private val windowMs: Long) {
    private val times = ArrayDeque<Long>()
    private val values = ArrayDeque<Double>()
    private var sum = 0.0

    fun add(timeMs: Long, value: Double): Double {
        times.addLast(timeMs)
        values.addLast(value)
        sum += value
        while (times.first() <= timeMs - windowMs) {
            times.removeFirst()
            sum -= values.removeFirst()
        }
        return sum / values.size
    }
}

/** Rolling average at every sample of a time-sorted series, over the preceding [windowMs]. */
fun rollingAverage(samples: List<Sample>, windowMs: Long): DoubleArray {
    val out = DoubleArray(samples.size)
    var start = 0
    var sum = 0.0
    for (i in samples.indices) {
        sum += samples[i].value
        while (samples[start].timeMs <= samples[i].timeMs - windowMs) {
            sum -= samples[start].value
            start++
        }
        out[i] = sum / (i - start + 1)
    }
    return out
}

const val TWO_MINUTES_MS = 120_000L

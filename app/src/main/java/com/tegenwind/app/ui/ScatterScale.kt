package com.tegenwind.app.ui

import com.tegenwind.app.ride.FormDot
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/** One axis of the scatterplot: its range and the whole-number step between its ticks. */
data class ScatterAxis(val lo: Double, val hi: Double, val step: Double) {
    /** The values to label, on multiples of [step] inside the range. */
    fun ticks(): List<Double> {
        val out = ArrayList<Double>()
        var v = ceil(lo / step) * step
        while (v <= hi + 1e-9) {
            out += v
            v += step
        }
        return out
    }
}

/**
 * Where the form-and-arrival scatterplot's axes go. Both are centred on the newest dot and wide
 * enough for every dot, with a little room round the farthest one; a minimum width keeps a steady
 * rider's plot from zooming in on noise. Arrival is in minutes, form in percent from 1.0, so the
 * ticks are whole minutes and whole percent.
 */
object ScatterScale {
    const val MIN_HALF_MINUTES = 1.0
    const val MIN_HALF_PERCENT = 1.5
    private const val ROOM = 1.25

    fun minutes(d: FormDot) = d.arrivalMs / 60_000.0
    fun percent(d: FormDot) = (d.form - 1) * 100

    /** Null with nothing to place. [dots] are oldest first. */
    fun fit(dots: List<FormDot>): Pair<ScatterAxis, ScatterAxis>? {
        val newest = dots.lastOrNull() ?: return null
        return axis(dots.map(::minutes), minutes(newest), MIN_HALF_MINUTES) to
            axis(dots.map(::percent), percent(newest), MIN_HALF_PERCENT)
    }

    private fun axis(values: List<Double>, centre: Double, minHalf: Double): ScatterAxis {
        val half = max(values.maxOf { abs(it - centre) } * ROOM, minHalf)
        val lo = centre - half
        val hi = centre + half
        return ScatterAxis(lo, hi, niceIntStep(lo, hi))
    }
}

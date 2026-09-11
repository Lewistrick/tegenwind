package com.tegenwind.app.eta

import kotlin.math.abs
import kotlin.math.ln

/** Rider + bike. Defaults fit an upright commuter on a city/hybrid bike. */
data class RiderParams(
    /** Rider plus bike plus bag, kg. */
    val massKg: Double = 90.0,
    /** Drag area: upright ~0.5, a bit crouched ~0.4. */
    val cdA: Double = 0.45,
    val crr: Double = 0.006,
    /** Typical cruising power at the pedals, W: ~25 km/h on flat ground without wind. */
    val powerW: Double = 130.0,
)

object Physics {
    private const val G = 9.81

    /** Air density from temperature (sea-level pressure; the Netherlands is flat enough). */
    fun airDensity(tempC: Double?): Double = 1.225 * 288.15 / (273.15 + (tempC ?: 15.0))

    /**
     * Speed (m/s) at which the rider's power balances air drag, rolling resistance and gravity:
     * P = ½·ρ·CdA·(v + w)²·v + Crr·m·g·v + m·g·grade·v, solved by bisection.
     * [headwindMps] is positive against you, negative from behind.
     */
    fun speedMps(powerW: Double, gradePct: Double, headwindMps: Double, rho: Double, p: RiderParams): Double {
        var lo = 0.3
        var hi = MAX_SPEED_MPS
        repeat(40) {
            val v = (lo + hi) / 2
            val air = v + headwindMps
            val need = 0.5 * rho * p.cdA * air * abs(air) * v +
                p.crr * p.massKg * G * v +
                p.massKg * G * (gradePct / 100) * v
            if (need > powerW) hi = v else lo = v
        }
        return (lo + hi) / 2
    }

    /**
     * How much of the 10 m forecast wind reaches a cyclist (~1.5 m up), from a log wind profile.
     * Open fields (exposure 1) have little surface roughness: ~65% of the wind remains.
     * Between buildings (exposure ~0.15) roughness is high: ~30% remains.
     */
    fun riderWindFactor(exposure: Double): Double {
        val e = exposure.coerceIn(0.0, 1.0)
        val z0 = kotlin.math.exp(ln(Z0_OPEN) + (1 - e) * (ln(Z0_TOWN) - ln(Z0_OPEN)))
        return (ln(RIDER_HEIGHT_M / z0) / ln(10.0 / z0)).coerceIn(0.2, 1.0)
    }

    /** Wind component against the direction of travel, at rider height. Positive = headwind. */
    fun headwindMps(windSpeed10Mps: Double, windFromDeg: Double, bearingDeg: Double, exposure: Double): Double =
        windSpeed10Mps * riderWindFactor(exposure) * kotlin.math.cos(Math.toRadians(windFromDeg - bearingDeg))

    const val MAX_SPEED_MPS = 11.0 // 40 km/h: nobody commutes faster downhill with a tailwind
    private const val RIDER_HEIGHT_M = 1.5
    private const val Z0_OPEN = 0.03
    private const val Z0_TOWN = 1.0
}

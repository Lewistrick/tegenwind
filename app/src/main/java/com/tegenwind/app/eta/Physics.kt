package com.tegenwind.app.eta

import kotlin.math.abs
import kotlin.math.ln

/**
 * Rider + bike, as measured or guessed for the one rider this app is for (15 Oct 2026): 84 kg on a
 * Gazelle Orange, a Dutch city bike with hub gears, a hub dynamo, 28" city tyres pumped to about 5
 * bar, locks, a backpack and a pannier. Sitting fully upright.
 */
data class RiderParams(
    /** Rider 84 kg, bike with its locks about 20 kg, backpack and pannier 5–8 kg. */
    val massKg: Double = 110.0,
    /** Drag area, m²: fully upright behind swept-back bars, with a backpack: ~0.55–0.6. */
    val cdA: Double = 0.58,
    /** City tyres of ~37–40 mm at about 5 bar. */
    val crr: Double = 0.0075,
    /** Share of the power at the pedals that reaches the road: a hub gear and a hub dynamo take ~6%. */
    val drivetrainEfficiency: Double = 0.94,
    /**
     * The model rider's speed on flat ground without wind, km/h: the yardstick form is a share of.
     * 1.0 form is this speed; it stays 25 so the forms of stored rides keep their meaning.
     */
    val referenceKmh: Double = 25.0,
) {
    /** Pedal power that holds [referenceKmh] on this bike on flat ground in still air at 15 °C. */
    val powerW: Double get() = Physics.pedalPowerW(referenceKmh / 3.6, 0.0, 0.0, Physics.airDensity(null), this)

    /**
     * The pedal power behind a [form]: what holds form × [referenceKmh] on this bike on flat ground in
     * still air. Form is already corrected for wind, slope and each road's own cost, so this is the
     * effort, not the speed of the moment. An estimate: nothing measures the force on the pedals.
     */
    fun powerAtFormW(form: Double): Double =
        Physics.pedalPowerW(form * referenceKmh / 3.6, 0.0, 0.0, Physics.airDensity(null), this)
}

object Physics {
    private const val G = 9.81

    /** Air density from temperature (sea-level pressure; the Netherlands is flat enough). */
    fun airDensity(tempC: Double?): Double = 1.225 * 288.15 / (273.15 + (tempC ?: 15.0))

    /**
     * Power at the pedals (W) to ride at [speedMps] against air drag, rolling resistance and gravity,
     * after the drivetrain's losses: P = (½·ρ·CdA·(v + w)²·v + Crr·m·g·v + m·g·grade·v) / efficiency.
     * [headwindMps] is positive against you, negative from behind.
     */
    fun pedalPowerW(speedMps: Double, gradePct: Double, headwindMps: Double, rho: Double, p: RiderParams): Double {
        val v = speedMps
        val air = v + headwindMps
        val atWheel = 0.5 * rho * p.cdA * air * abs(air) * v +
            p.crr * p.massKg * G * v +
            p.massKg * G * (gradePct / 100) * v
        return atWheel / p.drivetrainEfficiency
    }

    /** Speed (m/s) at which [powerW] at the pedals balances the road: [pedalPowerW] solved by bisection. */
    fun speedMps(powerW: Double, gradePct: Double, headwindMps: Double, rho: Double, p: RiderParams): Double {
        var lo = 0.3
        var hi = MAX_SPEED_MPS
        repeat(40) {
            val v = (lo + hi) / 2
            if (pedalPowerW(v, gradePct, headwindMps, rho, p) > powerW) hi = v else lo = v
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

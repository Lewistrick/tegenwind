package com.tegenwind.app.eta

import kotlin.math.abs
import kotlin.math.ln

/**
 * Rider + bike, as the physics needs them. Built from the rider and bike settings
 * ([com.tegenwind.app.data.RiderSetup]); the defaults are the first setup (15 Oct 2026): 84 kg on a
 * Gazelle Orange, a Dutch city bike with hub gears, a hub dynamo, 28" city tyres at about 5 bar,
 * locks, and the backpack in the pannier, leaning on the handlebars.
 */
data class RiderParams(
    /** Rider, bike with its locks, and luggage, kg. */
    val massKg: Double = 110.0,
    /** Drag area, m²: fully upright on a city bike ~0.55–0.6, leaning on the bars ~0.48, a road bike ~0.4. */
    val cdA: Double = 0.48,
    /** Rolling resistance: fast tyres ~0.004, city tyres ~0.0075, wide or knobbly ~0.010. */
    val crr: Double = 0.0075,
    /** Share of the power at the pedals that reaches the road: hub gears ~0.94, a derailleur ~0.97. */
    val drivetrainEfficiency: Double = 0.94,
    /** What a motor adds at the wheel, W, below [cutoffMps]; 0 without one. */
    val motorW: Double = 0.0,
    /** The speed at which the motor stops helping, m/s. */
    val cutoffMps: Double = 25 / 3.6,
    /** A motor that rides the bike on its own: the model rider doesn't pedal. */
    val motorFull: Boolean = false,
) {
    /** What the model rider puts into the pedals: [MODEL_RIDER_W], or nothing on a bike that rides itself. */
    val powerW: Double get() = if (motorFull) 0.0 else MODEL_RIDER_W

    /** The model rider's speed on this bike on flat ground in still air at 15 °C: form 1.0. */
    fun modelSpeedMps(): Double = Physics.speedMps(powerW, 0.0, 0.0, Physics.airDensity(null), this)

    /**
     * Your pedal power behind a [form]: what holds form × the model's speed on this bike on flat
     * ground in still air, the motor's help taken off. Form is already corrected for wind, slope and
     * each road's own cost, so this is the effort, not the speed of the moment. An estimate: nothing
     * measures the force on the pedals. Null on a bike that rides itself.
     */
    fun powerAtFormW(form: Double): Double? =
        if (motorFull) null
        else Physics.pedalPowerW(form * modelSpeedMps(), 0.0, 0.0, Physics.airDensity(null), this)

    companion object {
        /**
         * The model rider's legs, W: what form measures you against. 165 W is what held 25 km/h on the
         * first setup, the old yardstick, so forms from before settings existed keep their meaning.
         * Fixed rather than tied to a speed, so a lighter rider or a faster bike makes the model
         * faster on the same legs.
         */
        const val MODEL_RIDER_W = 165.0

        /** Over the last this much below its cut-off, a motor's help fades out, m/s. */
        const val MOTOR_FADE_MPS = 2 / 3.6
    }
}

object Physics {
    private const val G = 9.81

    /** Air density from temperature (sea-level pressure; the Netherlands is flat enough). */
    fun airDensity(tempC: Double?): Double = 1.225 * 288.15 / (273.15 + (tempC ?: 15.0))

    /**
     * Power at the pedals (W) to ride at [speedMps] against air drag, rolling resistance and gravity,
     * after what a motor adds and the drivetrain's losses:
     * P = max(0, ½·ρ·CdA·(v + w)²·v + Crr·m·g·v + m·g·grade·v − motor) / efficiency.
     * [headwindMps] is positive against you, negative from behind.
     */
    fun pedalPowerW(speedMps: Double, gradePct: Double, headwindMps: Double, rho: Double, p: RiderParams): Double {
        val v = speedMps
        val air = v + headwindMps
        val atWheel = 0.5 * rho * p.cdA * air * abs(air) * v +
            p.crr * p.massKg * G * v +
            p.massKg * G * (gradePct / 100) * v
        return maxOf(0.0, atWheel - motorW(v, p)) / p.drivetrainEfficiency
    }

    /** What the motor adds at the wheel at [speedMps]: all of it, fading out just below the cut-off, nothing above. */
    fun motorW(speedMps: Double, p: RiderParams): Double {
        if (p.motorW <= 0) return 0.0
        val fade = ((p.cutoffMps - speedMps) / RiderParams.MOTOR_FADE_MPS).coerceIn(0.0, 1.0)
        return p.motorW * fade
    }

    /**
     * Speed (m/s) at which [powerW] at the pedals balances the road: [pedalPowerW] solved by bisection.
     * The motor's help only drops as speed rises, so the balance still has one crossing.
     */
    fun speedMps(powerW: Double, gradePct: Double, headwindMps: Double, rho: Double, p: RiderParams): Double {
        var lo = 0.3
        var hi = maxOf(MAX_SPEED_MPS, p.cutoffMps + 5 / 3.6)
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

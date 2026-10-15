package com.tegenwind.app.data

import com.tegenwind.app.eta.RiderParams
import org.json.JSONArray
import org.json.JSONObject

/** How you sit on a bike: what the wind meets. */
enum class Position(val label: String, val explain: String, val cdA: Double) {
    UPRIGHT("Upright", "Sitting up straight, arms nearly straight.", 0.58),
    LEANING("Leaning on the bars", "Forearms or hands forward, back bent.", 0.48),
    SPORTY("Sporty", "Low and stretched out, as on a racing bike.", 0.40),
}

/** What the tyres cost on the road. */
enum class Tyres(val label: String, val explain: String, val crr: Double) {
    FAST("Fast", "Narrow, smooth and pumped hard, as on a racing bike.", 0.004),
    CITY("City", "Normal city tyres of about 35–40 mm at 4–5 bar.", 0.0075),
    WIDE("Wide or knobbly", "Fat, studded or soft tyres, or a cargo bike's.", 0.010),
}

/** Where the power at the pedals goes before it reaches the road. */
enum class Gears(val label: String, val explain: String, val efficiency: Double) {
    HUB("Hub gears", "Gears inside the rear hub, often with a hub dynamo.", 0.94),
    DERAILLEUR("Derailleur", "An open chain moving between sprockets.", 0.97),
}

/** Whether a motor helps, and how. */
enum class Motor(val label: String, val explain: String) {
    NONE("No motor", "All the power is yours."),
    ASSIST("Helps while you pedal", "An e-bike: the motor adds its power while you pedal, up to its cut-off speed."),
    FULL("Fully motorised", "It rides on its own, up to its cut-off speed; you don't need to pedal."),
}

/** One bike you ride. Its weight includes what stays on it, such as locks. */
data class Bike(
    val id: Long,
    val name: String,
    val weightKg: Double,
    val position: Position = Position.UPRIGHT,
    val tyres: Tyres = Tyres.CITY,
    val gears: Gears = Gears.HUB,
    val motor: Motor = Motor.NONE,
    /** What the motor adds at the wheel, W: a normal e-bike's is 250 by law, its real help often less. */
    val motorW: Double = 250.0,
    /** Where the motor stops helping, km/h: 25 for a normal e-bike, 45 for a speed pedelec. */
    val cutoffKmh: Double = 25.0,
)

/**
 * You and your bikes, as the ETA's physics needs them. Set on the "Rider and bike" screen; until
 * then the first setup, worked out on 15 Oct 2026: 84 kg, 6.5 kg of luggage, on a Gazelle Orange.
 */
data class RiderSetup(
    val riderKg: Double = 84.0,
    /** What you carry: bags, a laptop, shopping. */
    val luggageKg: Double = 6.5,
    val bikes: List<Bike> = listOf(GAZELLE),
    /** The bike the next ride is on. */
    val activeBikeId: Long = GAZELLE.id,
) {
    val activeBike: Bike get() = bikes.firstOrNull { it.id == activeBikeId } ?: bikes.first()

    /** The physics for riding [bike]. */
    fun params(bike: Bike = activeBike): RiderParams = RiderParams(
        massKg = riderKg + bike.weightKg + luggageKg,
        cdA = bike.position.cdA,
        crr = bike.tyres.crr,
        drivetrainEfficiency = bike.gears.efficiency,
        motorW = if (bike.motor == Motor.NONE) 0.0 else bike.motorW,
        cutoffMps = bike.cutoffKmh / 3.6,
        motorFull = bike.motor == Motor.FULL,
    )

    /**
     * Within what a person and a bike can be, at least one bike, and an active one that exists:
     * whatever was typed, the physics gets numbers it can work with.
     */
    fun valid(): RiderSetup {
        val bikes = bikes.map {
            it.copy(
                name = it.name.trim().ifEmpty { "Bike" },
                weightKg = it.weightKg.coerceIn(3.0, 80.0),
                motorW = it.motorW.coerceIn(0.0, 2000.0),
                cutoffKmh = it.cutoffKmh.coerceIn(6.0, 60.0),
            )
        }.ifEmpty { listOf(GAZELLE) }
        return copy(
            riderKg = riderKg.coerceIn(20.0, 250.0),
            luggageKg = luggageKg.coerceIn(0.0, 80.0),
            bikes = bikes,
            activeBikeId = if (bikes.any { it.id == activeBikeId }) activeBikeId else bikes.first().id,
        )
    }

    fun toJson(): String = JSONObject()
        .put("riderKg", riderKg)
        .put("luggageKg", luggageKg)
        .put("activeBikeId", activeBikeId)
        .put("bikes", JSONArray().apply {
            bikes.forEach { b ->
                put(
                    JSONObject()
                        .put("id", b.id).put("name", b.name).put("weightKg", b.weightKg)
                        .put("position", b.position.name).put("tyres", b.tyres.name).put("gears", b.gears.name)
                        .put("motor", b.motor.name).put("motorW", b.motorW).put("cutoffKmh", b.cutoffKmh)
                )
            }
        })
        .toString()

    companion object {
        /** The first bike: a Gazelle Orange, 20 kg with its locks, ridden leaning on the bars. */
        val GAZELLE = Bike(
            id = 1, name = "Gazelle", weightKg = 20.0,
            position = Position.LEANING, tyres = Tyres.CITY, gears = Gears.HUB,
        )

        /** Reads what [toJson] wrote. Anything missing or unknown falls back to the first setup's value. */
        fun fromJson(json: String): RiderSetup {
            val o = JSONObject(json)
            val d = RiderSetup()
            val bikes = o.optJSONArray("bikes")?.let { arr ->
                (0 until arr.length()).map { i ->
                    val b = arr.getJSONObject(i)
                    Bike(
                        id = b.optLong("id", i + 1L),
                        name = b.optString("name", "Bike"),
                        weightKg = b.optDouble("weightKg", GAZELLE.weightKg),
                        position = enumOr(b.optString("position"), Position.UPRIGHT),
                        tyres = enumOr(b.optString("tyres"), Tyres.CITY),
                        gears = enumOr(b.optString("gears"), Gears.HUB),
                        motor = enumOr(b.optString("motor"), Motor.NONE),
                        motorW = b.optDouble("motorW", 250.0),
                        cutoffKmh = b.optDouble("cutoffKmh", 25.0),
                    )
                }
            } ?: d.bikes
            return RiderSetup(
                riderKg = o.optDouble("riderKg", d.riderKg),
                luggageKg = o.optDouble("luggageKg", d.luggageKg),
                bikes = bikes,
                activeBikeId = o.optLong("activeBikeId", bikes.firstOrNull()?.id ?: d.activeBikeId),
            ).valid()
        }

        private inline fun <reified E : Enum<E>> enumOr(name: String, fallback: E): E =
            enumValues<E>().firstOrNull { it.name == name } ?: fallback
    }
}

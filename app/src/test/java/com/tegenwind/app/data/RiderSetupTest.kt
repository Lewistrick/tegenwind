package com.tegenwind.app.data

import com.tegenwind.app.eta.RiderParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RiderSetupTest {

    @Test
    fun theFirstSetupIsTheGazelleTheModelUsedBefore() {
        val p = RiderSetup().params()
        val before = RiderParams()
        assertEquals(before.massKg, p.massKg, 1.0)
        assertEquals(before.cdA, p.cdA, 1e-12)
        assertEquals(before.crr, p.crr, 1e-12)
        assertEquals(before.drivetrainEfficiency, p.drivetrainEfficiency, 1e-12)
        assertEquals(0.0, p.motorW, 0.0)
        assertFalse(p.motorFull)
        // So the model rider still holds about 25 km/h on it.
        assertEquals(25.0, p.modelSpeedMps() * 3.6, 0.3)
    }

    @Test
    fun aSetupComesBackAsItWasSaved() {
        val ebike = Bike(
            id = 7, name = "Stromer", weightKg = 28.0, position = Position.UPRIGHT, tyres = Tyres.WIDE,
            gears = Gears.DERAILLEUR, motor = Motor.ASSIST, motorW = 300.0, cutoffKmh = 45.0,
        )
        val setup = RiderSetup(riderKg = 79.5, luggageKg = 3.0, bikes = listOf(RiderSetup.GAZELLE, ebike), activeBikeId = 7)
        assertEquals(setup, RiderSetup.fromJson(setup.toJson()))
    }

    @Test
    fun theActiveBikeDecidesThePhysics() {
        val ebike = Bike(id = 2, name = "E-bike", weightKg = 25.0, motor = Motor.FULL, motorW = 500.0, cutoffKmh = 25.0)
        val setup = RiderSetup(bikes = listOf(RiderSetup.GAZELLE, ebike), activeBikeId = 2)
        val p = setup.params()
        assertEquals(84.0 + 25.0 + 6.5, p.massKg, 1e-9)
        assertTrue(p.motorFull)
        assertEquals(500.0, p.motorW, 0.0)
        assertEquals(Position.UPRIGHT.cdA, p.cdA, 0.0)
    }

    @Test
    fun whateverIsTypedTheNumbersStayPossible() {
        val silly = RiderSetup(riderKg = -5.0, luggageKg = 500.0, bikes = emptyList(), activeBikeId = 99).valid()
        assertEquals(20.0, silly.riderKg, 0.0)
        assertEquals(80.0, silly.luggageKg, 0.0)
        assertEquals(listOf(RiderSetup.GAZELLE), silly.bikes)
        assertEquals(RiderSetup.GAZELLE.id, silly.activeBikeId)
        // Unknown or missing fields fall back to the first setup.
        assertEquals(RiderSetup(), RiderSetup.fromJson("{}"))
        assertEquals(Position.UPRIGHT, RiderSetup.fromJson("""{"bikes":[{"id":3,"position":"LYING"}],"activeBikeId":3}""").activeBike.position)
    }
}

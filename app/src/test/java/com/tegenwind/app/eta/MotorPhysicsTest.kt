package com.tegenwind.app.eta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MotorPhysicsTest {

    private val rho = Physics.airDensity(15.0)
    private val gazelle = RiderParams()

    /** A pedelec: 25 kg, sitting up, helping with 250 W up to 25 km/h. */
    private val pedelec = RiderParams(massKg = 84 + 25 + 6.5, cdA = 0.55, motorW = 250.0, cutoffMps = 25 / 3.6)

    @Test
    fun aPedelecRunsUpToItsCutOff() {
        val kmh = pedelec.modelSpeedMps() * 3.6
        assertTrue("got $kmh", kmh in 24.0..25.1)
        // At 20 km/h the motor does all the work on the flat: your share is next to nothing.
        assertTrue(pedelec.powerAtFormW(20 / kmh)!! < 10.0)
    }

    @Test
    fun aboveItsCutOffAPedelecIsJustAHeavyBike() {
        val withMotor = Physics.speedMps(300.0, 0.0, 0.0, rho, pedelec)
        val without = Physics.speedMps(300.0, 0.0, 0.0, rho, pedelec.copy(motorW = 0.0))
        assertTrue(withMotor * 3.6 > 25.0)
        assertEquals(without, withMotor, 0.01)
    }

    @Test
    fun aBikeThatRidesItselfHoldsItsCutOffUntilTheWindSaysNo() {
        val full = RiderParams(massKg = 84 + 30 + 6.5, cdA = 0.55, motorW = 500.0, cutoffMps = 25 / 3.6, motorFull = true)
        assertEquals(0.0, full.powerW, 0.0)
        assertEquals(25.0, full.modelSpeedMps() * 3.6, 1.0)
        // A strong headwind is more than the motor can hold 25 km/h against.
        assertTrue(Physics.speedMps(0.0, 0.0, 8.0, rho, full) * 3.6 < 23.0)
        // You don't pedal, so there are no watts of yours to show.
        assertNull(full.powerAtFormW(1.0))
    }

    @Test
    fun aSpeedPedelecIsNotCappedAtForty() {
        val fast = RiderParams(massKg = 84 + 28 + 6.5, cdA = 0.5, motorW = 800.0, cutoffMps = 45 / 3.6)
        assertTrue(fast.modelSpeedMps() * 3.6 > 40.0)
    }

    @Test
    fun lighterOnTheSameLegsIsFaster() {
        assertTrue(gazelle.copy(massKg = 105.0).modelSpeedMps() > gazelle.modelSpeedMps())
        // ...and the same speed then takes fewer watts.
        assertTrue(gazelle.copy(massKg = 105.0).let { Physics.pedalPowerW(6.0, 0.0, 0.0, rho, it) } <
            Physics.pedalPowerW(6.0, 0.0, 0.0, rho, gazelle))
    }
}

package com.tegenwind.app.ui.stats

import com.tegenwind.app.data.LearningPass
import com.tegenwind.app.data.RideEntity
import com.tegenwind.app.eta.RiderParams
import com.tegenwind.app.weather.HourlyWeather
import com.tegenwind.app.weather.WeatherPart
import com.tegenwind.app.weather.summarizeWeather
import com.tegenwind.app.weather.weatherOverRide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.LocalDateTime

class StatsMathTest {
    private fun pass(rideId: Long, head: Double?, lengthM: Double = 1000.0, grade: Double? = 0.0) = LearningPass(
        rideId = rideId, routeId = 1, segIdx = 0, movingMs = 200_000, enterMs = 0, exitMs = 200_000,
        headwindMps = head, expectedMovingMs = null, startM = 0.0, endM = lengthM, gradePct = grade, learnedLogVar = 0.03,
    )

    @Test
    fun savedVersusCarIsPetrolAndItsCo2() {
        val s = savedVersusCar(100_000.0)
        assertEquals(6.5, s.petrolL, 1e-9)
        assertEquals(6.5 * 2.31, s.co2Kg, 1e-9)
    }

    @Test
    fun kcalIsWorkAtThePedalsOverMuscleEfficiency() {
        // 100 W for an hour is 360 kJ at the pedals; at 24% that is 1.5 MJ ≈ 358 kcal.
        val ride = RideEntity(startedAtMs = 0, movingMs = 3_600_000, pedalPowerW = 100.0)
        assertEquals(358.4, kcalBurned(listOf(ride)), 0.5)
        // A ride without an effort burns nothing here.
        assertEquals(0.0, kcalBurned(listOf(RideEntity(startedAtMs = 0, movingMs = 3_600_000))), 0.0)
    }

    @Test
    fun aHeadwindCostsATailwindGivesBackAndNoWindIsNeither() {
        val params = RiderParams()
        fun effect(head: Double) = windEffect(listOf(pass(1, head)), { 120.0 }, { params })
        assertTrue(effect(5.0).lostS > 0 && effect(5.0).gainedS == 0.0)
        assertTrue(effect(-5.0).gainedS > 0 && effect(-5.0).lostS == 0.0)
        assertEquals(0.0, effect(0.0).lostS + effect(0.0).gainedS, 1e-6)
        // Into the wind costs more than the same wind behind gives back.
        assertTrue(effect(5.0).lostS > effect(-5.0).gainedS)
        // No forecast, or no effort: not counted.
        assertEquals(0.0, windEffect(listOf(pass(1, null)), { 120.0 }, { params }).lostS, 0.0)
        assertEquals(0.0, windEffect(listOf(pass(1, 5.0)), { null }, { params }).lostS, 0.0)
    }

    @Test
    fun headwindShareCountsRidesNotPasses() {
        val passes = listOf(pass(1, 4.0), pass(1, 3.0), pass(2, -4.0), pass(3, 0.1), pass(4, null))
        val share = headwindShare(passes)
        assertEquals(1, share.withHeadwind)
        assertEquals(3, share.total) // ride 4 has no wind data
        assertEquals(100.0 / 3, share.pct, 1e-9)
        assertEquals(0.0, headwindShare(emptyList()).pct, 0.0)
    }

    @Test
    fun trendPerMonthNeedsEnoughRidesOverEnoughTime() {
        val day = 86_400_000L
        val rising = (0..5).map { it * 10 * day to 20.0 + it } // +1 per 10 days
        assertEquals(3.0, trendPerMonth(rising)!!, 1e-9)
        assertNull(trendPerMonth(rising.take(3)))
        assertNull(trendPerMonth((0..5).map { it * day to it.toDouble() })) // five days only
    }

    @Test
    fun gapTrendComparesTheNewerHalfWithTheOlder() {
        assertEquals(Trend.Better, gapTrend(listOf(120.0, -100.0, 30.0, -20.0)))
        assertEquals(Trend.Worse, gapTrend(listOf(20.0, -30.0, 100.0, -120.0)))
        assertEquals(Trend.Steady, gapTrend(listOf(60.0, -60.0, 55.0, -65.0)))
        assertNull(gapTrend(listOf(60.0, 50.0, 40.0)))
        assertEquals(120.0, arrivalGapS(1_000, 121_000)!!, 0.0)
        assertNull(arrivalGapS(null, 5_000))
    }

    @Test
    fun weekdaysStartOnMondayAndSkipEmptyDays() {
        val zone = ZoneId.of("UTC")
        fun ride(date: String, minutes: Long): RideEntity {
            val start = LocalDateTime.parse(date).toInstant(ZoneOffset.UTC).toEpochMilli()
            return RideEntity(startedAtMs = start, endedAtMs = start + minutes * 60_000)
        }
        // 2026-10-19 is a Monday, 2026-10-21 a Wednesday.
        val days = weekdayAverages(listOf(ride("2026-10-19T08:00", 20), ride("2026-10-26T08:00", 30), ride("2026-10-21T17:30", 40)), zone)
        assertEquals(25.0 to 2, days[0])
        assertNull(days[1])
        assertEquals(40.0 to 1, days[2])
        assertEquals(17.5, startHour(ride("2026-10-21T17:30", 40), zone), 1e-9)
    }

    @Test
    fun hoursAndMinutesAreSaidShort() {
        assertEquals("35 min", hoursMinutes(35 * 60_000L))
        assertEquals("42 h 05 min", hoursMinutes((42 * 60 + 5) * 60_000L))
    }

    @Test
    fun weatherIsAveragedOverTimeAndRainAddedUp() {
        val w = summarizeWeather(
            listOf(WeatherPart(0.5, 10.0, 0.0), WeatherPart(0.25, 14.0, 4.0))
        )!!
        assertEquals((10 * 0.5 + 14 * 0.25) / 0.75, w.avgTempC!!, 1e-9)
        assertEquals(1.0, w.rainMm!!, 1e-9) // 4 mm/h for a quarter of an hour
        assertNull(summarizeWeather(emptyList()))
    }

    @Test
    fun aRideTakesTheWeatherOfTheHoursItOverlaps() {
        val h = 3_600_000L
        // Ride from 08:10 to 08:40; stamps 08:00 (rain is the hour before it) and 09:00.
        val start = 8 * h + 10 * 60_000
        val end = 8 * h + 40 * 60_000
        val hours = listOf(HourlyWeather(8 * h, 10.0, 0.0), HourlyWeather(9 * h, 12.0, 2.0))
        val w = weatherOverRide(start, end, hours)!!
        // Temperature: 08:10–08:30 at 10 °C and 08:30–08:40 at 12 °C.
        assertEquals((10 * 20 + 12 * 10) / 30.0, w.avgTempC!!, 1e-9)
        // Rain: the 09:00 stamp covers 08:00–09:00, of which the ride is 30 min: 2 mm/h × 0.5 h.
        assertEquals(1.0, w.rainMm!!, 1e-9)
        assertNull(weatherOverRide(start, end, emptyList()))
    }
}

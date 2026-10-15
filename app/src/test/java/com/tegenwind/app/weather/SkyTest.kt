package com.tegenwind.app.weather

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkyTest {

    @Test
    fun drizzleIsClearlyBlueAndAFewMillimetresIsFullyBlue() {
        assertEquals(0.0, rainBlueShare(0.0), 0.0)
        assertEquals(0.0, rainBlueShare(0.05), 0.0) // a trace: no rain to speak of
        assertEquals(0.35, rainBlueShare(0.1), 1e-9)
        assertTrue(rainBlueShare(0.5) > 0.6)
        assertEquals(1.0, rainBlueShare(2.0), 1e-9)
        assertEquals(1.0, rainBlueShare(8.0), 1e-9)
    }

    @Test
    fun aChanceOfRainShowsAsStripesOnlyWhereNoRainIsForecast() {
        assertEquals(0.0, rainChanceStripes(Sky(80.0, 0.0, 10.0)), 0.0) // too small a chance
        assertEquals(0.25, rainChanceStripes(Sky(80.0, 0.0, 20.0)), 1e-9)
        assertEquals(0.70, rainChanceStripes(Sky(80.0, 0.0, 100.0)), 1e-9)
        // Rain in the forecast is solid blue already: no stripes on top.
        assertEquals(0.0, rainChanceStripes(Sky(80.0, 0.5, 90.0)), 0.0)
        // Without a chance from the models, nothing is made up.
        assertEquals(0.0, rainChanceStripes(Sky(80.0, 0.0)), 0.0)
    }

    @Test
    fun theHoursChanceOfRainReachesEveryQuarterHour() {
        val json = JSONObject(
            """
            {
              "minutely_15": {
                "time": ["2026-10-16T07:00", "2026-10-16T07:15", "2026-10-16T07:30", "2026-10-16T07:45", "2026-10-16T08:00"],
                "wind_speed_10m": [4, 4, 4, 4, 4], "wind_direction_10m": [240, 240, 240, 240, 240],
                "temperature_2m": [12, 12, 12, 12, 12], "cloud_cover": [90, 90, 90, 90, 90],
                "precipitation": [0, 0, 0.1, 0, 0]
              },
              "hourly": {
                "time": ["2026-10-16T07:00", "2026-10-16T08:00"],
                "wind_speed_10m": [4, 4], "wind_direction_10m": [240, 240],
                "temperature_2m": [12, 12], "cloud_cover": [90, 90],
                "precipitation": [0.1, 0], "precipitation_probability": [40, 80]
              }
            }
            """
        )
        val samples = forecastFrom(json).samples
        assertEquals(5, samples.size) // the 15-minute data, not the hourly
        assertEquals(40.0, samples[0].rainChancePct!!, 1e-9)
        assertEquals(60.0, samples[2].rainChancePct!!, 1e-9) // halfway between the hours
        assertEquals(80.0, samples[4].rainChancePct!!, 1e-9)
        // A 15-minute total of 0.1 mm is 0.4 mm per hour.
        assertEquals(0.4, samples[2].rainMmH!!, 1e-9)
        assertEquals(60.0, samples[2].sky!!.rainChancePct!!, 1e-9)
    }

    @Test
    fun withoutAChanceTheSkyStillWorks() {
        val json = JSONObject(
            """{"hourly": {"time": ["2026-10-16T07:00"], "wind_speed_10m": [3], "wind_direction_10m": [90],
               "cloud_cover": [20], "precipitation": [0]}}"""
        )
        val sky = forecastFrom(json).samples.single().sky!!
        assertNull(sky.rainChancePct)
        assertEquals(0.0, rainChanceStripes(sky), 0.0)
    }
}

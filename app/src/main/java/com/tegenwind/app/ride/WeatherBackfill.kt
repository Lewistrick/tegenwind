package com.tegenwind.app.ride

// TODO(remove after backfill): the one-off weather lookup for rides recorded before their weather was
// kept. Once the old rides have their weather (or you accept leaving the rest empty), delete:
//   weather/WeatherHistory.kt, ride/WeatherBackfill.kt, weatherOverRide + HourlyWeather in
//   weather/RideWeather.kt (and their test in StatsMathTest.kt), the backfillWeather launch in
//   TegenwindApp.kt, and RideDao.firstPoint / ridesWithoutWeather.
// Keep summarizeWeather, avgTempC and rainMm: new rides record their own weather.

import com.tegenwind.app.data.RideDao
import com.tegenwind.app.routes.GeoPoint
import com.tegenwind.app.weather.WeatherHistory
import com.tegenwind.app.weather.weatherOverRide

/**
 * Rides recorded before their weather was kept get it from the weather record, at the place they
 * began. A ride it can't be read for (offline, no track) is tried again the next time the app starts.
 */
suspend fun backfillWeather(rideDao: RideDao, history: WeatherHistory) {
    for (ride in rideDao.ridesWithoutWeather()) {
        val end = ride.endedAtMs ?: continue
        val start = rideDao.firstPoint(ride.id)?.let { GeoPoint(it.lat, it.lon) } ?: continue
        // Offline or refused: stop here, and try the rest next time.
        val hours = history.hourly(start, ride.startedAtMs, end) ?: return
        val weather = weatherOverRide(ride.startedAtMs, end, hours) ?: continue
        rideDao.setWeather(ride.id, weather.avgTempC, weather.rainMm)
    }
}

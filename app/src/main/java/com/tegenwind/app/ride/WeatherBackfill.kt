package com.tegenwind.app.ride

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

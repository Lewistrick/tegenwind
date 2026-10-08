package com.tegenwind.app

import android.app.Application
import android.content.Context
import androidx.room.Room
import com.tegenwind.app.data.TegenwindDb
import com.tegenwind.app.eta.Banister
import com.tegenwind.app.eta.RiderModel
import com.tegenwind.app.health.HealthConnectHr
import com.tegenwind.app.health.HeartRateStore
import com.tegenwind.app.live.LiveShare
import com.tegenwind.app.ride.RideRecorder
import com.tegenwind.app.ride.backfillLessons
import com.tegenwind.app.ride.backfillProfiles
import com.tegenwind.app.routes.RouteEnricher
import com.tegenwind.app.routes.RouteRepository
import com.tegenwind.app.weather.WeatherRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class TegenwindApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/** App-wide singletons. Small enough that a DI framework isn't worth it yet. */
class AppContainer(app: Application) {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val db: TegenwindDb = Room.databaseBuilder(app, TegenwindDb::class.java, "tegenwind.db").build()
    /** What has been learned about how you ride: wind, spread of form, time at lights. */
    val riderModel = RiderModel(db.rides())
    val recorder: RideRecorder = RideRecorder(db.rides(), db.routes(), appScope, learned = { riderModel.learned.value }) { routeId, rideId ->
        routes.learnLine(routeId, rideId)
        // After the ride is stored and its segments and form have learned from it.
        riderModel.refresh()
    }
    val healthConnect = HealthConnectHr(app)
    val heartRate = HeartRateStore(healthConnect, db.rides())
    val liveShare = LiveShare()
    val routes: RouteRepository = RouteRepository(db.routes(), db.rides(), RouteEnricher(), appScope) { recorder.live.value != null }
    val weather = WeatherRepository()

    init {
        routes.resumeStalledEnrichment()
        // Heart-rate readings are only kept for two weeks.
        appScope.launch { heartRate.forgetOld() }
        // Rides recorded before the fitness model existed still count towards it.
        appScope.launch {
            val missing = db.rides().ridesWithoutLoad()
            if (missing.isNotEmpty()) {
                db.rides().updateRides(
                    missing.map { it.copy(loadTss = Banister.loadFromRide(it.movingMs, it.distanceM)) }
                )
            }
        }
        appScope.launch {
            // Rides from before each pass recorded what it taught get that filled in once.
            backfillLessons(db.rides(), db.routes())
            // Whatever all segments learned in common goes into form. The first time, that's the
            // tilt they gathered before this existed; after that each ride does it as it ends.
            db.routes().moveSharedTiltIntoForm()
            riderModel.refresh()
        }
        appScope.launch {
            // Routes ridden before rides learned a speed profile learn one from the rides already stored.
            backfillProfiles(db.rides(), db.routes(), routes::load)
            // Then each line moves toward where the rides stored since it last learned went. After
            // the profiles, so those are learned on the line they are then carried over from.
            routes.learnLines()
        }
    }
}

val Context.appContainer: AppContainer
    get() = (applicationContext as TegenwindApp).container

package com.tegenwind.app

import android.app.Application
import android.content.Context
import androidx.room.Room
import com.tegenwind.app.data.RiderSetupStore
import com.tegenwind.app.data.TegenwindDb
import com.tegenwind.app.eta.Banister
import com.tegenwind.app.eta.RiderModel
import com.tegenwind.app.health.HealthConnectHr
import com.tegenwind.app.health.HeartRateStore
import com.tegenwind.app.live.LiveShare
import com.tegenwind.app.media.AudioMediaControls
import com.tegenwind.app.media.MediaControls
import com.tegenwind.app.ride.RideRecorder
import com.tegenwind.app.ride.backfillLessons
import com.tegenwind.app.ride.backfillProfiles
import com.tegenwind.app.ride.backfillSteadyPower
import com.tegenwind.app.ride.backfillWeather
import com.tegenwind.app.weather.WeatherHistory
import com.tegenwind.app.routes.RideLineStore
import com.tegenwind.app.routes.RouteEnricher
import com.tegenwind.app.routes.RouteRepository
import com.tegenwind.app.ui.OsmTiles
import com.tegenwind.app.weather.WeatherRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

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
    /** The music buttons on the ride screen. */
    val mediaControls: MediaControls = AudioMediaControls(app)
    /** You and your bikes, as set on the "Rider and bike" screen. */
    val riderSetup = RiderSetupStore(app)
    /** What has been learned about how you ride: wind, spread of form, time at lights. */
    val riderModel = RiderModel(db.rides()) { riderSetup.setup.value.params() }
    val recorder: RideRecorder = RideRecorder(
        db.rides(), db.routes(), appScope,
        learned = { riderModel.learned.value },
        setup = { riderSetup.setup.value },
    ) { routeId, rideId ->
        routes.learnLine(routeId, rideId)
        // After the ride is stored and its segments and form have learned from it.
        riderModel.refresh()
    }
    val healthConnect = HealthConnectHr(app)
    val heartRate = HeartRateStore(healthConnect, db.rides())
    val liveShare = LiveShare()
    /** Each ride's route line before and after the line learned from it, for the ride's street map. */
    val rideLines = RideLineStore(File(app.filesDir, "ride-lines"))
    val routes: RouteRepository = RouteRepository(db.routes(), db.rides(), RouteEnricher(), appScope, rideLines) { recorder.live.value != null }
    /** OpenStreetMap's map tiles, kept on the phone once fetched. */
    val tiles = OsmTiles(File(app.cacheDir, "osm-tiles"))
    val weather = WeatherRepository()

    init {
        routes.resumeStalledEnrichment()
        // Heart-rate readings are only kept for two weeks.
        appScope.launch {
            heartRate.forgetOld()
            heartRate.fillAverages()
        }
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
            // Rides keep their effort as steady power now (wind and slope taken out): the ones from
            // before are worked out again from their passes, once.
            val prefs = app.getSharedPreferences("tegenwind", Context.MODE_PRIVATE)
            if (!prefs.getBoolean(STEADY_POWER_DONE, false)) {
                backfillSteadyPower(db.rides(), riderSetup.setup.value)
                prefs.edit().putBoolean(STEADY_POWER_DONE, true).apply()
            }
        }
        appScope.launch {
            // Rides from before their weather was kept get it from the weather record.
            // TODO(remove after backfill): see the note in weather/WeatherHistory.kt.
            backfillWeather(db.rides(), WeatherHistory())
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
            // First of all: a line damaged by a faulty update is rebuilt from the rides on it.
            routes.repairLines()
            // Routes ridden before rides learned a speed profile learn one from the rides already stored.
            backfillProfiles(db.rides(), db.routes(), routes::load)
            // Then each line moves toward where the rides stored since it last learned went. After
            // the profiles, so those are learned on the line they are then carried over from.
            routes.learnLines()
        }
    }
}

private const val STEADY_POWER_DONE = "steady_power_v1"

val Context.appContainer: AppContainer
    get() = (applicationContext as TegenwindApp).container

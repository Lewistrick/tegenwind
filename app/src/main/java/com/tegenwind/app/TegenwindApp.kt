package com.tegenwind.app

import android.app.Application
import android.content.Context
import androidx.room.Room
import com.tegenwind.app.data.TegenwindDb
import com.tegenwind.app.health.HealthConnectHr
import com.tegenwind.app.ride.RideRecorder
import com.tegenwind.app.routes.RouteEnricher
import com.tegenwind.app.routes.RouteRepository
import com.tegenwind.app.weather.WeatherRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

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
    val recorder = RideRecorder(db.rides(), appScope)
    val healthConnect = HealthConnectHr(app)
    val routes = RouteRepository(db.routes(), RouteEnricher(), appScope)
    val weather = WeatherRepository()
}

val Context.appContainer: AppContainer
    get() = (applicationContext as TegenwindApp).container

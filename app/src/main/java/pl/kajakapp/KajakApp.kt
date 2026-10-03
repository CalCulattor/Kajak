package pl.kajakapp

import com.mapbox.common.MapboxOptions
import android.app.Activity
import android.app.Application
import android.os.Bundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import pl.kajakapp.data.ConditionsRepository
import pl.kajakapp.data.LiveUpdates
import pl.kajakapp.data.RiverRepository
import pl.kajakapp.data.ServerSettings
import pl.kajakapp.data.SyncRepository
import pl.kajakapp.data.TrackRecorder
import pl.kajakapp.data.TrackRepository
import pl.kajakapp.data.TripRepository
import pl.kajakapp.data.db.AppDatabase
import pl.kajakapp.data.remote.Network

/** Ręczny kontener zależności – wystarcza przy tej wielkości projektu. */
class AppContainer(app: Application, scope: CoroutineScope) {
    private val db = AppDatabase.build(app)
    val settings = ServerSettings(app)
    val sync = SyncRepository(db, settings)
    val live = LiveUpdates(settings, sync, scope)
    val rivers = RiverRepository(db)
    val trips = TripRepository(db)
    val tracks = TrackRepository(db.trackDao())
    val recorder = TrackRecorder(db.trackDao(), settings)
    val conditions = ConditionsRepository(Network.imgw, Network.openMeteo, db.cacheDao())
}

class KajakApp : Application() {
    lateinit var container: AppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var started = 0
    private var stopJob: Job? = null

    private companion object {
        const val STOP_DELAY_MS = 3_000L
    }

    override fun onCreate() {
        super.onCreate()
        // Token publiczny Mapbox (z local.properties przez resValue); bez niego mapy pokażą komunikat.
        getString(R.string.mapbox_access_token).takeIf { it.isNotBlank() }?.let { MapboxOptions.accessToken = it }
        container = AppContainer(this, appScope)
        appScope.launch { container.rivers.seedMissing() }
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                started++
                stopJob?.cancel()
                container.live.setForeground(true)
            }

            override fun onActivityStopped(activity: Activity) {
                started--
                if (started <= 0) {
                    // Krótka zwłoka, żeby obrót ekranu nie zrywał połączenia.
                    stopJob = appScope.launch(Dispatchers.Main) {
                        delay(STOP_DELAY_MS)
                        if (started <= 0) container.live.setForeground(false)
                    }
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }
}

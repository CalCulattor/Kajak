package pl.kajakapp

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import pl.kajakapp.data.ConditionsRepository
import pl.kajakapp.data.RiverRepository
import pl.kajakapp.data.TripRepository
import pl.kajakapp.data.db.AppDatabase
import pl.kajakapp.data.remote.Network

/** Ręczny kontener zależności – wystarcza przy tej wielkości projektu. */
class AppContainer(app: Application) {
    private val db = AppDatabase.build(app)
    val rivers = RiverRepository(db)
    val trips = TripRepository(db)
    val conditions = ConditionsRepository(Network.imgw, Network.openMeteo, db.cacheDao())
}

class KajakApp : Application() {
    lateinit var container: AppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        appScope.launch { container.rivers.seedIfEmpty() }
    }
}

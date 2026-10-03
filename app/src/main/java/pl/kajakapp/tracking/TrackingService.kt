package pl.kajakapp.tracking

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import pl.kajakapp.KajakApp
import pl.kajakapp.MainActivity
import pl.kajakapp.data.TrackRecorder
import pl.kajakapp.domain.TrackPoint

/**
 * Usługa pierwszoplanowa (z powiadomieniem), która odczytuje pozycję GPS w trakcie trasy – także gdy
 * aplikacja jest zminimalizowana, a ekran wygaszony. Każdy odczyt trafia do [TrackRecorder].
 *
 * Polecenia (start, wznowienie, stop) są wykonywane po kolei przez jedną pętlę, dzięki czemu np. „stop”
 * nigdy nie wyprzedzi jeszcze niedokończonego „startu”.
 */
class TrackingService : Service() {
    private sealed interface Command {
        val startId: Int

        class Begin(val title: String, val tripId: Long?, val tripTitle: String?, override val startId: Int) : Command
        class Resume(override val startId: Int) : Command
        class Stop(override val startId: Int) : Command

        /** Zgłaszane, gdy odczyt dotarł, a żadna trasa nie jest w toku. */
        class Abort(override val startId: Int) : Command
    }

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            CoroutineExceptionHandler { _, e -> Log.e(TAG, "Nieoczekiwany błąd w usłudze nagrywania", e) }
    )
    private lateinit var recorder: TrackRecorder
    private lateinit var locationManager: LocationManager
    private var wakeLock: PowerManager.WakeLock? = null

    private val commands = Channel<Command>(Channel.UNLIMITED)

    // Odczyty przechodzą przez jedną kolejkę, żeby zapisywały się w kolejności.
    @Volatile
    private var fixes: Channel<TrackPoint>? = null
    private var consumer: Job? = null

    // Tylko pętla poleceń zmienia [listening], [consumer] i blokadę uśpienia.
    @Volatile
    private var listening = false

    @Volatile
    private var lastStartId = 0
    private var lastNotificationAt = 0L

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            fixes?.trySend(
                TrackPoint(
                    time = location.time.takeIf { it > 0 } ?: System.currentTimeMillis(),
                    lat = location.latitude,
                    lon = location.longitude,
                    accuracy = if (location.hasAccuracy()) location.accuracy else null
                )
            )
        }

        override fun onProviderEnabled(provider: String) {
            if (provider == LocationManager.GPS_PROVIDER) recorder.setGpsEnabled(true)
        }

        override fun onProviderDisabled(provider: String) {
            if (provider == LocationManager.GPS_PROVIDER) recorder.setGpsEnabled(false)
        }

        @Deprecated("Wymagane na starszych wersjach Androida")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    }

    override fun onCreate() {
        super.onCreate()
        recorder = (application as KajakApp).container.recorder
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val channel = NotificationChannel(CHANNEL_ID, "Nagrywanie trasy", NotificationManager.IMPORTANCE_LOW)
        channel.description = "Pokazuje, że aplikacja zapisuje przebieg spływu"
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)

        scope.launch {
            for (command in commands) {
                try {
                    handle(command)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Błąd polecenia $command", e)
                    // Po błędzie nie zostawiamy usługi z samym powiadomieniem.
                    shutdownListening(join = false)
                    stopSelfSafely(command.startId)
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        when (intent?.action) {
            ACTION_STOP -> commands.trySend(Command.Stop(startId))
            ACTION_START -> {
                val title = intent?.getStringExtra(EXTRA_TITLE).orEmpty()
                if (!enterForeground(title.ifBlank { "Trasa" })) return START_NOT_STICKY
                val tripId = (intent?.getLongExtra(EXTRA_TRIP_ID, -1L) ?: -1L).takeIf { it >= 0 }
                commands.trySend(Command.Begin(title, tripId, intent?.getStringExtra(EXTRA_TRIP_TITLE), startId))
            }
            ACTION_RESUME -> {
                if (!enterForeground("Trasa")) return START_NOT_STICKY
                commands.trySend(Command.Resume(startId))
            }
            // Brak polecenia (np. system uruchomił usługę ponownie): nic nie nagrywamy, trasa zostaje do wznowienia z aplikacji.
            else -> stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private suspend fun handle(command: Command) {
        when (command) {
            is Command.Begin -> {
                recorder.begin(command.title, command.tripId, command.tripTitle)
                startListening()
            }
            is Command.Resume ->
                if (recorder.resumeActive()) startListening() else stopSelfSafely(command.startId)
            is Command.Stop -> stopRecording(command.startId)
            is Command.Abort -> {
                // Odczyty bez trasy (zakończonej np. z ekranu historii). Jeśli w międzyczasie ruszyła nowa – zostawiamy.
                if (recorder.live.value == null && !recorder.busy.value) {
                    shutdownListening(join = false)
                    stopSelfSafely(lastStartId)
                }
            }
        }
    }

    private fun enterForeground(title: String): Boolean = try {
        val notification = buildNotification(title, null, System.currentTimeMillis())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        true
    } catch (e: Exception) {
        // Np. system nie pozwala uruchomić usługi lokalizacji z tła. Trasa zostaje w bazie jako przerwana.
        Log.w(TAG, "Nie można przejść do pracy na pierwszym planie", e)
        stopSelf()
        false
    }

    @SuppressLint("MissingPermission", "WakelockTimeout")
    private fun startListening() {
        if (listening) return
        listening = true
        val queue = Channel<TrackPoint>(Channel.UNLIMITED)
        fixes = queue
        consumer = scope.launch {
            for (fix in queue) {
                try {
                    if (recorder.onFix(fix)) {
                        refreshNotification()
                    } else {
                        commands.trySend(Command.Abort(lastStartId))
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Nie udało się zapisać odczytu GPS", e)
                }
            }
        }
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "KajakApp:tracking").apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_MAX_MS)
        }
        // Rejestracja musi iść z wątku z pętlą zdarzeń.
        ContextCompat.getMainExecutor(this).execute {
            try {
                recorder.setGpsEnabled(locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER))
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER, UPDATE_INTERVAL_MS, 0f, listener, Looper.getMainLooper()
                )
            } catch (e: SecurityException) {
                // Brak uprawnienia do lokalizacji – kończymy; trasa zapisze się tylko jeśli ma dane.
                Log.w(TAG, "Brak uprawnienia do lokalizacji", e)
                commands.trySend(Command.Stop(lastStartId))
            } catch (e: IllegalArgumentException) {
                recorder.setGpsEnabled(false)
            }
        }
    }

    /** Przerywa nasłuch GPS i zwalnia blokadę uśpienia. Wołać tylko z pętli poleceń. */
    private suspend fun shutdownListening(join: Boolean) {
        if (listening) {
            listening = false
            ContextCompat.getMainExecutor(this).execute { locationManager.removeUpdates(listener) }
            fixes?.close()
            fixes = null
            if (join) consumer?.join() else consumer?.cancel()
            consumer = null
        }
        releaseWakeLock()
    }

    /** Kończy nasłuch, dopisuje odczyty czekające w kolejce, zapisuje trasę i zatrzymuje usługę. */
    private suspend fun stopRecording(startId: Int) {
        try {
            shutdownListening(join = true) // dopisz odczyty, które już czekają w kolejce
            recorder.finish()
        } finally {
            stopSelfSafely(startId)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    /**
     * Zatrzymuje usługę, o ile [startId] to nadal ostatnie polecenie startu – inaczej w międzyczasie
     * przyszło nowe i usługa (wraz z powiadomieniem) ma działać dalej.
     */
    private fun stopSelfSafely(startId: Int) {
        ContextCompat.getMainExecutor(this).execute {
            if (startId == lastStartId) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
        }
    }

    private fun refreshNotification() {
        val now = System.currentTimeMillis()
        if (now - lastNotificationAt < NOTIFICATION_EVERY_MS) return
        lastNotificationAt = now
        val live = recorder.live.value ?: return
        val text = "%.2f km\t%.1f km/h".format(live.distanceM / 1000.0, live.speedKmh)
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, buildNotification(live.title, text, live.startedAt))
    }

    private fun buildNotification(title: String, text: String?, startedAt: Long): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, TrackingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Nagrywanie trasy: $title")
            .setContentText(text ?: "Czekam na sygnał GPS…")
            .setWhen(startedAt)
            .setUsesChronometer(true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "Zakończ", stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    override fun onDestroy() {
        if (listening) {
            listening = false
            try {
                locationManager.removeUpdates(listener)
            } catch (e: Exception) {
                // nic więcej nie da się zrobić
            }
        }
        releaseWakeLock()
        commands.close()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "TrackingService"
        private const val CHANNEL_ID = "tracking"
        private const val NOTIFICATION_ID = 42
        private const val NOTIFICATION_EVERY_MS = 10_000L
        private const val UPDATE_INTERVAL_MS = 3_000L
        private const val WAKE_LOCK_MAX_MS = 12 * 60 * 60 * 1000L
        private const val ACTION_START = "pl.kajakapp.tracking.START"
        private const val ACTION_RESUME = "pl.kajakapp.tracking.RESUME"
        private const val ACTION_STOP = "pl.kajakapp.tracking.STOP"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_TRIP_ID = "trip_id"
        private const val EXTRA_TRIP_TITLE = "trip_title"

        /** Uruchamia nagrywanie (wołać, gdy aplikacja jest na ekranie i ma uprawnienie do lokalizacji). */
        fun start(context: Context, title: String, tripId: Long?, tripTitle: String?) {
            val intent = Intent(context, TrackingService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_TRIP_TITLE, tripTitle)
            if (tripId != null) intent.putExtra(EXTRA_TRIP_ID, tripId)
            ContextCompat.startForegroundService(context, intent)
        }

        /** Wznawia trasę przerwaną (np. po zabiciu aplikacji przez system). */
        fun resume(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, TrackingService::class.java).setAction(ACTION_RESUME)
            )
        }

        fun stop(context: Context) {
            context.startService(Intent(context, TrackingService::class.java).setAction(ACTION_STOP))
        }
    }
}

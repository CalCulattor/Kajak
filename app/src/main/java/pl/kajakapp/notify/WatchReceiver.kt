package pl.kajakapp.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import pl.kajakapp.KajakApp

/**
 * Budzi aplikację co ok. 15 minut (alarm niedokładny, oszczędza baterię), żeby sprawdzić SOS i zmiany
 * warunków, gdy aplikacja jest zamknięta. Po restarcie telefonu harmonogram odtwarza się sam.
 */
class WatchReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            schedule(context)
            return
        }
        val app = context.applicationContext as? KajakApp ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                withTimeoutOrNull(RUN_LIMIT_MS) { app.container.watcher.run() }
            } catch (e: Exception) {
                // Błąd sieci nie może zabić odbiornika; spróbujemy przy następnym alarmie.
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val RUN_LIMIT_MS = 45_000L

        fun schedule(context: Context) {
            val alarms = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarms.setInexactRepeating(
                AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + AlarmManager.INTERVAL_FIFTEEN_MINUTES,
                AlarmManager.INTERVAL_FIFTEEN_MINUTES,
                pendingIntent(context)
            )
        }

        private fun pendingIntent(context: Context): PendingIntent =
            PendingIntent.getBroadcast(
                context, 7, Intent(context, WatchReceiver::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
    }
}

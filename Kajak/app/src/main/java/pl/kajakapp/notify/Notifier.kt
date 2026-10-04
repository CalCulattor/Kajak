package pl.kajakapp.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import pl.kajakapp.MainActivity

/** Kanały i wysyłanie powiadomień (SOS, zmiana warunków). Powiadomienie o nagrywaniu tworzy TrackingService. */
class Notifier(private val context: Context) {

    fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_SOS, "Wezwania pomocy (SOS)", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Ktoś z Twojego spływu prosi o pomoc."
                enableVibration(true)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_CONDITIONS, "Warunki na rzece", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Zmiana oceny warunków na rzece, na której masz spływ."
            }
        )
    }

    fun canNotify(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return false
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    /** [tripLocalId] == null: wezwanie z innej grupy na tej samej rzece (nie jestem jej uczestnikiem). */
    fun sos(checkInId: Long, tripLocalId: Long?, person: String, tripTitle: String) {
        post(
            id = SOS_BASE + (checkInId % 100_000).toInt(),
            builder = NotificationCompat.Builder(context, CHANNEL_SOS)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("SOS: $person prosi o pomoc")
                .setContentText(
                    if (tripLocalId != null) "Spływ: $tripTitle – otwórz aplikację, aby zobaczyć pozycję."
                    else "Ktoś na Twojej rzece potrzebuje pomocy – możesz być najbliżej. Dzwoń też pod 112."
                )
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setContentIntent(openTrip(tripLocalId ?: -1L, 100 + (checkInId % 1000).toInt()))
                .setAutoCancel(true)
        )
    }

    fun cancelSos(checkInId: Long) {
        NotificationManagerCompat.from(context).cancel(SOS_BASE + (checkInId % 100_000).toInt())
    }

    fun conditions(sectionId: Long, title: String, text: String, tripLocalId: Long?) {
        post(
            id = COND_BASE + (sectionId % 100_000).toInt(),
            builder = NotificationCompat.Builder(context, CHANNEL_CONDITIONS)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(openTrip(tripLocalId ?: -1L, 2000 + (sectionId % 1000).toInt()))
                .setAutoCancel(true)
        )
    }

    private fun post(id: Int, builder: NotificationCompat.Builder) {
        if (!canNotify()) return
        ensureChannels()
        try {
            NotificationManagerCompat.from(context).notify(id, builder.build())
        } catch (e: SecurityException) {
            // Uprawnienie cofnięte w trakcie – pomijamy.
        }
    }

    private fun openTrip(tripLocalId: Long, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_TRIP_ID, tripLocalId)
        return PendingIntent.getActivity(
            context, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    companion object {
        const val CHANNEL_SOS = "sos"
        const val CHANNEL_CONDITIONS = "conditions"
        const val EXTRA_TRIP_ID = "open_trip_id"
        private const val SOS_BASE = 10_000
        private const val COND_BASE = 200_000
    }
}

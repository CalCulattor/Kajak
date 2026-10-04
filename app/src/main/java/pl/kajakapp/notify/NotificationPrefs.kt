package pl.kajakapp.notify

import android.content.Context

/** Ustawienia powiadomień oraz pamięć o tym, o czym już powiadomiono (żeby nie powtarzać). */
class NotificationPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("kajak_notifications", Context.MODE_PRIVATE)

    var sosEnabled: Boolean
        get() = prefs.getBoolean(KEY_SOS, true)
        set(value) = prefs.edit().putBoolean(KEY_SOS, value).apply()

    var conditionsEnabled: Boolean
        get() = prefs.getBoolean(KEY_CONDITIONS, true)
        set(value) = prefs.edit().putBoolean(KEY_CONDITIONS, value).apply()

    /** Ostatnio znana ocena warunków (nazwa RiskLevel) dla odcinka; null = brak. */
    fun lastLevel(sectionId: Long): String? = prefs.getString("level_$sectionId", null)

    fun setLastLevel(sectionId: Long, level: String) {
        prefs.edit().putString("level_$sectionId", level).apply()
    }

    /** Identyfikatory zameldowań SOS, o których już powiadomiono. */
    var notifiedSos: Set<Long>
        get() = prefs.getStringSet(KEY_SOS_IDS, emptySet()).orEmpty().mapNotNull { it.toLongOrNull() }.toSet()
        set(value) = prefs.edit().putStringSet(KEY_SOS_IDS, value.map { it.toString() }.toSet()).apply()

    private companion object {
        const val KEY_SOS = "sos"
        const val KEY_CONDITIONS = "conditions"
        const val KEY_SOS_IDS = "sos_ids"
    }
}

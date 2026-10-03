package pl.kajakapp.data

import android.content.Context
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Ustawienia połączenia z serwerem. Pusty adres oznacza „tylko lokalnie” (bez synchronizacji).
 */
class ServerSettings(context: Context) {
    private val prefs = context.getSharedPreferences("kajak_settings", Context.MODE_PRIVATE)

    private val _url = MutableStateFlow(prefs.getString(KEY_URL, DEFAULT_URL) ?: DEFAULT_URL)

    /** Znormalizowany adres bazowy (kończy się „/”) albo pusty tekst, gdy synchronizacja jest wyłączona. */
    val url: StateFlow<String> = _url

    /** Losowy identyfikator instalacji; służy do odróżniania zgłoszeń z różnych telefonów. */
    val deviceId: String = prefs.getString(KEY_DEVICE, null) ?: UUID.randomUUID().toString()
        .replace("-", "").take(10).also { prefs.edit().putString(KEY_DEVICE, it).apply() }

    /**
     * Zapisuje adres. Zwraca false, gdy adres jest niepoprawny (wtedy nic nie zmienia).
     * Pusty tekst wyłącza synchronizację.
     */
    fun setUrl(raw: String): Boolean {
        val normalized = if (raw.isBlank()) "" else normalize(raw) ?: return false
        prefs.edit().putString(KEY_URL, normalized).apply()
        _url.value = normalized
        return true
    }

    fun clientId(kind: String, localId: Long): String = "$deviceId-$kind$localId"

    companion object {
        const val DEFAULT_URL = "https://hackyeah.duckdns.org/"
        private const val KEY_URL = "server_url"
        private const val KEY_DEVICE = "device_id"
        private val CLEARTEXT_HOSTS = setOf("10.0.2.2", "localhost")

        /**
         * Dodaje brakujący schemat (domyślnie https) i końcowy „/”; null = adres nie do przyjęcia.
         * HTTP bez szyfrowania jest dozwolone tylko dla emulatora (10.0.2.2) i localhost.
         */
        fun normalize(raw: String): String? {
            var text = raw.trim()
            if (text.isEmpty()) return null
            if (!text.contains("://")) text = "https://$text"
            val url = text.toHttpUrlOrNull() ?: return null
            if (url.scheme != "http" && url.scheme != "https") return null
            if (url.query != null || url.fragment != null) return null
            // Android blokuje HTTP bez szyfrowania (poza hostami z network_security_config.xml).
            if (url.scheme == "http" && url.host !in CLEARTEXT_HOSTS) return null
            val result = url.toString()
            return if (result.endsWith("/")) result else "$result/"
        }
    }
}

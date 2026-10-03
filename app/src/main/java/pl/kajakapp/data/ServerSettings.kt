package pl.kajakapp.data

import android.content.Context
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Zalogowane konto: nazwa użytkownika i token sesji. */
data class Session(val username: String, val token: String)

/**
 * Ustawienia połączenia z serwerem oraz zalogowane konto. Pusty adres oznacza „tylko lokalnie” (bez synchronizacji).
 */
class ServerSettings(context: Context) {
    private val prefs = context.getSharedPreferences("kajak_settings", Context.MODE_PRIVATE)

    private val _url = MutableStateFlow(prefs.getString(KEY_URL, DEFAULT_URL) ?: DEFAULT_URL)

    /** Znormalizowany adres bazowy (kończy się „/”) albo pusty tekst, gdy synchronizacja jest wyłączona. */
    val url: StateFlow<String> = _url

    private val _session = MutableStateFlow(loadSession())

    /** Zalogowane konto albo null. Token jest ważny tylko dla serwera, na którym go wydano. */
    val session: StateFlow<Session?> = _session

    private fun loadSession(): Session? {
        val name = prefs.getString(KEY_USER, null)
        val token = prefs.getString(KEY_TOKEN, null)
        return if (name != null && token != null) Session(name, token) else null
    }

    /**
     * Ostatnio zalogowane konto (zostaje po wylogowaniu). Pozwala wykryć zmianę konta
     * na tym telefonie i usunąć kopie spływów poprzedniego użytkownika.
     */
    val lastAccount: String? get() = prefs.getString(KEY_LAST_ACCOUNT, null)

    /** Adres serwera, z którego pochodzą lokalne kopie spływów (ustawiany przy logowaniu). */
    val mirrorUrl: String? get() = prefs.getString(KEY_MIRROR_URL, null)

    fun setSession(username: String, token: String) {
        prefs.edit()
            .putString(KEY_MIRROR_URL, _url.value)
            .putString(KEY_USER, username)
            .putString(KEY_TOKEN, token)
            .putString(KEY_LAST_ACCOUNT, username)
            .apply()
        _session.value = Session(username, token)
    }

    fun clearSession() {
        prefs.edit().remove(KEY_USER).remove(KEY_TOKEN).apply()
        _session.value = null
    }

    /** Losowy identyfikator instalacji; służy do odróżniania zgłoszeń z różnych telefonów. */
    val deviceId: String = prefs.getString(KEY_DEVICE, null) ?: UUID.randomUUID().toString()
        .replace("-", "").take(10).also { prefs.edit().putString(KEY_DEVICE, it).apply() }

    /**
     * Zapisuje adres. Zwraca false, gdy adres jest niepoprawny (wtedy nic nie zmienia).
     * Pusty tekst wyłącza synchronizację.
     */
    fun setUrl(raw: String): Boolean {
        val normalized = if (raw.isBlank()) "" else normalize(raw) ?: return false
        // Token wydany przez jeden serwer nie ma sensu na innym.
        if (normalized != _url.value) clearSession()
        prefs.edit().putString(KEY_URL, normalized).apply()
        _url.value = normalized
        return true
    }

    fun clientId(kind: String, localId: Long): String = "$deviceId-$kind$localId"

    companion object {
        const val DEFAULT_URL = "https://hackyeah.duckdns.org/"
        private const val KEY_URL = "server_url"
        private const val KEY_DEVICE = "device_id"
        private const val KEY_USER = "session_user"
        private const val KEY_TOKEN = "session_token"
        private const val KEY_LAST_ACCOUNT = "last_account"
        private const val KEY_MIRROR_URL = "mirror_url"
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

package pl.kajakapp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerSettingsTest {
    @Test
    fun addsTrailingSlash() {
        assertEquals("https://hackyeah.duckdns.org/", ServerSettings.normalize("https://hackyeah.duckdns.org"))
    }

    @Test
    fun keepsAlreadyNormalizedAddress() {
        assertEquals("https://hackyeah.duckdns.org/", ServerSettings.normalize("https://hackyeah.duckdns.org/"))
    }

    @Test
    fun addsHttpsWhenSchemeIsMissing() {
        assertEquals("https://example.com/", ServerSettings.normalize("  example.com  "))
    }

    @Test
    fun keepsPortAndPath() {
        assertEquals("http://10.0.2.2:8080/", ServerSettings.normalize("http://10.0.2.2:8080"))
        assertEquals("https://example.com/kajak/", ServerSettings.normalize("https://example.com/kajak"))
    }

    @Test
    fun rejectsInvalidAddresses() {
        assertNull(ServerSettings.normalize(""))
        assertNull(ServerSettings.normalize("ftp://example.com"))
        assertNull(ServerSettings.normalize("https://"))
        assertNull(ServerSettings.normalize("https://example.com/?a=1"))
        assertNull(ServerSettings.normalize("http://example.com"))
    }
}

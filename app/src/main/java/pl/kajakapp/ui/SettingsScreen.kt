@file:OptIn(ExperimentalMaterial3Api::class)

package pl.kajakapp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(onBack: () -> Unit, onOpenAuth: () -> Unit) {
    val container = rememberContainer()
    val settings = container.settings
    val session by settings.session.collectAsStateWithLifecycle()
    var status by remember { mutableStateOf<String?>(null) }
    var statusIsError by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Scaffold(
        contentWindowInsets = NoInsets,
        topBar = {
            AppTopBar(
                windowInsets = NoInsets,
                title = { Text("Serwer") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Wstecz")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Konto", style = MaterialTheme.typography.titleMedium)
            val account = session
            if (account != null) {
                Text("Zalogowano jako ${account.username}.")
                OutlinedButton(onClick = {
                    scope.launch {
                        val result = container.sync.logout()
                        status = result.message
                        statusIsError = false
                    }
                }) { Text("Wyloguj") }
            } else {
                Text(
                    "Nie jesteś zalogowany. Bez konta możesz korzystać z aplikacji lokalnie i czytać " +
                        "trasy oraz przeszkody; wysyłanie danych i spływy na serwerze wymagają konta."
                )
                OutlinedButton(onClick = onOpenAuth) { Text("Zaloguj / zarejestruj") }
            }
            Text("Połączenie", style = MaterialTheme.typography.titleMedium)
            Text(
                "Przez serwer aplikacja wymienia trasy, przeszkody, spływy i zameldowania z innymi " +
                    "użytkownikami.",
                style = MaterialTheme.typography.bodyMedium
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    enabled = !testing,
                    onClick = {
                        testing = true
                        status = null
                        scope.launch {
                            val result = container.sync.testConnection()
                            status = result.message
                            statusIsError = !result.ok
                            testing = false
                        }
                    }
                ) { Text("Testuj połączenie") }
                if (testing) CircularProgressIndicator(Modifier.padding(start = 4.dp))
            }
            status?.let {
                Text(
                    it,
                    color = if (statusIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                )
            }
            Text(
                "Uwaga: trasy i przeszkody są publiczne. Spływy (uczestnicy, wyposażenie, pozycje GPS " +
                    "z zameldowań) widzą tylko ich uczestnicy, ale administrator serwera ma dostęp do " +
                    "wszystkich danych.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

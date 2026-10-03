@file:OptIn(ExperimentalMaterial3Api::class)

package pl.kajakapp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

private val USERNAME_RE = Regex("^[A-Za-z0-9_.-]{3,24}$")
private const val MIN_PASSWORD = 8
private const val MAX_PASSWORD = 128

/** Logowanie i rejestracja: tylko nazwa użytkownika i hasło (bez e-maila). */
@Composable
fun AuthScreen(onBack: () -> Unit, onDone: () -> Unit) {
    val container = rememberContainer()
    val scope = rememberCoroutineScope()

    var registering by rememberSaveable { mutableStateOf(false) }
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var repeat by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun submit() {
        val name = username.trim()
        error = when {
            registering && !USERNAME_RE.matches(name) ->
                "Nazwa użytkownika: 3-24 znaków, tylko litery a-z, cyfry oraz _ . -"
            name.isEmpty() -> "Podaj nazwę użytkownika."
            registering && password.length < MIN_PASSWORD -> "Hasło musi mieć co najmniej $MIN_PASSWORD znaków."
            password.isEmpty() -> "Podaj hasło."
            registering && password != repeat -> "Hasła nie są takie same."
            else -> null
        }
        if (error != null) return
        busy = true
        scope.launch {
            val result = container.sync.authenticate(name, password, registering)
            busy = false
            if (result.ok) {
                onDone()
            } else {
                error = result.message
            }
        }
    }

    Scaffold(
        contentWindowInsets = NoInsets,
        topBar = {
            TopAppBar(
                windowInsets = NoInsets,
                title = { Text(if (registering) "Rejestracja" else "Logowanie") },
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
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                if (registering) {
                    "Utwórz konto: wystarczy unikalna nazwa użytkownika i hasło. Nazwa będzie " +
                        "widoczna dla innych jako Twoje imię w spływach."
                } else {
                    "Zaloguj się nazwą użytkownika i hasłem."
                },
                style = MaterialTheme.typography.bodyMedium
            )
            OutlinedTextField(
                value = username,
                onValueChange = { if (it.length <= 24) username = it },
                label = { Text("Nazwa użytkownika") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = password,
                onValueChange = { if (it.length <= MAX_PASSWORD) password = it },
                label = { Text("Hasło") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth()
            )
            if (registering) {
                OutlinedTextField(
                    value = repeat,
                    onValueChange = { if (it.length <= MAX_PASSWORD) repeat = it },
                    label = { Text("Powtórz hasło") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            PrimaryAction(
                text = if (registering) "Utwórz konto" else "Zaloguj",
                onClick = { submit() },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            )
            if (busy) CircularProgressIndicator()
            TextButton(onClick = {
                registering = !registering
                error = null
            }) {
                Text(if (registering) "Mam już konto – zaloguj" else "Nie mam konta – zarejestruj")
            }
            Text(
                "Nie ma odzyskiwania hasła (konto nie ma e-maila) – zapamiętaj je.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@file:OptIn(ExperimentalMaterial3Api::class)

package pl.kajakapp.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Surface
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import pl.kajakapp.AppContainer
import pl.kajakapp.KajakApp
import pl.kajakapp.domain.RISK_DISCLAIMER
import pl.kajakapp.domain.RiskAssessment
import pl.kajakapp.domain.RiskLevel
import pl.kajakapp.util.GeoPoint
import pl.kajakapp.util.LocationHelper

/** Zewnętrzny Scaffold obsługuje marginesy systemowe, więc ekrany wewnętrzne ich nie dublują. */
val NoInsets = WindowInsets(0, 0, 0, 0)

/** Płaski pasek tytułu w kolorze tła – bez cieni i zmiany koloru przy przewijaniu. */
@Composable
fun AppTopBar(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    windowInsets: WindowInsets = NoInsets
) {
    Column(modifier) {
        TopAppBar(
            title = title,
            navigationIcon = navigationIcon,
            actions = actions,
            windowInsets = windowInsets,
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
                scrolledContainerColor = MaterialTheme.colorScheme.background,
                titleContentColor = MaterialTheme.colorScheme.onBackground,
                actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                navigationIconContentColor = MaterialTheme.colorScheme.onSurface
            )
        )
        // Delikatna kreska oddzielająca tytuł od treści ekranu.
        HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/** Kafelek z jedną wartością (np. dystans): mała etykieta nad dużą liczbą. */
@Composable
fun StatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    note: String? = null
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            if (note != null) {
                Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.padding(top = 8.dp),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface
    )
}

/** Pusty ekran: krótko mówi, co tu będzie, i podpowiada pierwszy krok. */
@Composable
fun EmptyState(title: String, message: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 40.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (action != null) {
            Spacer(Modifier.height(4.dp))
            action()
        }
    }
}

@Composable
fun rememberContainer(): AppContainer =
    (LocalContext.current.applicationContext as KajakApp).container

fun riskColor(level: RiskLevel): Color = when (level) {
    RiskLevel.FAVORABLE -> Color(0xFF2E7D32)
    RiskLevel.ELEVATED -> Color(0xFFB85C00)
    RiskLevel.EXTREME -> Color(0xFFC62828)
    RiskLevel.UNKNOWN -> Color(0xFF546E7A)
}

@Composable
fun RiskBanner(assessment: RiskAssessment?, modifier: Modifier = Modifier) {
    val level = assessment?.level ?: RiskLevel.UNKNOWN
    Card(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = riskColor(level),
            contentColor = Color.White
        )
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = level.label,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            if (assessment == null) {
                Text("Ładowanie…")
            } else {
                assessment.factors.forEach { Text("• ${it.message}") }
            }
            Spacer(Modifier.height(8.dp))
            Text(RISK_DISCLAIMER, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Prosty wybór z listy (przycisk + menu), oparty wyłącznie o stabilne API Material3. */
@Composable
fun <T> ChoiceButton(
    selectedLabel: String,
    options: List<T>,
    optionLabel: (T) -> String,
    onSelected: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { expanded = true }) { Text(selectedLabel) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionLabel(option)) },
                    onClick = {
                        expanded = false
                        onSelected(option)
                    }
                )
            }
        }
    }
}

@Composable
fun PrimaryAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        modifier = modifier.height(52.dp),
        enabled = enabled,
        shape = MaterialTheme.shapes.medium
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

/**
 * Zwraca funkcję, która (w razie potrzeby po zapytaniu o uprawnienie) ustala pozycję i
 * przekazuje ją do [onResult]. Wynik `null` oznacza brak uprawnienia lub brak pozycji.
 */
@Composable
fun rememberLocationRequester(onResult: (GeoPoint?) -> Unit): () -> Unit {
    val context = LocalContext.current
    val helper = remember(context) { LocationHelper(context.applicationContext) }
    val scope = rememberCoroutineScope()
    val latestOnResult by rememberUpdatedState(onResult)

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        scope.launch { latestOnResult(helper.current()) }
    }

    return remember(helper, scope, launcher) {
        val request: () -> Unit = {
            if (helper.hasPermission()) {
                scope.launch { latestOnResult(helper.current()) }
            } else {
                launcher.launch(
                    arrayOf(
                        android.Manifest.permission.ACCESS_FINE_LOCATION,
                        android.Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                )
            }
        }
        request
    }
}

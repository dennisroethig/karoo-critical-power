package io.hammerhead.karoocriticalpower.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.hammerhead.karoocriticalpower.data.ApiError
import io.hammerhead.karoocriticalpower.data.PowerCurveRepository
import io.hammerhead.karoocriticalpower.data.CriticalPowerSettings
import io.hammerhead.karoocriticalpower.data.PrTimeframe
import io.hammerhead.karoocriticalpower.data.SettingsDataStore
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settingsDataStore: SettingsDataStore,
    powerCurveRepository: PowerCurveRepository? = null,
    onTestConnection: (suspend (String, String) -> Pair<Boolean, String?>)? = null,
    onClose: () -> Unit = {}
) {
    val settings by settingsDataStore.settings.collectAsState(initial = CriticalPowerSettings())
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    // Repository state
    val isLoading by powerCurveRepository?.isLoading?.collectAsState() ?: remember { mutableStateOf(false) }
    val lastError by powerCurveRepository?.lastError?.collectAsState() ?: remember { mutableStateOf<ApiError?>(null) }
    val lastFetchTime by powerCurveRepository?.lastFetchTime?.collectAsState() ?: remember { mutableStateOf<Long?>(null) }
    val isFromCache by powerCurveRepository?.isFromCache?.collectAsState() ?: remember { mutableStateOf(false) }
    val powerCurve by powerCurveRepository?.powerCurve?.collectAsState() ?: remember { mutableStateOf(null) }

    var apiKey by remember(settings.intervalsApiKey) { mutableStateOf(settings.intervalsApiKey) }
    var athleteId by remember(settings.intervalsAthleteId) { mutableStateOf(settings.intervalsAthleteId) }
    var timeframeExpanded by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var isTesting by remember { mutableStateOf(false) }

    // Input validation
    val apiKeyError = if (apiKey.isNotBlank() && apiKey.length < 10) "API key seems too short" else null
    val athleteIdError = if (athleteId.isNotBlank() && !athleteId.matches(Regex("^i?\\d+$"))) "Should be like 'i12345' or '12345'" else null

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Critical Power Settings") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Text("<", style = MaterialTheme.typography.titleLarge)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(paddingValues)
                .padding(16.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Connect to intervals.icu to show PR comparisons during your ride.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text("API Key") },
                placeholder = { Text("Your intervals.icu API key") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                singleLine = true,
                isError = apiKeyError != null,
                supportingText = if (apiKeyError != null) {
                    { Text(apiKeyError, color = MaterialTheme.colorScheme.error) }
                } else null,
                modifier = Modifier.fillMaxWidth()
            )

            Text(
                text = "Find your API key in intervals.icu Settings > Developer Settings",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedTextField(
                value = athleteId,
                onValueChange = { athleteId = it },
                label = { Text("Athlete ID") },
                placeholder = { Text("i12345") },
                singleLine = true,
                isError = athleteIdError != null,
                supportingText = if (athleteIdError != null) {
                    { Text(athleteIdError, color = MaterialTheme.colorScheme.error) }
                } else null,
                modifier = Modifier.fillMaxWidth()
            )

            Text(
                text = "Your athlete ID from your intervals.icu profile URL",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            ExposedDropdownMenuBox(
                expanded = timeframeExpanded,
                onExpandedChange = { timeframeExpanded = it }
            ) {
                OutlinedTextField(
                    value = settings.prTimeframe.displayName,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("PR Timeframe") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = timeframeExpanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor()
                )

                ExposedDropdownMenu(
                    expanded = timeframeExpanded,
                    onDismissRequest = { timeframeExpanded = false }
                ) {
                    PrTimeframe.entries.forEach { timeframe ->
                        DropdownMenuItem(
                            text = { Text(timeframe.displayName) },
                            onClick = {
                                scope.launch {
                                    settingsDataStore.updatePrTimeframe(timeframe)
                                }
                                timeframeExpanded = false
                            }
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Show PR Comparison",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Switch(
                    checked = settings.showPrComparison,
                    onCheckedChange = { checked ->
                        scope.launch {
                            settingsDataStore.updateShowPrComparison(checked)
                        }
                    }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        scope.launch {
                            settingsDataStore.updateSettings(
                                settings.copy(
                                    intervalsApiKey = apiKey,
                                    intervalsAthleteId = athleteId
                                )
                            )
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Save")
                }

                if (onTestConnection != null) {
                    Button(
                        onClick = {
                            scope.launch {
                                isTesting = true
                                testResult = null
                                val (success, errorMsg) = onTestConnection(apiKey, athleteId)
                                testResult = if (success) {
                                    "Connection successful!"
                                } else {
                                    errorMsg ?: "Connection failed"
                                }
                                isTesting = false
                            }
                        },
                        enabled = apiKey.isNotBlank() && athleteId.isNotBlank() && !isTesting && apiKeyError == null && athleteIdError == null,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (isTesting) "Testing..." else "Test")
                    }
                }
            }

            testResult?.let { result ->
                Text(
                    text = result,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (result.contains("successful")) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Status section
            StatusSection(
                isConfigured = settings.isConfigured,
                isLoading = isLoading,
                lastError = lastError,
                lastFetchTime = lastFetchTime,
                isFromCache = isFromCache,
                prCount = powerCurve?.durationToWatts?.size ?: 0
            )
        }
    }
}

@Composable
private fun StatusSection(
    isConfigured: Boolean,
    isLoading: Boolean,
    lastError: ApiError?,
    lastFetchTime: Long?,
    isFromCache: Boolean,
    prCount: Int
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                shape = RoundedCornerShape(8.dp)
            )
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "Status",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface
        )

        // Loading indicator
        if (isLoading) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp
                )
                Text(
                    text = "Fetching power curve data...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Error display
        if (lastError != null && !isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f),
                        RoundedCornerShape(4.dp)
                    )
                    .padding(8.dp)
            ) {
                Text(
                    text = lastError.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }

        // Configuration status
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "Configuration:",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = if (isConfigured) "Ready" else "Not configured",
                style = MaterialTheme.typography.bodySmall,
                color = if (isConfigured) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // PR data status
        if (prCount > 0) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "PR Data:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "$prCount durations loaded",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
                if (isFromCache) {
                    Text(
                        text = "(cached)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Last fetch time
        if (lastFetchTime != null && lastFetchTime > 0L) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "Last updated:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = formatFetchTime(lastFetchTime),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun formatFetchTime(timestamp: Long): String {
    val ageMs = System.currentTimeMillis() - timestamp
    val minutes = ageMs / (1000 * 60)
    val hours = minutes / 60
    val days = hours / 24
    return when {
        minutes < 1 -> "Just now"
        minutes < 60 -> "$minutes min ago"
        hours < 24 -> "$hours hr ago"
        else -> "$days day${if (days > 1) "s" else ""} ago"
    }
}

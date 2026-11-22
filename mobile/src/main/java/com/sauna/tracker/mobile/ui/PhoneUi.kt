@file:OptIn(ExperimentalMaterial3Api::class)

package com.sauna.tracker.mobile.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.sauna.tracker.mobile.CsvShare
import com.sauna.tracker.mobile.R
import com.sauna.tracker.shared.Session
import com.sauna.tracker.shared.Settings
import com.sauna.tracker.shared.SyncHub
import com.sauna.tracker.shared.db.SaunaDb
import com.sauna.tracker.shared.db.SessionEntity
import com.sauna.tracker.shared.db.loadSession
import com.sauna.tracker.shared.db.toSession
import com.sauna.tracker.shared.formatDuration
import com.sauna.tracker.shared.ui.ChartSeries
import com.sauna.tracker.shared.ui.HrChart
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val HrColor = Color(0xFFE65100)
private val LimitColor = Color(0xFFD32F2F)
/** Distinct colours for overlaid rounds. */
private val Palette = listOf(
    Color(0xFFE65100), Color(0xFF1E88E5), Color(0xFF43A047), Color(0xFF8E24AA),
    Color(0xFFFDD835), Color(0xFF00ACC1), Color(0xFF6D4C41), Color(0xFFEC407A),
)

private val dateFormat = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
private fun formatDate(epochMs: Long) = dateFormat.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

@Composable
fun PhoneNav() {
    val nav = rememberNavController()
    NavHost(nav, startDestination = "list") {
        composable("list") {
            ListScreen(
                onOpen = { nav.navigate("detail/$it") },
                onCompare = { ids -> nav.navigate("compare/" + ids.joinToString(",")) },
                onSettings = { nav.navigate("settings") },
            )
        }
        composable("detail/{id}") {
            DetailScreen(id = it.arguments?.getString("id").orEmpty(), onBack = { nav.popBackStack() })
        }
        composable("compare/{ids}") {
            val ids = it.arguments?.getString("ids").orEmpty().split(',').filter(String::isNotBlank)
            CompareScreen(ids = ids, onBack = { nav.popBackStack() })
        }
        composable("settings") { SettingsScreen(onBack = { nav.popBackStack() }) }
    }
}

// ---------------------------------------------------------------- List

@Composable
private fun ListScreen(onOpen: (String) -> Unit, onCompare: (List<String>) -> Unit, onSettings: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val hub = remember { SyncHub.get(ctx) }
    val dao = remember { SaunaDb.get(ctx).sessions() }
    val sessions by remember { dao.observeFinished() }.collectAsStateWithLifecycle(emptyList())
    var selected by rememberSaveable { mutableStateOf(listOf<String>()) }
    var confirmDelete by remember { mutableStateOf(false) }
    // Drop selections whose session no longer exists (deleted on the watch).
    val selectedIds = selected.filter { id -> sessions.any { it.id == id } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.sessions)) },
                actions = {
                    IconButton(onClick = {
                        scope.launch {
                            val ok = hub.requestSync()
                            Toast.makeText(ctx, if (ok) R.string.sync_requested else R.string.no_watch, Toast.LENGTH_SHORT).show()
                        }
                    }) { Icon(Icons.Filled.Refresh, stringResource(R.string.request_sync)) }
                    IconButton(
                        enabled = sessions.isNotEmpty(),
                        onClick = { scope.launch { CsvShare.share(ctx, sessions.mapNotNull { dao.loadSession(it.id) }) } },
                    ) { Icon(Icons.Filled.Share, stringResource(R.string.export_all)) }
                    IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, stringResource(R.string.settings)) }
                },
            )
        },
        bottomBar = {
            if (selectedIds.isNotEmpty()) {
                BottomAppBar {
                    TextButton(onClick = { selected = emptyList() }) { Text(stringResource(R.string.clear_selection)) }
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(onClick = { confirmDelete = true }) {
                        Text(stringResource(R.string.delete_fmt, selectedIds.size))
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { onCompare(selectedIds) }) {
                        Text(stringResource(R.string.compare_fmt, selectedIds.size))
                    }
                    Spacer(Modifier.width(8.dp))
                }
            }
        },
    ) { padding ->
        if (sessions.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding).padding(32.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.no_sessions), textAlign = TextAlign.Center)
            }
        } else {
            LazyColumn(contentPadding = padding) {
                items(sessions, key = { it.id }) { s ->
                    val checked = s.id in selectedIds
                    SessionRow(
                        s = s,
                        checked = checked,
                        onCheck = { selected = if (checked) selectedIds - s.id else selectedIds + s.id },
                        onClick = { onOpen(s.id) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    if (confirmDelete) {
        DeleteDialog(
            count = selectedIds.size,
            onDismiss = { confirmDelete = false },
            onConfirm = {
                val ids = selectedIds
                confirmDelete = false
                selected = emptyList()
                scope.launch { ids.forEach { hub.deleteSession(it) } }
            },
        )
    }
}

@Composable
private fun SessionRow(s: SessionEntity, checked: Boolean, onCheck: () -> Unit, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = { Checkbox(checked = checked, onCheckedChange = { onCheck() }) },
        headlineContent = { Text(formatDate(s.startedAt)) },
        supportingContent = {
            Column {
                Text(stringResource(R.string.summary_fmt, formatDuration(s.durationMs), s.maxBpm, s.avgBpm))
                if (s.interrupted) Text(stringResource(R.string.interrupted), color = MaterialTheme.colorScheme.error)
            }
        },
        trailingContent = {
            Text(
                "${s.maxBpm}",
                fontWeight = FontWeight.Bold,
                color = if (s.maxBpm >= s.hrLimit) LimitColor else MaterialTheme.colorScheme.onSurface,
            )
        },
    )
}

@Composable
private fun DeleteDialog(count: Int, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.delete_title)) },
        text = { Text(stringResource(R.string.delete_message_fmt, count)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.delete)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

// ---------------------------------------------------------------- Detail

@Composable
private fun DetailScreen(id: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val dao = remember { SaunaDb.get(ctx).sessions() }
    val row by remember(id) { dao.observeSession(id) }.collectAsStateWithLifecycle(null)
    val samples by remember(id) { dao.observeSamples(id) }.collectAsStateWithLifecycle(emptyList())
    var confirmDelete by remember { mutableStateOf(false) }
    val s = row?.toSession(samples)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(s?.let { formatDate(it.startedAt) } ?: "") },
                navigationIcon = { BackButton(onBack) },
                actions = {
                    IconButton(enabled = s != null, onClick = { s?.let { scope.launch { CsvShare.share(ctx, listOf(it)) } } }) {
                        Icon(Icons.Filled.Share, stringResource(R.string.share_csv))
                    }
                    IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, stringResource(R.string.delete)) }
                },
            )
        },
    ) { padding ->
        if (s == null) return@Scaffold
        Column(Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState())) {
            Text(stringResource(R.string.limits_fmt, s.timeLimitMin, s.hrLimit), style = MaterialTheme.typography.bodyMedium)
            if (s.interrupted) Text(stringResource(R.string.interrupted), color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(12.dp))
            Chart(listOf(ChartSeries(s.samples, HrColor)), hrLimit = s.hrLimit, timeLimitMin = s.timeLimitMin)
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Stat(stringResource(R.string.duration), formatDuration(s.durationMs))
                Stat(stringResource(R.string.max), "${s.maxBpm}")
                Stat(stringResource(R.string.avg), "${s.avgBpm}")
                Stat(stringResource(R.string.above_limit), formatDuration(s.timeAboveLimitMs))
            }
        }
    }

    if (confirmDelete) {
        DeleteDialog(
            count = 1,
            onDismiss = { confirmDelete = false },
            onConfirm = {
                confirmDelete = false
                scope.launch { SyncHub.get(ctx).deleteSession(id) }
                onBack()
            },
        )
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Chart(series: List<ChartSeries>, hrLimit: Int?, timeLimitMin: Int?) {
    Card {
        Column(Modifier.padding(12.dp)) {
            HrChart(
                series = series,
                hrLimit = hrLimit,
                timeLimitMin = timeLimitMin,
                limitColor = LimitColor,
                labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                gridColor = MaterialTheme.colorScheme.outlineVariant,
                labelSize = 11.sp,
                modifier = Modifier.fillMaxWidth().height(280.dp),
            )
            Text(
                stringResource(R.string.x_axis),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
    }
}

// ---------------------------------------------------------------- Compare

@Composable
private fun CompareScreen(ids: List<String>, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val sessions by produceState(emptyList<Session>(), ids) {
        val dao = SaunaDb.get(ctx).sessions()
        value = ids.mapNotNull { dao.loadSession(it) }.sortedBy { it.startedAt }
    }
    val sameHrLimit = sessions.map { it.hrLimit }.distinct().singleOrNull()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.compare)) },
                navigationIcon = { BackButton(onBack) },
                actions = {
                    IconButton(enabled = sessions.isNotEmpty(), onClick = { scope.launch { CsvShare.share(ctx, sessions) } }) {
                        Icon(Icons.Filled.Share, stringResource(R.string.share_csv))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState())) {
            Chart(
                series = sessions.mapIndexed { i, s -> ChartSeries(s.samples, Palette[i % Palette.size]) },
                hrLimit = sameHrLimit,
                timeLimitMin = null,
            )
            Spacer(Modifier.height(16.dp))
            CompareTable(sessions)
        }
    }
}

@Composable
private fun CompareTable(sessions: List<Session>) {
    val header = MaterialTheme.typography.labelMedium
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.width(20.dp))
        Text(stringResource(R.string.date), Modifier.weight(2.2f), style = header)
        Text(stringResource(R.string.duration), Modifier.weight(1.2f), style = header, textAlign = TextAlign.End)
        Text(stringResource(R.string.max), Modifier.weight(0.8f), style = header, textAlign = TextAlign.End)
        Text(stringResource(R.string.avg), Modifier.weight(0.8f), style = header, textAlign = TextAlign.End)
        Text(stringResource(R.string.above_limit), Modifier.weight(1.2f), style = header, textAlign = TextAlign.End)
    }
    HorizontalDivider()
    sessions.forEachIndexed { i, s ->
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(12.dp).background(Palette[i % Palette.size], CircleShape))
            Spacer(Modifier.width(8.dp))
            Text(formatDate(s.startedAt) + if (s.interrupted) " ⚠" else "", Modifier.weight(2.2f), style = MaterialTheme.typography.bodySmall)
            Text(formatDuration(s.durationMs), Modifier.weight(1.2f), textAlign = TextAlign.End)
            Text("${s.maxBpm}", Modifier.weight(0.8f), textAlign = TextAlign.End, fontWeight = FontWeight.Bold)
            Text("${s.avgBpm}", Modifier.weight(0.8f), textAlign = TextAlign.End)
            Text(formatDuration(s.timeAboveLimitMs), Modifier.weight(1.2f), textAlign = TextAlign.End)
        }
        HorizontalDivider()
    }
}

// ---------------------------------------------------------------- Settings

@Composable
private fun SettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val hub = remember { SyncHub.get(ctx) }
    val settings by remember { hub.settingsStore.settings }.collectAsStateWithLifecycle(Settings())

    fun update(time: Int = settings.timeLimitMin, hr: Int = settings.hrLimit) {
        scope.launch { hub.updateSettings(time, hr) }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.settings)) }, navigationIcon = { BackButton(onBack) }) },
    ) { padding ->
        Column(Modifier.padding(padding).padding(16.dp)) {
            LimitRow(
                label = stringResource(R.string.time_limit),
                value = settings.timeLimitMin,
                unit = stringResource(R.string.unit_min),
                range = Settings.TIME_RANGE,
                onChange = { update(time = it) },
            )
            HorizontalDivider()
            LimitRow(
                label = stringResource(R.string.hr_limit),
                value = settings.hrLimit,
                unit = stringResource(R.string.unit_bpm),
                range = Settings.HR_RANGE,
                onChange = { update(hr = it) },
            )
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.settings_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LimitRow(label: String, value: Int, unit: String, range: IntRange, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        FilledTonalButton(
            onClick = { onChange((value - 1).coerceIn(range)) },
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier.size(44.dp),
        ) { Text("−", style = MaterialTheme.typography.titleLarge) }
        Text(
            "$value $unit",
            Modifier.width(96.dp),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.titleLarge,
        )
        FilledTonalButton(
            onClick = { onChange((value + 1).coerceIn(range)) },
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier.size(44.dp),
        ) { Text("+", style = MaterialTheme.typography.titleLarge) }
    }
}

@Composable
private fun BackButton(onBack: () -> Unit) {
    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
}

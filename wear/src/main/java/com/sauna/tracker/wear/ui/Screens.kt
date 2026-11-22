package com.sauna.tracker.wear.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.CompactChip
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.sauna.tracker.shared.Settings
import com.sauna.tracker.shared.Repo
import com.sauna.tracker.shared.db.SaunaDb
import com.sauna.tracker.shared.db.toSample
import com.sauna.tracker.shared.formatDuration
import com.sauna.tracker.shared.ui.ChartSeries
import com.sauna.tracker.shared.ui.HrChart
import com.sauna.tracker.wear.LiveState
import com.sauna.tracker.wear.R
import com.sauna.tracker.wear.appScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.abs

private val Orange = Color(0xFFFF8F00)
private val Danger = Color(0xFFE53935)
private val HrLine = Color(0xFFFF7043)
private val Dim = Color(0xFF9E9E9E)

private val dateFormat = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
private fun formatDate(epochMs: Long) = dateFormat.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

// ---------------------------------------------------------------- Home

@Composable
fun HomeScreen(
    live: LiveState,
    onStart: () -> Unit,
    onOpenRound: () -> Unit,
    onRecent: () -> Unit,
    onSettings: () -> Unit,
    onSend: () -> Unit,
) {
    val ctx = LocalContext.current
    val hub = remember { Repo.get(ctx) }
    val settings by hub.settingsStore.settings.collectAsStateWithLifecycle(Settings())
    val unsent by remember { SaunaDb.get(ctx).sessions().observeUnsyncedCount() }.collectAsStateWithLifecycle(0)
    val listState = rememberScalingLazyListState()
    val limits = stringResource(R.string.limits_fmt, settings.timeLimitMin, settings.hrLimit)

    Scaffold(
        timeText = { TimeText() },
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) },
    ) {
        ScalingLazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            item { ListHeader { Text(stringResource(R.string.app_name)) } }
            item {
                if (live.running) {
                    Chip(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onOpenRound,
                        label = { Text(stringResource(R.string.session_running)) },
                        secondaryLabel = { Text(formatDuration(live.elapsedMs)) },
                        icon = { Icon(painterResource(R.drawable.ic_heart), null) },
                        colors = ChipDefaults.chipColors(backgroundColor = Danger),
                    )
                } else {
                    Chip(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onStart,
                        label = { Text(stringResource(R.string.start), fontWeight = FontWeight.Bold) },
                        secondaryLabel = { Text(limits) },
                        icon = { Icon(painterResource(R.drawable.ic_heart), null) },
                        colors = ChipDefaults.chipColors(backgroundColor = Orange, contentColor = Color.Black),
                    )
                }
            }
            item {
                Chip(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onRecent,
                    label = { Text(stringResource(R.string.recent)) },
                    colors = ChipDefaults.secondaryChipColors(),
                )
            }
            item {
                Chip(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onSettings,
                    label = { Text(stringResource(R.string.settings)) },
                    secondaryLabel = { Text(limits) },
                    colors = ChipDefaults.secondaryChipColors(),
                )
            }
            item {
                Chip(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onSend,
                    label = { Text(stringResource(R.string.send_to_phone)) },
                    secondaryLabel = {
                        Text(if (unsent > 0) stringResource(R.string.unsent_fmt, unsent) else stringResource(R.string.all_sent))
                    },
                    colors = ChipDefaults.secondaryChipColors(),
                )
            }
        }
    }
}

// ---------------------------------------------------------------- Running round

@Composable
fun SessionScreen(live: LiveState, ambient: Boolean, ambientTick: Int, onStop: () -> Unit) {
    val limitMs = live.timeLimitMin * 60_000L
    val overTime = live.elapsedMs >= limitMs
    val overHr = (live.bpm ?: 0) >= live.hrLimit

    if (ambient) {
        // Low-power view: black background, grey text, refreshed about once a minute.
        val snapshotMs = remember(ambientTick) { live.elapsedMs }
        val snapshotBpm = remember(ambientTick) { live.bpm }
        Column(
            Modifier.fillMaxSize().background(Color.Black),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.ambient_min_fmt, snapshotMs / 60_000), fontSize = 36.sp, color = Dim)
            Text("♥ ${snapshotBpm ?: "--"}", fontSize = 26.sp, color = Dim)
        }
        return
    }

    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            progress = (live.elapsedMs.toFloat() / limitMs).coerceIn(0f, 1f),
            modifier = Modifier.fillMaxSize().padding(3.dp),
            indicatorColor = if (overTime) Danger else Orange,
            trackColor = Color(0xFF333333),
            strokeWidth = 6.dp,
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                formatDuration(live.elapsedMs),
                fontSize = 40.sp,
                fontWeight = FontWeight.Bold,
                color = if (overTime) Danger else Color.White,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painterResource(R.drawable.ic_heart), null,
                    tint = if (overHr) Danger else HrLine,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    live.bpm?.toString() ?: "--",
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (overHr) Danger else Color.White,
                )
                Text(" " + stringResource(R.string.unit_bpm), fontSize = 12.sp, color = Dim)
            }
            if (live.sensorError) {
                Text(stringResource(R.string.hr_error), fontSize = 11.sp, color = Danger, textAlign = TextAlign.Center)
            }
            HrChart(
                series = listOf(ChartSeries(live.samples, HrLine)),
                hrLimit = live.hrLimit,
                showLabels = false,
                minDurationMs = limitMs,
                modifier = Modifier.width(130.dp).height(34.dp).padding(vertical = 2.dp),
            )
            ConfirmChip(
                label = stringResource(R.string.stop),
                confirmLabel = stringResource(R.string.tap_again),
                onConfirm = onStop,
            )
        }
    }
}

/** Needs two taps, so a wet finger or a towel can't end the round by accident. */
@Composable
private fun ConfirmChip(label: String, confirmLabel: String, onConfirm: () -> Unit) {
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(armed) {
        if (armed) { delay(3000); armed = false }
    }
    CompactChip(
        onClick = { if (armed) onConfirm() else armed = true },
        label = { Text(if (armed) confirmLabel else label) },
        colors = ChipDefaults.chipColors(backgroundColor = if (armed) Danger else Color(0xFF5D4037)),
    )
}

// ---------------------------------------------------------------- Summary of one round

@Composable
fun SummaryScreen(id: String, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val dao = remember { SaunaDb.get(ctx).sessions() }
    val row by remember(id) { dao.observeSession(id) }.collectAsStateWithLifecycle(null)
    val samples by remember(id) { dao.observeSamples(id) }.collectAsStateWithLifecycle(emptyList())
    val listState = rememberScalingLazyListState()
    val s = row ?: return

    Scaffold(positionIndicator = { PositionIndicator(scalingLazyListState = listState) }) {
        ScalingLazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            item { ListHeader { Text(formatDate(s.startedAt), textAlign = TextAlign.Center) } }
            item {
                HrChart(
                    series = listOf(ChartSeries(samples.map { it.toSample() }, HrLine)),
                    hrLimit = s.hrLimit,
                    timeLimitMin = s.timeLimitMin,
                    labelSize = 8.sp,
                    modifier = Modifier.fillMaxWidth().height(90.dp),
                )
            }
            item { Text(formatDuration(s.durationMs), fontSize = 28.sp, fontWeight = FontWeight.Bold) }
            item { Text(stringResource(R.string.max_avg_fmt, s.maxBpm, s.avgBpm)) }
            if (s.interrupted) {
                item { Text(stringResource(R.string.interrupted), color = Orange, fontSize = 12.sp, textAlign = TextAlign.Center) }
            }
            item {
                Text(
                    stringResource(if (s.synced) R.string.synced else R.string.not_synced),
                    color = Dim, fontSize = 12.sp,
                )
            }
            item {
                Chip(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onDone,
                    label = { Text(stringResource(R.string.done)) },
                )
            }
            item {
                ConfirmChip(
                    label = stringResource(R.string.delete),
                    confirmLabel = stringResource(R.string.tap_again),
                    onConfirm = {
                        appScope.launch { Repo.get(ctx).deleteSession(id) }
                        onDone()
                    },
                )
            }
        }
    }
}

// ---------------------------------------------------------------- Recent rounds

@Composable
fun RecentScreen(onOpen: (String) -> Unit) {
    val ctx = LocalContext.current
    val sessions by remember { SaunaDb.get(ctx).sessions().observeFinished() }.collectAsStateWithLifecycle(emptyList())
    val listState = rememberScalingLazyListState()

    Scaffold(
        timeText = { TimeText() },
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) },
    ) {
        ScalingLazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            item { ListHeader { Text(stringResource(R.string.recent)) } }
            if (sessions.isEmpty()) item { Text(stringResource(R.string.no_sessions), color = Dim) }
            sessions.take(30).forEach { s ->
                item(key = s.id) {
                    Chip(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { onOpen(s.id) },
                        label = { Text(formatDate(s.startedAt) + if (s.interrupted) " ⚠" else "") },
                        secondaryLabel = { Text("${formatDuration(s.durationMs)} · ♥ ${s.maxBpm}") },
                        colors = ChipDefaults.secondaryChipColors(),
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Settings

@Composable
fun SettingsScreen(onEdit: (String) -> Unit) {
    val ctx = LocalContext.current
    val settings by remember { Repo.get(ctx).settingsStore.settings }.collectAsStateWithLifecycle(Settings())
    val listState = rememberScalingLazyListState()

    Scaffold(
        timeText = { TimeText() },
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) },
    ) {
        ScalingLazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            item { ListHeader { Text(stringResource(R.string.settings)) } }
            item {
                Chip(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { onEdit("time") },
                    label = { Text(stringResource(R.string.time_limit)) },
                    secondaryLabel = { Text("${settings.timeLimitMin} ${stringResource(R.string.unit_min)}") },
                    colors = ChipDefaults.secondaryChipColors(),
                )
            }
            item {
                Chip(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { onEdit("hr") },
                    label = { Text(stringResource(R.string.hr_limit)) },
                    secondaryLabel = { Text("${settings.hrLimit} ${stringResource(R.string.unit_bpm)}") },
                    colors = ChipDefaults.secondaryChipColors(),
                )
            }
        }
    }
}

/** Big number with − / + buttons; turning the bezel also changes it. Saved automatically. */
@Composable
fun EditLimitScreen(which: String) {
    val ctx = LocalContext.current
    val hub = remember { Repo.get(ctx) }
    val isTime = which == "time"
    val range = if (isTime) Settings.TIME_RANGE else Settings.HR_RANGE
    val settings by hub.settingsStore.settings.collectAsStateWithLifecycle<Settings?>(null)
    var value by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(settings) {
        val s = settings
        if (value == null && s != null) value = if (isTime) s.timeLimitMin else s.hrLimit
    }

    val current by rememberUpdatedState(value)
    fun save() {
        val v = current ?: return
        appScope.launch {
            val s = hub.settingsStore.current()
            if (isTime && v != s.timeLimitMin) hub.updateSettings(v, s.hrLimit)
            if (!isTime && v != s.hrLimit) hub.updateSettings(s.timeLimitMin, v)
        }
    }
    LaunchedEffect(value) { delay(600); save() }
    DisposableEffect(Unit) { onDispose { save() } }

    fun change(delta: Int) { value = ((value ?: return) + delta).coerceIn(range) }

    val focus = remember { FocusRequester() }
    var rotaryAccum by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) { focus.requestFocus() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .onRotaryScrollEvent {
                rotaryAccum += it.verticalScrollPixels
                while (abs(rotaryAccum) >= ROTARY_STEP_PX) {
                    change(if (rotaryAccum > 0) 1 else -1)
                    rotaryAccum -= if (rotaryAccum > 0) ROTARY_STEP_PX else -ROTARY_STEP_PX
                }
                true
            }
            .focusRequester(focus)
            .focusable(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(if (isTime) R.string.time_limit else R.string.hr_limit), color = Dim)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = { change(-1) },
                colors = ButtonDefaults.secondaryButtonColors(),
                modifier = Modifier.size(ButtonDefaults.SmallButtonSize),
            ) { Text("−", fontSize = 22.sp) }
            Text(
                value?.toString() ?: "",
                fontSize = 44.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.width(96.dp),
                textAlign = TextAlign.Center,
            )
            Button(
                onClick = { change(1) },
                colors = ButtonDefaults.secondaryButtonColors(),
                modifier = Modifier.size(ButtonDefaults.SmallButtonSize),
            ) { Text("+", fontSize = 22.sp) }
        }
        Text(stringResource(if (isTime) R.string.unit_min else R.string.unit_bpm), color = Dim)
    }
}

private const val ROTARY_STEP_PX = 48f

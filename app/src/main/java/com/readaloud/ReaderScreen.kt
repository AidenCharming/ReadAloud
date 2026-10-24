package com.readaloud

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.readaloud.data.Chapters
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderPane(vm: AppViewModel, showBack: Boolean, listOpen: Boolean = false, onToggleList: (() -> Unit)? = null) {
    val n = vm.narrator
    val ctx = LocalContext.current
    val listState = rememberLazyListState()
    val snackbar = remember { SnackbarHostState() }
    var pickingVoice by remember { mutableStateOf(false) }
    var pickingSpeed by remember { mutableStateOf(false) }
    var pickingSleep by remember { mutableStateOf(false) }
    var pickingChapter by remember { mutableStateOf(false) }
    val chapters = remember(vm.sentences) { Chapters.find(vm.sentences) }

    if (pickingVoice) VoiceDialog(vm) { pickingVoice = false }
    if (pickingSpeed) SpeedDialog(n.speed, n::changeSpeed) { pickingSpeed = false }
    if (pickingSleep) SleepDialog(n.sleepEndsAt != 0L, n::setSleepTimer) { pickingSleep = false }
    if (pickingChapter) ChapterDialog(chapters, n.index, onPick = { n.seek(it) }) { pickingChapter = false }

    // The notification permission is asked for when listening first starts, with a reason, rather than at launch
    var warnedNotifications by remember { mutableStateOf(false) }
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted && !warnedNotifications) {
            warnedNotifications = true
        }
    }
    LaunchedEffect(warnedNotifications) {
        if (warnedNotifications) snackbar.showSnackbar("Notifications are off, so the lock-screen player won't show. Playback still works.")
    }
    fun togglePlay() {
        if (n.playing) { n.pause(); return }
        val needs = Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needs) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        n.play()
    }

    LaunchedEffect(n.index, vm.sentences) {
        if (vm.sentences.isNotEmpty()) listState.animateScrollToItem((n.index - 2).coerceAtLeast(0))
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                title = { Text(vm.current?.title ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    if (showBack) IconButton(onClick = vm::close) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    } else if (onToggleList != null) IconButton(onClick = onToggleList) {
                        Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = if (listOpen) "Hide book list" else "Show book list")
                    }
                },
                actions = {
                    if (chapters.size >= 2) IconButton(onClick = { pickingChapter = true }) {
                        Icon(Icons.AutoMirrored.Filled.FormatListBulleted, contentDescription = "Chapters")
                    }
                },
            )
        },
        bottomBar = {
            PlayerBar(
                vm, onToggle = ::togglePlay,
                onVoice = { pickingVoice = true }, onSpeed = { pickingSpeed = true }, onSleep = { pickingSleep = true },
            )
        },
    ) { pad ->
        LazyColumn(
            Modifier.padding(pad).fillMaxWidth(),
            state = listState,
            contentPadding = PaddingValues(horizontal = 22.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            itemsIndexed(vm.sentences) { i, s ->
                val active = i == n.index
                val bg by animateColorAsState(
                    if (active) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent, label = "highlight",
                )
                Text(
                    s,
                    style = com.readaloud.ui.ReadingStyle,
                    color = if (active) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.small)
                        .clickable { n.seek(i) }
                        .background(bg)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun PlayerBar(vm: AppViewModel, onToggle: () -> Unit, onVoice: () -> Unit, onSpeed: () -> Unit, onSleep: () -> Unit) {
    val n = vm.narrator
    var dragging by remember { mutableStateOf<Float?>(null) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.large.copy(bottomStart = androidx.compose.foundation.shape.CornerSize(0.dp), bottomEnd = androidx.compose.foundation.shape.CornerSize(0.dp)),
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
    ) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp).navigationBarsPadding()) {
            Slider(
                value = dragging ?: n.index.toFloat(),
                onValueChange = { dragging = it },
                onValueChangeFinished = { dragging?.let { n.seek(it.toInt()) }; dragging = null },
                valueRange = 0f..(n.size - 1).coerceAtLeast(1).toFloat(),
                colors = SliderDefaults.colors(inactiveTrackColor = MaterialTheme.colorScheme.outlineVariant),
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Sentence ${(dragging?.toInt() ?: n.index) + 1} of ${n.size}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${((dragging ?: n.index.toFloat()) * 100 / (n.size - 1).coerceAtLeast(1)).toInt()}%", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(
                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { n.seek(n.index - 1) }, modifier = Modifier.size(52.dp)) {
                    Icon(Icons.Filled.SkipPrevious, contentDescription = "Previous sentence", Modifier.size(30.dp))
                }
                FilledIconButton(
                    onClick = onToggle, enabled = n.ready, modifier = Modifier.padding(horizontal = 18.dp).size(68.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary),
                ) {
                    Icon(
                        if (n.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (n.playing) "Pause" else "Play",
                        modifier = Modifier.size(36.dp),
                    )
                }
                IconButton(onClick = { n.seek(n.index + 1) }, modifier = Modifier.size(52.dp)) {
                    Icon(Icons.Filled.SkipNext, contentDescription = "Next sentence", Modifier.size(30.dp))
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                AssistChip(
                    onClick = onVoice,
                    leadingIcon = { Icon(Icons.Filled.RecordVoiceOver, contentDescription = null, Modifier.size(18.dp)) },
                    label = {
                        Text(
                            when {
                                vm.usePiper -> "Piper · ${vm.piperVoice.label.substringBefore(" ·")}"
                                vm.useKokoro -> vm.kokoroVoice.label.substringBefore(" ·")
                                else -> "System"
                            },
                            maxLines = 1,
                        )
                    },
                )
                AssistChip(
                    onClick = onSpeed,
                    leadingIcon = { Icon(Icons.Filled.Speed, contentDescription = null, Modifier.size(18.dp)) },
                    label = { Text("%.1fx".format(n.speed)) },
                )
                val remaining by produceState(0L, n.sleepEndsAt) {
                    while (n.sleepEndsAt != 0L) {
                        value = (n.sleepEndsAt - SystemClock.elapsedRealtime()).coerceAtLeast(0)
                        delay(1000)
                    }
                    value = 0
                }
                AssistChip(
                    onClick = onSleep,
                    leadingIcon = { Icon(Icons.Filled.Bedtime, contentDescription = null, Modifier.size(18.dp)) },
                    label = { Text(if (n.sleepEndsAt != 0L) "%d:%02d".format(remaining / 60000, remaining / 1000 % 60) else "Sleep") },
                )
            }
        }
    }
}

@Composable
private fun SpeedDialog(speed: Float, onChange: (Float) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Reading speed") },
        text = {
            Column {
                Text("%.1fx".format(speed), style = MaterialTheme.typography.headlineMedium)
                Slider(
                    value = speed,
                    onValueChange = { onChange((it * 10).toInt() / 10f) },
                    valueRange = 0.5f..3f,
                    colors = SliderDefaults.colors(inactiveTrackColor = MaterialTheme.colorScheme.outlineVariant),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0.8f, 1f, 1.25f, 1.5f, 2f).forEach { v ->
                        FilterChip(selected = speed == v, onClick = { onChange(v) }, label = { Text("${v}x".replace(".0x", "x")) })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
private fun SleepDialog(active: Boolean, onSet: (Int) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sleep timer") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Pause reading after:", color = MaterialTheme.colorScheme.onSurfaceVariant)
                listOf(10, 15, 30, 45, 60).forEach { m ->
                    TextButton(onClick = { onSet(m); onDismiss() }, modifier = Modifier.fillMaxWidth()) { Text("$m minutes") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = if (active) ({ TextButton(onClick = { onSet(0); onDismiss() }) { Text("Turn off timer") } }) else null,
    )
}

@Composable
private fun ChapterDialog(chapters: List<com.readaloud.data.Chapter>, current: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    val here = chapters.lastOrNull { it.sentence <= current }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Chapters") },
        text = {
            LazyColumn(Modifier.heightIn(max = 380.dp)) {
                itemsIndexed(chapters) { _, c ->
                    Text(
                        c.title,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (c == here) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.fillMaxWidth().clickable { onPick(c.sentence); onDismiss() }.padding(vertical = 10.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

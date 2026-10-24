package com.readaloud

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.readaloud.tts.KokoroModel
import com.readaloud.ui.scrollIndicator
import com.readaloud.tts.PiperModel

@Composable
fun VoiceDialog(vm: AppViewModel, onDismiss: () -> Unit) {
    val piperList = rememberLazyListState()
    val kokoroList = rememberLazyListState()
    val barColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
    // A big download over mobile data should be a choice, not a surprise
    var pendingDownload by remember { mutableStateOf<(() -> Unit)?>(null) }
    fun download(start: () -> Unit) { if (vm.onMeteredNetwork()) pendingDownload = start else start() }
    pendingDownload?.let { start ->
        AlertDialog(
            onDismissRequest = { pendingDownload = null },
            title = { Text("Use mobile data?") },
            text = { Text("You're not on Wi-Fi. This download is large, so it may use a good part of a mobile data plan.") },
            confirmButton = { TextButton(onClick = { pendingDownload = null; start() }) { Text("Download anyway") } },
            dismissButton = { TextButton(onClick = { pendingDownload = null }) { Text("Wait for Wi-Fi") } },
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Voice") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                VoiceRow("System voice", "Your phone's built-in speech engine", !vm.useKokoro && !vm.usePiper, vm::useSystemVoice)
                HorizontalDivider()
                Text("Piper (on-device, fast)", style = MaterialTheme.typography.titleSmall)
                val piperProgress = vm.piperProgress
                when {
                    piperProgress != null -> {
                        Text("${vm.downloadStatus.ifEmpty { "Downloading" }}… ${(piperProgress * 100).toInt()}%")
                        LinearProgressIndicator(progress = { piperProgress }, modifier = Modifier.fillMaxWidth())
                        TextButton(onClick = vm::cancelDownload) { Text("Pause download") }
                    }
                    !vm.piperInstalled -> {
                        Text(
                            "One-time download of about 75 MB. Keeps up with playback, so there are no pauses between sentences.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Button(onClick = { download(vm::downloadPiper) }) { Text("Download Piper") }
                    }
                    else -> {
                        LazyColumn(Modifier.heightIn(max = 200.dp).scrollIndicator(piperList, barColor), state = piperList) {
                            items(PiperModel.voices, key = { it.id }) { v ->
                                VoiceRow(v.label, null, vm.usePiper && vm.piperVoice == v) { vm.selectPiper(v) }
                            }
                        }
                        TextButton(onClick = vm::deletePiper) { Text("Delete Piper voices") }
                    }
                }
                HorizontalDivider()
                Text("Kokoro (on-device, most natural, slower)", style = MaterialTheme.typography.titleSmall)
                val progress = vm.kokoroProgress
                when {
                    progress != null -> {
                        Text("${vm.downloadStatus.ifEmpty { "Downloading" }}… ${(progress * 100).toInt()}%")
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                        TextButton(onClick = vm::cancelDownload) { Text("Pause download") }
                    }
                    !vm.kokoroInstalled -> {
                        Text(
                            "One-time download of about 350 MB. Runs fully offline afterwards, but pauses between pieces on long sentences; Piper is faster.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Button(onClick = { download(vm::downloadKokoro) }) { Text("Download Kokoro") }
                    }
                    else -> {
                        if (vm.kokoroLoading) Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.padding(end = 12.dp))
                            Text("Loading voice…")
                        }
                        LazyColumn(Modifier.heightIn(max = 320.dp).scrollIndicator(kokoroList, barColor), state = kokoroList) {
                            items(KokoroModel.voices, key = { it.id }) { v ->
                                VoiceRow(v.label, null, vm.useKokoro && vm.kokoroVoice == v) { vm.selectKokoro(v) }
                            }
                        }
                        TextButton(onClick = vm::deleteKokoro) { Text("Delete downloaded voices") }
                    }
                }
                vm.voiceError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(onClick = { vm.dismissVoiceError(); onDismiss() }) { Text("Done") } },
    )
}

@Composable
private fun VoiceRow(title: String, subtitle: String?, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column {
            Text(title)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

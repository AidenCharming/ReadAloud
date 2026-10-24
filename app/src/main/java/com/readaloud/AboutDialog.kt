package com.readaloud

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Where the source code is published; fill in once the repository exists. The GPL requires users to be told where it is. */
private const val SOURCE_URL = "https://github.com/AidenCharming/ReadAloud"

private val credits = listOf(
    "Kokoro-82M voice model" to "Apache License 2.0, by hexgrad",
    "sherpa-onnx speech runtime" to "Apache License 2.0, by the k2-fsa project",
    "ONNX Runtime" to "MIT License, by Microsoft",
    "Piper voice (LibriTTS-R, English US)" to "Trained on LibriTTS-R, licensed CC BY 4.0",
    "eSpeak NG phoneme data" to "GNU GPL v3 or later, by the eSpeak NG project; used by the voices to pronounce words",
    "Open Library" to "Book details and cover images from openlibrary.org",
    "Google ML Kit" to "Text recognition, barcode and document scanning, under Google's ML Kit terms",
    "English word list" to "20,000 common words from the google-10000-english project, used to repair scan misreads",
    "PDFBox-Android" to "Apache License 2.0",
    "jsoup" to "MIT License",
    "Apache Commons Compress" to "Apache License 2.0",
)

@Composable
fun AboutDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("ReadAloud") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text("Version ${BuildConfigVersion.NAME}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "Your books, scans and shelf stay on this phone. The only things that go online are book lookups on Open Library, " +
                        "cover images, and the one-time voice downloads.",
                    Modifier.padding(top = 10.dp, bottom = 14.dp), style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "ReadAloud is free software under the GNU General Public License v3 or later." +
                        if (SOURCE_URL.isNotEmpty()) " Source code: $SOURCE_URL" else "",
                    Modifier.padding(bottom = 14.dp), style = MaterialTheme.typography.bodyMedium,
                )
                Text("Made with", style = MaterialTheme.typography.titleSmall)
                credits.forEach { (name, license) ->
                    Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                        Text(name, style = MaterialTheme.typography.bodyMedium)
                        Text(license, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/** Mirrors versionName in app/build.gradle.kts so the About screen needs no generated BuildConfig. */
private object BuildConfigVersion { const val NAME = "0.1" }

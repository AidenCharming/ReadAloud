package com.readaloud

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.readaloud.data.Book
import com.readaloud.ui.BookCover

@Composable
fun LibraryPane(vm: AppViewModel, modifier: Modifier, onAbout: () -> Unit) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.import(uri)
    }
    val activity = LocalContext.current as Activity
    val scanner = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        GmsDocumentScanningResult.fromActivityResultIntent(result.data)
            ?.pages?.map { it.imageUri }?.takeIf { it.isNotEmpty() }?.let(vm::importScan)
    }
    fun importFile() = picker.launch(arrayOf("application/pdf", "application/epub+zip", "text/plain"))
    fun scanPages() {
        val options = GmsDocumentScannerOptions.Builder()
            .setPageLimit(30)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
        GmsDocumentScanning.getClient(options).getStartScanIntent(activity)
            .addOnSuccessListener { sender -> scanner.launch(IntentSenderRequest.Builder(sender).build()) }
            .addOnFailureListener { vm.reportError(it.message ?: "Scanner unavailable") }
    }

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm.error) {
        vm.error?.let { snackbar.showSnackbar(it); vm.dismissError() }
    }

    var query by remember { mutableStateOf("") }
    var filter by remember { mutableIntStateOf(0) } // 0 all, 1 in progress, 2 finished
    var confirmDelete by remember { mutableStateOf<Book?>(null) }

    val shown = vm.books.filter { b ->
        (query.isBlank() || b.title.contains(query, ignoreCase = true)) && when (filter) {
            1 -> b.position > 0 && b.position < b.sentenceCount - 1
            2 -> b.sentenceCount > 0 && b.position >= b.sentenceCount - 1
            else -> true
        }
    }

    confirmDelete?.let { book ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete this book?") },
            text = { Text("\"${book.title}\" and your place in it will be removed from this phone. The original file is not touched.") },
            confirmButton = { TextButton(onClick = { vm.delete(book); confirmDelete = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (vm.books.isNotEmpty()) Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SmallFloatingActionButton(onClick = ::scanPages) { Icon(Icons.Filled.DocumentScanner, contentDescription = "Scan pages") }
                ExtendedFloatingActionButton(
                    onClick = ::importFile,
                    icon = { Icon(Icons.Outlined.UploadFile, contentDescription = null) },
                    text = { Text("Import") },
                )
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            if (vm.books.isEmpty() && !vm.importing) {
                EmptyLibrary(onImport = ::importFile, onScan = ::scanPages)
            } else LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 120.dp)) {
                item {
                    Row(Modifier.fillMaxWidth().padding(top = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Library", style = MaterialTheme.typography.headlineLarge)
                            Text(
                                "${vm.books.size} ${if (vm.books.size == 1) "book" else "books"} to listen to",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = onAbout) { Icon(Icons.Filled.Info, contentDescription = "About and licences") }
                    }
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("Search your library") },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        singleLine = true,
                        shape = MaterialTheme.shapes.extraLarge,
                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    )
                    Row(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("All", "In progress", "Finished").forEachIndexed { i, label ->
                            FilterChip(selected = filter == i, onClick = { filter = i }, label = { Text(label) })
                        }
                    }
                }
                if (shown.isEmpty() && !vm.importing) item {
                    Text(
                        if (query.isNotBlank()) "Nothing matches \"$query\"" else "Nothing here yet",
                        Modifier.fillMaxWidth().padding(vertical = 32.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                items(shown, key = { it.id }) { book ->
                    BookCard(book, selected = vm.current?.id == book.id, onOpen = { vm.open(book) }, onDelete = { confirmDelete = book })
                }
            }
            if (vm.importing) {
                Surface(
                    Modifier.align(Alignment.Center), shape = MaterialTheme.shapes.large,
                    tonalElevation = 6.dp, shadowElevation = 6.dp,
                ) {
                    Column(Modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        androidx.compose.material3.CircularProgressIndicator()
                        Text("Preparing your book…", Modifier.padding(top = 16.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyLibrary(onImport: () -> Unit, onScan: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Filled.MenuBook, contentDescription = null,
            tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(72.dp).alpha(0.85f),
        )
        Spacer(Modifier.height(20.dp))
        Text("Your library is empty", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Import a PDF, EPUB or text file, or scan printed pages, and ReadAloud will read them to you.",
            Modifier.padding(top = 8.dp, bottom = 24.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(onClick = onImport) {
            Icon(Icons.Outlined.UploadFile, contentDescription = null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Import a book")
        }
        FilledTonalButton(onClick = onScan, modifier = Modifier.padding(top = 8.dp)) {
            Icon(Icons.Filled.DocumentScanner, contentDescription = null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Scan printed pages")
        }
    }
}

@Composable
private fun BookCard(book: Book, selected: Boolean, onOpen: () -> Unit, onDelete: () -> Unit) {
    val progress = book.position.toFloat() / book.sentenceCount.coerceAtLeast(1)
    val percent = (progress * 100).toInt()
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp).clip(MaterialTheme.shapes.medium).clickable(onClick = onOpen),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            BookCover(book.title, "", Modifier.width(60.dp).height(90.dp))
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text(book.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    "${book.ext.uppercase()} · " + when {
                        percent >= 99 -> "Finished"
                        percent == 0 -> "Not started"
                        else -> "$percent% listened"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp, bottom = 10.dp),
                )
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().height(5.dp).clip(MaterialTheme.shapes.extraSmall),
                    trackColor = MaterialTheme.colorScheme.outlineVariant,
                )
            }
            IconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, contentDescription = "Delete ${book.title}") }
        }
    }
}

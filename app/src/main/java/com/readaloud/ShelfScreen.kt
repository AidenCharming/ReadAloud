package com.readaloud

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.readaloud.data.BookHit
import com.readaloud.data.Loan
import com.readaloud.data.Shelf
import com.readaloud.data.ShelfBook
import com.readaloud.data.ShelfStatus
import com.readaloud.ui.BookCover
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.TimeUnit

private enum class ShelfFilter(val label: String) { ALL("All"), WANT("Want to read"), READING("Reading"), READ("Read"), LENT("Lent out") }
private enum class ShelfSort(val label: String) { RECENT("Recently added"), TITLE("Title"), AUTHOR("Author"), RATING("Rating") }

private fun date(ms: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(ms))
private fun coverOf(b: ShelfBook) = b.coverUrl.ifBlank { if (b.isbn.isNotBlank()) Shelf.coverForIsbn(b.isbn) else "" }

private fun loanText(l: Loan, now: Long = System.currentTimeMillis()): String = when {
    !l.isOut -> "Returned ${date(l.returnedAt)}"
    l.dueAt == 0L -> "Lent ${date(l.lentAt)}"
    l.isOverdue(now) -> "Overdue by ${TimeUnit.MILLISECONDS.toDays(now - l.dueAt).coerceAtLeast(1)} days"
    else -> "Due ${date(l.dueAt)}"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShelfPane(vm: AppViewModel, modifier: Modifier) {
    val ctx = LocalContext.current
    val activity = ctx as Activity
    val snackbar = remember { SnackbarHostState() }
    var filter by remember { mutableStateOf(ShelfFilter.ALL) }
    var sort by remember { mutableStateOf(ShelfSort.RECENT) }
    var query by remember { mutableStateOf("") }
    var grid by remember { mutableStateOf(true) }
    var fabOpen by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var sortOpen by remember { mutableStateOf(false) }
    var detailId by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf<ShelfBook?>(null) }
    var lendId by remember { mutableStateOf<String?>(null) }
    var confirmRemove by remember { mutableStateOf<ShelfBook?>(null) }

    LaunchedEffect(vm.shelfMessage) {
        vm.shelfMessage?.let { snackbar.showSnackbar(it); vm.dismissShelfMessage() }
    }
    LaunchedEffect(vm.removedBook) {
        vm.removedBook?.let {
            val r = snackbar.showSnackbar("Removed ${it.title}", actionLabel = "Undo", duration = SnackbarDuration.Long)
            if (r == SnackbarResult.ActionPerformed) vm.undoRemove() else vm.clearRemoved()
        }
    }

    fun scanIsbn() {
        fabOpen = false
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8, Barcode.FORMAT_UPC_A)
            .enableAutoZoom()
            .build()
        GmsBarcodeScanning.getClient(ctx, options).startScan()
            .addOnSuccessListener { code -> code.rawValue?.let(vm::addByIsbn) }
            .addOnFailureListener { e ->
                // Cancelling the scanner is not an error worth reporting
                if (e.message?.contains("cancel", ignoreCase = true) != true) vm.showShelfMessage("Barcode scanner unavailable. Try scanning the cover instead.")
            }
    }

    val coverLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        GmsDocumentScanningResult.fromActivityResultIntent(result.data)?.pages?.firstOrNull()?.imageUri?.let(vm::scanCover)
    }
    fun scanCover() {
        fabOpen = false
        val options = GmsDocumentScannerOptions.Builder()
            .setPageLimit(1)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_BASE)
            .build()
        GmsDocumentScanning.getClient(options).getStartScanIntent(activity)
            .addOnSuccessListener { sender -> coverLauncher.launch(IntentSenderRequest.Builder(sender).build()) }
            .addOnFailureListener { vm.showShelfMessage(it.message ?: "Cover scanner unavailable") }
    }

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) vm.exportShelf(uri)
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importShelf(uri)
    }

    val matching = vm.shelf.filter {
        query.isBlank() || it.title.contains(query, ignoreCase = true) || it.author.contains(query, ignoreCase = true)
    }
    val shown = matching.filter {
        when (filter) {
            ShelfFilter.ALL -> true
            ShelfFilter.WANT -> it.status == ShelfStatus.WANT
            ShelfFilter.READING -> it.status == ShelfStatus.READING
            ShelfFilter.READ -> it.status == ShelfStatus.READ
            ShelfFilter.LENT -> it.currentLoan != null
        }
    }.let { list ->
        when (sort) {
            ShelfSort.RECENT -> list.sortedByDescending { it.addedAt }
            ShelfSort.TITLE -> list.sortedBy { it.title.lowercase() }
            ShelfSort.AUTHOR -> list.sortedBy { it.author.lowercase() }
            ShelfSort.RATING -> list.sortedByDescending { it.rating }
        }
    }
    val lentCount = vm.shelf.count { it.currentLoan != null }
    val overdue = vm.shelf.count { it.currentLoan?.isOverdue() == true }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AnimatedVisibility(fabOpen) {
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        ExtendedFloatingActionButton(
                            onClick = { fabOpen = false; draft = ShelfBook(title = "") },
                            icon = { Icon(Icons.Filled.Edit, contentDescription = null) }, text = { Text("Type it in") },
                        )
                        ExtendedFloatingActionButton(
                            onClick = ::scanCover,
                            icon = { Icon(Icons.Filled.PhotoCamera, contentDescription = null) }, text = { Text("Scan the cover") },
                        )
                        ExtendedFloatingActionButton(
                            onClick = ::scanIsbn,
                            icon = { Icon(Icons.Filled.QrCodeScanner, contentDescription = null) }, text = { Text("Scan the barcode") },
                        )
                    }
                }
                FloatingActionButton(onClick = { fabOpen = !fabOpen }) {
                    Icon(if (fabOpen) Icons.Filled.Close else Icons.Filled.Add, contentDescription = if (fabOpen) "Close" else "Add a book")
                }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Bookshelf", style = MaterialTheme.typography.headlineLarge)
                    Text(
                        buildString {
                            append("${vm.shelf.size} ${if (vm.shelf.size == 1) "book" else "books"}")
                            if (lentCount > 0) append(" · $lentCount lent out")
                            if (overdue > 0) append(" · $overdue overdue")
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (overdue > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { grid = !grid }) {
                    if (grid) Icon(Icons.AutoMirrored.Filled.ViewList, contentDescription = "Show as list")
                    else Icon(Icons.Filled.GridView, contentDescription = "Show as grid")
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text("Back up shelf to a file") }, onClick = { menuOpen = false; exporter.launch("readaloud-shelf.json") })
                        DropdownMenuItem(text = { Text("Restore from a backup") }, onClick = { menuOpen = false; importer.launch(arrayOf("application/json", "text/plain", "*/*")) })
                    }
                }
            }
            Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query, onValueChange = { query = it },
                    placeholder = { Text("Search title or author") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    singleLine = true, shape = MaterialTheme.shapes.extraLarge,
                    modifier = Modifier.weight(1f),
                )
                Box(Modifier.padding(start = 8.dp)) {
                    FilledTonalButton(onClick = { sortOpen = true }) { Text(sort.label, maxLines = 1) }
                    DropdownMenu(expanded = sortOpen, onDismissRequest = { sortOpen = false }) {
                        ShelfSort.entries.forEach { s ->
                            DropdownMenuItem(text = { Text(s.label) }, onClick = { sort = s; sortOpen = false })
                        }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().hScroll().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ShelfFilter.entries.forEach { f ->
                    val count = when (f) {
                        ShelfFilter.ALL -> vm.shelf.size
                        ShelfFilter.WANT -> vm.shelf.count { it.status == ShelfStatus.WANT }
                        ShelfFilter.READING -> vm.shelf.count { it.status == ShelfStatus.READING }
                        ShelfFilter.READ -> vm.shelf.count { it.status == ShelfStatus.READ }
                        ShelfFilter.LENT -> lentCount
                    }
                    FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text("${f.label} $count") })
                }
            }
            Box(Modifier.fillMaxSize()) {
                when {
                    vm.shelf.isEmpty() -> EmptyShelf(::scanIsbn, ::scanCover)
                    shown.isEmpty() -> Text(
                        if (filter == ShelfFilter.LENT) "Nothing is lent out right now" else "Nothing matches",
                        Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    filter == ShelfFilter.LENT -> LentList(shown, onOpen = { detailId = it.id }, onReturned = vm::markReturned)
                    grid -> LazyVerticalGrid(
                        columns = GridCells.Adaptive(112.dp),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 150.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        items(shown, key = { it.id }) { ShelfTile(it) { detailId = it.id } }
                    }
                    else -> LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 150.dp)) {
                        items(shown, key = { it.id }) { ShelfRow(it) { detailId = it.id } }
                    }
                }
            }
        }
    }

    // Details of a book already on the shelf
    detailId?.let { id ->
        val book = vm.shelf.firstOrNull { it.id == id }
        if (book == null) detailId = null
        else BookSheet(
            vm, book, isNew = false,
            onSave = { vm.updateShelf(it); detailId = null },
            onRemove = { confirmRemove = book },
            onLend = { lendId = book.id },
            onDismiss = { detailId = null },
        )
    }
    // A book typed in by hand
    draft?.let { fresh ->
        BookSheet(
            vm, fresh, isNew = true,
            onSave = { vm.addToShelf(it); draft = null },
            onRemove = {}, onLend = {}, onDismiss = { draft = null },
        )
    }
    lendId?.let { id ->
        vm.shelf.firstOrNull { it.id == id }?.let { book ->
            LendDialog(vm.friends, book.title, onLend = { friend, due -> vm.lend(book, friend, due); lendId = null }, onDismiss = { lendId = null })
        } ?: run { lendId = null }
    }
    confirmRemove?.let { book ->
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { Text("Remove from your shelf?") },
            text = {
                Text(
                    if (book.currentLoan != null) "\"${book.title}\" is currently lent to ${book.currentLoan!!.friend}. Removing it also removes its lending history. You can undo straight afterwards."
                    else "\"${book.title}\" will be removed. You can undo straight afterwards."
                )
            },
            confirmButton = { TextButton(onClick = { vm.removeFromShelf(book); confirmRemove = null; detailId = null }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { confirmRemove = null }) { Text("Keep") } },
        )
    }
    vm.coverHits?.let { hits -> CoverMatchDialog(vm, hits) }
}

/** A row that scrolls sideways when its chips do not fit. */
@Composable
private fun Modifier.hScroll(): Modifier = this.horizontalScroll(rememberScrollState())

@Composable
private fun EmptyShelf(onBarcode: () -> Unit, onCover: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Filled.QrCodeScanner, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(64.dp))
        Spacer(Modifier.height(16.dp))
        Text("Start your shelf", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Scan a barcode, or photograph a cover if the barcode won't scan. Then you can track what you read and who borrowed what.",
            Modifier.padding(top = 8.dp, bottom = 20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(onClick = onBarcode) { Text("Scan a barcode") }
        FilledTonalButton(onClick = onCover, modifier = Modifier.padding(top = 8.dp)) { Text("Scan a cover") }
    }
}

@Composable
private fun LoanBadge(loan: Loan, modifier: Modifier = Modifier) {
    val over = loan.isOverdue()
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary,
        contentColor = if (over) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onSecondary,
    ) {
        Text(
            if (over) "Overdue · ${loan.friend}" else "With ${loan.friend}",
            Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ShelfTile(book: ShelfBook, onClick: () -> Unit) {
    Column(Modifier.clip(MaterialTheme.shapes.small).clickable(onClick = onClick)) {
        Box {
            BookCover(book.title, coverOf(book), Modifier.fillMaxWidth().aspectRatio(2f / 3f))
            book.currentLoan?.let { LoanBadge(it, Modifier.align(Alignment.BottomStart).padding(6.dp)) }
        }
        Text(book.title, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (book.author.isNotBlank()) Text(
            book.author, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ShelfRow(book: ShelfBook, onClick: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp).clip(MaterialTheme.shapes.medium).clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            BookCover(book.title, coverOf(book), Modifier.width(52.dp).height(78.dp))
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text(book.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (book.author.isNotBlank()) Text(book.author, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(book.status.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 2.dp))
                book.currentLoan?.let { LoanBadge(it, Modifier.padding(top = 4.dp)) }
            }
            if (book.rating > 0) Text("${book.rating} ★", color = MaterialTheme.colorScheme.secondary)
        }
    }
}

/** Books that are out, grouped by who has them, with the one-tap Returned button. */
@Composable
private fun LentList(books: List<ShelfBook>, onOpen: (ShelfBook) -> Unit, onReturned: (ShelfBook) -> Unit) {
    val groups = books.groupBy { it.currentLoan!!.friend }
    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 150.dp)) {
        groups.forEach { (friend, list) ->
            item(key = "h-$friend") {
                Text(
                    "$friend · ${list.size} ${if (list.size == 1) "book" else "books"}",
                    style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 14.dp, bottom = 4.dp),
                )
            }
            items(list.sortedBy { it.currentLoan!!.dueAt.takeIf { d -> d != 0L } ?: Long.MAX_VALUE }, key = { it.id }) { book ->
                val loan = book.currentLoan!!
                Surface(
                    shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(MaterialTheme.shapes.medium).clickable { onOpen(book) },
                ) {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        BookCover(book.title, coverOf(book), Modifier.width(44.dp).height(66.dp))
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(book.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(
                                loanText(loan), style = MaterialTheme.typography.bodySmall,
                                color = if (loan.isOverdue()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        OutlinedButton(onClick = { onReturned(book) }) { Text("Returned") }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BookSheet(
    vm: AppViewModel,
    book: ShelfBook,
    isNew: Boolean,
    onSave: (ShelfBook) -> Unit,
    onRemove: () -> Unit,
    onLend: () -> Unit,
    onDismiss: () -> Unit,
) {
    var title by remember(book.id) { mutableStateOf(book.title) }
    var author by remember(book.id) { mutableStateOf(book.author) }
    var status by remember(book.id) { mutableStateOf(book.status) }
    var rating by remember(book.id) { mutableStateOf(book.rating) }
    var notes by remember(book.id) { mutableStateOf(book.notes) }
    val scope = rememberCoroutineScope()
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Always merge edits into the latest copy of the book, so lending changes made while the sheet is open are kept
    fun edited(): ShelfBook {
        val latest = if (isNew) book else vm.shelf.firstOrNull { it.id == book.id } ?: book
        return latest.copy(title = title.trim(), author = author.trim(), status = status, rating = rating, notes = notes)
    }
    fun saveAndClose() {
        if (title.isBlank()) { onDismiss(); return }
        scope.launch { sheet.hide() }.invokeOnCompletion { onSave(edited()) }
    }

    ModalBottomSheet(onDismissRequest = { if (title.isNotBlank()) onSave(edited()) else onDismiss() }, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                BookCover(title.ifBlank { "?" }, coverOf(book), Modifier.width(88.dp).height(132.dp))
                Column(Modifier.weight(1f).padding(start = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(title, { title = it }, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(author, { author = it }, label = { Text("Author") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            }
            Row(Modifier.padding(top = 14.dp).fillMaxWidth().hScroll(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ShelfStatus.entries.forEach { s -> FilterChip(selected = status == s, onClick = { status = s }, label = { Text(s.label) }) }
            }
            Row(Modifier.padding(top = 4.dp)) {
                (1..5).forEach { star ->
                    IconButton(onClick = { rating = if (rating == star) 0 else star }) {
                        Icon(
                            if (star <= rating) Icons.Filled.Star else Icons.Filled.StarBorder,
                            contentDescription = "$star stars", tint = MaterialTheme.colorScheme.secondary,
                        )
                    }
                }
            }
            OutlinedTextField(notes, { notes = it }, label = { Text("Notes") }, minLines = 2, modifier = Modifier.fillMaxWidth())

            if (!isNew) {
                val live = vm.shelf.firstOrNull { it.id == book.id } ?: book
                Text("Lending", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
                val loan = live.currentLoan
                if (loan != null) {
                    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.secondaryContainer) {
                        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.SwapHoriz, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                Text("With ${loan.friend}", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                                Text(
                                    "Since ${date(loan.lentAt)} · ${loanText(loan)}", style = MaterialTheme.typography.bodySmall,
                                    color = if (loan.isOverdue()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                            }
                            Button(onClick = { vm.markReturned(live) }) { Text("Returned") }
                        }
                    }
                } else {
                    FilledTonalButton(onClick = onLend) {
                        Icon(Icons.Filled.SwapHoriz, contentDescription = null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Lend to a friend")
                    }
                }
                val history = live.loans.filter { !it.isOut }.sortedByDescending { it.lentAt }
                if (history.isNotEmpty()) {
                    Text("History", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    history.take(8).forEach {
                        Text("${it.friend} · ${date(it.lentAt)} to ${date(it.returnedAt)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            Row(Modifier.fillMaxWidth().padding(top = 22.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                if (!isNew) TextButton(onClick = onRemove) { Text("Remove", color = MaterialTheme.colorScheme.error) } else Spacer(Modifier.width(1.dp))
                Button(onClick = ::saveAndClose, enabled = title.isNotBlank()) { Text(if (isNew) "Add to shelf" else "Done") }
            }
        }
    }
}

@Composable
private fun LendDialog(friends: List<String>, title: String, onLend: (String, Long) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var days by remember { mutableStateOf(14) } // 0 = no due date
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Lend \"$title\"") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Who is borrowing it?") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (friends.isNotEmpty()) Row(Modifier.hScroll(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    friends.take(8).forEach { f -> FilterChip(selected = name == f, onClick = { name = f }, label = { Text(f) }) }
                }
                Text("Bring it back by", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.hScroll(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(7 to "1 week", 14 to "2 weeks", 30 to "1 month", 0 to "No date").forEach { (d, label) ->
                        FilterChip(selected = days == d, onClick = { days = d }, label = { Text(label) })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = { onLend(name, if (days == 0) 0L else System.currentTimeMillis() + TimeUnit.DAYS.toMillis(days.toLong())) },
            ) { Text("Lend") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** The cover scanner's results: what was read off the cover, editable, and the matching books from Open Library. */
@Composable
private fun CoverMatchDialog(vm: AppViewModel, hits: List<BookHit>) {
    AlertDialog(
        onDismissRequest = vm::closeCoverSearch,
        title = { Text("Find this book") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = vm.coverQuery, onValueChange = vm::updateCoverQuery,
                    label = { Text("Title and author") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    trailingIcon = { IconButton(onClick = { vm.searchCover(vm.coverQuery) }) { Icon(Icons.Filled.Search, contentDescription = "Search") } },
                )
                if (vm.coverBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                vm.coverMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(hits) { hit ->
                        Row(
                            Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable { vm.pickCover(hit) }.padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            BookCover(hit.title, hit.coverUrl, Modifier.width(40.dp).height(60.dp))
                            Column(Modifier.padding(start = 12.dp)) {
                                Text(hit.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(
                                    listOf(hit.author, hit.year?.toString()).filter { !it.isNullOrBlank() }.joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = vm::closeCoverSearch) { Text("Cancel") } },
    )
}

package com.readaloud

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.readaloud.data.Book
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.readaloud.data.Library
import com.readaloud.data.OcrText
import com.readaloud.data.SpellFix
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.readaloud.data.BookHit
import com.readaloud.data.Loan
import com.readaloud.data.LoanReminders
import com.readaloud.data.Shelf
import com.readaloud.data.ShelfBook
import com.readaloud.tts.DownloadCancelled
import com.readaloud.tts.KokoroModel
import com.readaloud.tts.KokoroSpeaker
import com.readaloud.tts.KokoroVoice
import com.readaloud.tts.Narrator
import com.readaloud.tts.PiperModel
import com.readaloud.tts.PiperVoice
import com.readaloud.tts.PlaybackService
import android.net.ConnectivityManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val library = Library(app)

    val books = mutableStateListOf<Book>()
    var current by mutableStateOf<Book?>(null); private set
    var sentences by mutableStateOf<List<String>>(emptyList()); private set
    var importing by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set

    private val shelfStore = Shelf(app)
    val shelf = mutableStateListOf<ShelfBook>()
    var shelfMessage by mutableStateOf<String?>(null); private set

    val narrator =Narrator(app) { pos -> current?.let { savePosition(it.id, pos) } }

    private val prefs = app.getSharedPreferences("voice", Application.MODE_PRIVATE)
    private var kokoro: KokoroSpeaker? = null

    var useKokoro by mutableStateOf(false); private set
    var kokoroVoice by mutableStateOf(
        KokoroModel.voices.firstOrNull { it.id == prefs.getInt("kokoroVoice", -1) } ?: KokoroModel.defaultVoice
    ); private set
    var kokoroInstalled by mutableStateOf(KokoroModel.installed(app)); private set
    /** 0..1 while the model downloads, null otherwise. */
    var kokoroProgress by mutableStateOf<Float?>(null); private set
    var kokoroLoading by mutableStateOf(false); private set
    var voiceError by mutableStateOf<String?>(null); private set

    var usePiper by mutableStateOf(false); private set
    var piperVoice by mutableStateOf(
        PiperModel.voices.firstOrNull { it.id == prefs.getInt("piperVoice", -1) } ?: PiperModel.defaultVoice
    ); private set
    var piperInstalled by mutableStateOf(PiperModel.installed(app)); private set
    var piperProgress by mutableStateOf<Float?>(null); private set
    private var piper: KokoroSpeaker? = null

    init {
        // Older builds stored a boolean "useKokoro"; fall back to it when no engine was saved yet
        val saved = prefs.getString("engine", null) ?: if (prefs.getBoolean("useKokoro", false)) "kokoro" else "system"
        if (saved == "piper" && piperInstalled) selectPiper(preview = false)
        else if (saved == "kokoro" && kokoroInstalled) selectKokoro(preview = false)
        refresh()
        shelf.addAll(shelfStore.load())
        PlaybackService.narrator = narrator
        narrator.onStateChanged = { PlaybackService.sync(app) }
    }

    private fun refresh() {
        books.clear()
        books.addAll(library.load())
    }

    fun import(uri: Uri) = viewModelScope.launch {
        importing = true
        error = null
        try {
            val book = withContext(Dispatchers.IO) { library.import(uri) }
            refresh()
            open(book)
        } catch (e: Exception) {
            error = e.message ?: "Could not read that file"
        } finally {
            importing = false
        }
    }

    /** OCRs scanned page images on-device (ML Kit) and imports the combined text as a new book. */
    fun importScan(pages: List<Uri>) = viewModelScope.launch {
        importing = true
        error = null
        try {
            val book = withContext(Dispatchers.IO) {
                val app = getApplication<Application>()
                SpellFix.load(app)
                val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                val text = pages.joinToString("\n\n") { uri ->
                    OcrText.clean(Tasks.await(recognizer.process(InputImage.fromFilePath(app, uri))))
                }
                val stamp = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date())
                library.importText("Scan $stamp", "scan", text)
            }
            refresh()
            open(book)
        } catch (e: Exception) {
            error = e.message ?: "Could not read the scanned pages"
        } finally {
            importing = false
        }
    }

    fun open(book: Book) = viewModelScope.launch {
        val fresh = books.firstOrNull { it.id == book.id } ?: book
        val text = withContext(Dispatchers.IO) { library.sentences(fresh) }
        current = fresh
        PlaybackService.title = fresh.title
        sentences = text
        narrator.load(text, fresh.position)
    }

    fun close() {
        narrator.stop()
        PlaybackService.quit(getApplication())
        current = null
        sentences = emptyList()
    }

    fun delete(book: Book) {
        if (current?.id == book.id) close()
        library.delete(book.id)
        refresh()
    }

    fun dismissError() { error = null }

    fun dismissVoiceError() { voiceError = null }

    /** What the running download is doing: "Downloading", "Resuming download", "Checking download" or "Unpacking". */
    var downloadStatus by mutableStateOf(""); private set
    private var downloadJob: Job? = null

    /** True on mobile data or another metered connection, where a 350 MB download deserves a warning. */
    fun onMeteredNetwork(): Boolean =
        getApplication<Application>().getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered == true

    fun cancelDownload() { downloadJob?.cancel() }

    private fun startDownload(
        setProgress: (Float?) -> Unit,
        install: (isActive: () -> Boolean, onStatus: (String) -> Unit, onProgress: (Float) -> Unit) -> Unit,
        onDone: () -> Unit,
    ) {
        if (downloadJob?.isActive == true) return
        voiceError = null
        setProgress(0f)
        downloadJob = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val job = coroutineContext[Job]!!
                    install({ job.isActive }, { downloadStatus = it }, { setProgress(it) })
                }
                setProgress(null)
                onDone()
            } catch (e: Exception) {
                setProgress(null)
                voiceError = if (e is CancellationException || e is DownloadCancelled) "Download paused. Tap Download to carry on where it stopped."
                else "Download failed: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    fun downloadKokoro() = startDownload(
        { kokoroProgress = it },
        { active, status, progress -> KokoroModel.install(getApplication(), active, status, progress) },
    ) { kokoroInstalled = true; selectKokoro() }

    fun downloadPiper() = startDownload(
        { piperProgress = it },
        { active, status, progress -> PiperModel.install(getApplication(), active, status, progress) },
    ) { piperInstalled = true; selectPiper() }

    /** Loads Piper if needed (a second or two) and makes it the active voice. */
    fun selectPiper(voice: PiperVoice = piperVoice, preview: Boolean = true) = viewModelScope.launch {
        piperVoice = voice
        prefs.edit().putString("engine", "piper").putInt("piperVoice", voice.id).apply()
        try {
            val engine = piper ?: run {
                kokoroLoading = true
                withContext(Dispatchers.IO) { PiperModel.load(getApplication()) }.also { piper = it }
            }
            kokoro = null // Narrator.useVoice releases the previous engine
            narrator.useVoice(engine, voice.id)
            usePiper = true
            useKokoro = false
            if (preview && !narrator.playing) engine.speak("Hi, this is ${voice.label}. This is how I sound.", voice.id, 1f) {}
        } catch (e: Throwable) {
            useSystemVoice()
            voiceError = "Could not start Piper: ${e.message ?: e.javaClass.simpleName}"
        } finally {
            kokoroLoading = false
        }
    }

    fun deletePiper() {
        useSystemVoice()
        PiperModel.delete(getApplication())
        piperInstalled = false
    }

    /** Loads Kokoro if needed (a few seconds) and makes it the active voice. */
    fun selectKokoro(voice: KokoroVoice = kokoroVoice, preview: Boolean = true) = viewModelScope.launch {
        kokoroVoice = voice
        prefs.edit().putString("engine", "kokoro").putInt("kokoroVoice", voice.id).apply()
        try {
            val engine = kokoro ?: run {
                kokoroLoading = true
                withContext(Dispatchers.IO) { KokoroModel.load(getApplication()) }.also { kokoro = it }
            }
            piper = null // Narrator.useVoice releases the previous engine
            narrator.useVoice(engine, voice.id)
            usePiper = false
            useKokoro = true
            // While reading, useVoice already restarts the sentence in the new voice; otherwise play a short sample
            if (preview && !narrator.playing) engine.speak("Hi, I'm ${voice.name.substringAfter('_').replaceFirstChar { it.uppercase() }}. This is how I sound.", voice.id, 1f) {}
        } catch (e: Throwable) {
            useSystemVoice()
            voiceError = "Could not start Kokoro: ${e.message ?: e.javaClass.simpleName}"
        } finally {
            kokoroLoading = false
        }
    }

    fun useSystemVoice() {
        narrator.useVoice(null)
        kokoro = null
        piper = null
        useKokoro = false
        usePiper = false
        prefs.edit().putString("engine", "system").apply()
    }

    fun deleteKokoro() {
        useSystemVoice()
        KokoroModel.delete(getApplication())
        kokoroInstalled = false
    }

    fun reportError(message: String) { error = message }

    /** Saves the shelf and keeps the due-date alarms in step with it. */
    private fun persistShelf() {
        val books = shelf.toList()
        viewModelScope.launch(Dispatchers.IO) {
            shelfStore.save(books)
            LoanReminders.reschedule(getApplication(), books)
        }
    }

    fun dismissShelfMessage() { shelfMessage = null }

    fun showShelfMessage(message: String) { shelfMessage = message }

    fun addToShelf(book: ShelfBook) {
        shelf.add(0, book)
        persistShelf()
    }

    fun updateShelf(book: ShelfBook) {
        val i = shelf.indexOfFirst { it.id == book.id }
        if (i >= 0) shelf[i] = book
        persistShelf()
    }

    /** The book removed most recently, kept so the snackbar can offer Undo. */
    var removedBook by mutableStateOf<ShelfBook?>(null); private set
    private var removedIndex = 0

    fun removeFromShelf(book: ShelfBook) {
        removedIndex = shelf.indexOfFirst { it.id == book.id }.coerceAtLeast(0)
        shelf.removeAll { it.id == book.id }
        persistShelf()
        removedBook = book
    }

    fun undoRemove() {
        val book = removedBook ?: return
        shelf.add(removedIndex.coerceIn(0, shelf.size), book)
        persistShelf()
        removedBook = null
    }

    fun clearRemoved() { removedBook = null }

    /** Everyone the user has lent to before, most recent first, for one-tap suggestions. */
    val friends: List<String>
        get() = shelf.flatMap { it.loans }.sortedByDescending { it.lentAt }.map { it.friend }.distinct()

    fun lend(book: ShelfBook, friend: String, dueAt: Long) {
        if (friend.isBlank() || book.currentLoan != null) return
        updateShelf(book.copy(loans = book.loans + Loan(friend = friend.trim(), dueAt = dueAt)))
    }

    fun markReturned(book: ShelfBook) {
        val now = System.currentTimeMillis()
        updateShelf(book.copy(loans = book.loans.map { if (it.isOut) it.copy(returnedAt = now) else it }))
    }

    // Cover scanner: the user photographs the front of a book, we read the big text and search Open Library for it
    /** Null while the cover-match dialog is closed; a list (possibly empty) while it is open. */
    var coverHits by mutableStateOf<List<BookHit>?>(null); private set
    var coverQuery by mutableStateOf(""); private set
    var coverBusy by mutableStateOf(false); private set
    var coverMessage by mutableStateOf<String?>(null); private set

    fun openCoverSearch() { coverHits = emptyList(); coverQuery = ""; coverMessage = null }
    fun closeCoverSearch() { coverHits = null; coverBusy = false }
    fun updateCoverQuery(q: String) { coverQuery = q }

    fun scanCover(uri: Uri) = viewModelScope.launch {
        coverHits = emptyList()
        coverMessage = null
        coverBusy = true
        val read = try {
            withContext(Dispatchers.IO) { coverTextFrom(uri) }
        } catch (e: Exception) {
            coverMessage = "Couldn't read the cover (${e.message ?: "unknown error"}). Type the title instead."
            null
        }
        coverQuery = read?.queries?.firstOrNull() ?: ""
        if (read == null || read.queries.isEmpty()) {
            if (coverMessage == null) coverMessage = "No readable text on the cover. Type the title instead."
            coverBusy = false
        } else searchCover(coverQuery, read)
    }

    /** A typed search runs as it is; a scan also tries smaller slices of the cover text and ranks every hit by how much of the cover it matches. */
    fun searchCover(query: String, read: CoverRead? = null) = viewModelScope.launch {
        if (query.isBlank()) return@launch
        coverBusy = true
        coverMessage = null
        try {
            val hits = withContext(Dispatchers.IO) {
                val queries = (listOf(query) + (read?.queries ?: emptyList())).distinct().take(5)
                val merged = LinkedHashMap<String, BookHit>()
                for (q in queries) for (h in runCatching { Shelf.search(q) }.getOrDefault(emptyList())) {
                    merged.putIfAbsent(h.isbn.ifBlank { "${h.title}|${h.author}".lowercase() }, h)
                }
                val words = read?.words ?: emptySet()
                if (words.isEmpty()) merged.values.toList()
                else merged.values.sortedByDescending { h -> wordsOf("${h.title} ${h.author}").count { it in words } }
            }
            coverHits = hits
            if (hits.isEmpty()) coverMessage = "No matches. Try fewer words, such as just the title."
        } catch (e: Exception) {
            coverMessage = "Couldn't reach Open Library. Check your connection and try again."
        } finally {
            coverBusy = false
        }
    }

    fun pickCover(hit: BookHit) {
        if (hit.isbn.isNotBlank() && shelf.any { it.isbn == hit.isbn }) { shelfMessage = "Already on your shelf" }
        else { addToShelf(ShelfBook(title = hit.title, author = hit.author, isbn = hit.isbn, coverUrl = hit.coverUrl)); shelfMessage = "Added ${hit.title}" }
        coverHits = null
    }

    /** What was read off a cover: search phrases from the biggest text, and every word seen on it for ranking the results. */
    class CoverRead(val queries: List<String>, val words: Set<String>)

    private fun wordsOf(text: String) = Regex("[A-Za-z]{3,}").findAll(text.lowercase()).map { it.value }.toSet()

    /**
     * The title is usually the biggest text and the authors smaller, but a multi-author cover can make any line the biggest,
     * so try the biggest line, the biggest two, three and four, and each of the biggest lines alone.
     */
    private fun coverTextFrom(uri: Uri): CoverRead {
        val app = getApplication<Application>()
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val all = Tasks.await(recognizer.process(InputImage.fromFilePath(app, uri))).textBlocks.flatMap { it.lines }
            .filter { l -> l.boundingBox != null && l.text.trim().length >= 3 && l.text.count { it.isLetter() } >= l.text.length * 0.6 }
        val bySize = all.sortedByDescending { it.boundingBox!!.height() }
        fun join(n: Int) = bySize.take(n).sortedBy { it.boundingBox!!.top }.joinToString(" ") { it.text.trim() }.take(90)
        val queries = (listOf(join(4), join(2), join(1), join(3)) + bySize.take(4).map { it.text.trim() }).filter { it.isNotBlank() }.distinct()
        return CoverRead(queries, wordsOf(all.joinToString(" ") { it.text }))
    }

    fun exportShelf(uri: Uri) = viewModelScope.launch {
        val json = Shelf.encode(shelf.toList())
        val ok = withContext(Dispatchers.IO) {
            runCatching { getApplication<Application>().contentResolver.openOutputStream(uri, "wt")!!.use { it.write(json.toByteArray()) } }.isSuccess
        }
        shelfMessage = if (ok) "Exported ${shelf.size} books" else "Export failed"
    }

    /** Merges a backup into the shelf: books already here (same id, or same ISBN) are kept as they are. */
    fun importShelf(uri: Uri) = viewModelScope.launch {
        val incoming = withContext(Dispatchers.IO) {
            runCatching { Shelf.parse(getApplication<Application>().contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }) }.getOrNull()
        }
        if (incoming == null) { shelfMessage = "That file isn't a ReadAloud backup"; return@launch }
        val fresh = incoming.filter { b -> shelf.none { it.id == b.id || (b.isbn.isNotBlank() && it.isbn == b.isbn) } }
        shelf.addAll(fresh)
        persistShelf()
        shelfMessage = "Imported ${fresh.size} new books"
    }

    fun addByIsbn(isbn: String) = viewModelScope.launch {
        if (shelf.any { it.isbn == isbn }) { shelfMessage = "Already on your shelf"; return@launch }
        val found = withContext(Dispatchers.IO) { Shelf.lookup(isbn) }
        if (found == null) {
            shelfMessage = "ISBN $isbn not found. Try scanning the cover, or type it in."
        } else {
            addToShelf(found)
            shelfMessage = "Added ${found.title}"
        }
    }

    private fun savePosition(id: String, pos: Int) = viewModelScope.launch(Dispatchers.IO) {
        library.savePosition(id, pos)
        withContext(Dispatchers.Main) {
            val i = books.indexOfFirst { it.id == id }
            if (i >= 0) books[i] = books[i].copy(position = pos)
        }
    }

    override fun onCleared() {
        PlaybackService.narrator = null
        narrator.shutdown()
    }
}

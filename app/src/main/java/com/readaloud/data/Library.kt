package com.readaloud.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class Book(
    val id: String,
    val title: String,
    val ext: String,
    val sentenceCount: Int,
    val position: Int,
)

/** Books live in app storage: a copy of the source file is not needed after import, only the extracted text. */
class Library(private val context: Context) {
    private val dir = File(context.filesDir, "books").apply { mkdirs() }
    private val index = File(context.filesDir, "library.json")

    fun load(): List<Book> {
        if (!index.exists()) return emptyList()
        val arr = JSONArray(index.readText())
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Book(o.getString("id"), o.getString("title"), o.getString("ext"), o.getInt("sentences"), o.getInt("pos"))
        }
    }

    private fun save(books: List<Book>) {
        val arr = JSONArray()
        books.forEach {
            arr.put(
                JSONObject().put("id", it.id).put("title", it.title).put("ext", it.ext)
                    .put("sentences", it.sentenceCount).put("pos", it.position)
            )
        }
        index.writeText(arr.toString())
    }

    fun import(uri: Uri): Book {
        val name = displayName(uri)
        val ext = name.substringAfterLast('.', "txt").lowercase()
        val text = context.contentResolver.openInputStream(uri)!!.use { TextExtractor.extract(context, it, ext) }
        return importText(name.substringBeforeLast('.'), ext, text)
    }

    fun importText(title: String, ext: String, text: String): Book {
        val sentences = TextExtractor.sentences(text)
        require(sentences.isNotEmpty()) { "No readable text found in $title" }
        val book = Book(UUID.randomUUID().toString(), title, ext, sentences.size, 0)
        File(dir, "${book.id}.txt").writeText(sentences.joinToString("\n"))
        save(load() + book)
        return book
    }

    fun sentences(book: Book): List<String> = File(dir, "${book.id}.txt").readLines()

    fun savePosition(id: String, position: Int) =
        save(load().map { if (it.id == id) it.copy(position = position) else it })

    fun delete(id: String) {
        File(dir, "$id.txt").delete()
        save(load().filterNot { it.id == id })
    }

    private fun displayName(uri: Uri): String =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "Untitled.txt"
}

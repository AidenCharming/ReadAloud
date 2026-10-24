package com.readaloud.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

enum class ShelfStatus(val label: String) { WANT("Want to read"), READING("Reading"), READ("Read") }

/** One time a book left the house. [dueAt] and [returnedAt] are 0 when there is no due date / it is still out. */
data class Loan(
    val id: String = UUID.randomUUID().toString(),
    val friend: String,
    val lentAt: Long = System.currentTimeMillis(),
    val dueAt: Long = 0,
    val returnedAt: Long = 0,
) {
    val isOut: Boolean get() = returnedAt == 0L
    fun isOverdue(now: Long = System.currentTimeMillis()) = isOut && dueAt != 0L && dueAt < now
}

data class ShelfBook(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val author: String = "",
    val isbn: String = "",
    val status: ShelfStatus = ShelfStatus.WANT,
    val rating: Int = 0,
    val notes: String = "",
    val addedAt: Long = System.currentTimeMillis(),
    val coverUrl: String = "",
    val loans: List<Loan> = emptyList(),
) {
    /** The open loan, if the book is currently with a friend. */
    val currentLoan: Loan? get() = loans.lastOrNull { it.isOut }
}

/** A search result from Open Library, used when a barcode is missing or unreadable. */
data class BookHit(val title: String, val author: String, val isbn: String, val coverUrl: String, val year: Int?)

/** The bookshelf is a reading log plus a lending ledger, stored locally as JSON. */
class Shelf(context: Context) {
    private val file = File(context.filesDir, "shelf.json")

    fun load(): List<ShelfBook> {
        if (!file.exists()) return emptyList()
        return parse(file.readText())
    }

    fun save(books: List<ShelfBook>) {
        // Write to a temp file first so a crash mid-write cannot leave a half-written shelf
        val tmp = File(file.parentFile, "shelf.json.tmp")
        tmp.writeText(encode(books))
        if (!tmp.renameTo(file)) { file.writeText(tmp.readText()); tmp.delete() }
    }

    companion object {
        fun parse(json: String): List<ShelfBook> {
            val arr = JSONArray(json)
            return (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val loans = o.optJSONArray("loans")?.let { l ->
                    (0 until l.length()).map { j ->
                        val x = l.getJSONObject(j)
                        Loan(
                            id = x.optString("id").ifEmpty { UUID.randomUUID().toString() },
                            friend = x.getString("friend"),
                            lentAt = x.optLong("lentAt"),
                            dueAt = x.optLong("dueAt"),
                            returnedAt = x.optLong("returnedAt"),
                        )
                    }
                }.orEmpty()
                ShelfBook(
                    id = o.getString("id"),
                    title = o.getString("title"),
                    author = o.optString("author"),
                    isbn = o.optString("isbn"),
                    status = runCatching { ShelfStatus.valueOf(o.optString("status")) }.getOrDefault(ShelfStatus.WANT),
                    rating = o.optInt("rating"),
                    notes = o.optString("notes"),
                    addedAt = o.optLong("addedAt"),
                    coverUrl = o.optString("coverUrl"),
                    loans = loans,
                )
            }
        }

        fun encode(books: List<ShelfBook>): String {
            val arr = JSONArray()
            books.forEach { b ->
                val loans = JSONArray()
                b.loans.forEach {
                    loans.put(
                        JSONObject().put("id", it.id).put("friend", it.friend).put("lentAt", it.lentAt)
                            .put("dueAt", it.dueAt).put("returnedAt", it.returnedAt)
                    )
                }
                arr.put(
                    JSONObject().put("id", b.id).put("title", b.title).put("author", b.author).put("isbn", b.isbn)
                        .put("status", b.status.name).put("rating", b.rating).put("notes", b.notes)
                        .put("addedAt", b.addedAt).put("coverUrl", b.coverUrl).put("loans", loans)
                )
            }
            return arr.toString()
        }

        private fun get(url: String): String {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("User-Agent", "ReadAloud/0.1 (Android; personal bookshelf)")
            return try { conn.inputStream.bufferedReader().use { it.readText() } } finally { conn.disconnect() }
        }

        fun coverForIsbn(isbn: String) = "https://covers.openlibrary.org/b/isbn/$isbn-M.jpg?default=false"

        /** Open Library is free and needs no key. Returns null if the ISBN isn't found or the network fails. */
        fun lookup(isbn: String): ShelfBook? = runCatching {
            val body = get("https://openlibrary.org/api/books?bibkeys=ISBN:$isbn&format=json&jscmd=data")
            val data = JSONObject(body).optJSONObject("ISBN:$isbn") ?: return@runCatching null
            val authors = data.optJSONArray("authors")
            ShelfBook(
                title = data.getString("title"),
                author = authors?.optJSONObject(0)?.optString("name").orEmpty(),
                isbn = isbn,
                coverUrl = data.optJSONObject("cover")?.optString("medium").orEmpty().ifEmpty { coverForIsbn(isbn) },
            )
        }.getOrNull()

        /** Free-text search for the cover scanner. Throws on network trouble so the caller can say so. */
        fun search(query: String): List<BookHit> {
            val q = URLEncoder.encode(query.trim(), "UTF-8")
            val body = get("https://openlibrary.org/search.json?q=$q&limit=12&fields=title,author_name,isbn,cover_i,first_publish_year")
            val docs = JSONObject(body).optJSONArray("docs") ?: return emptyList()
            return (0 until docs.length()).mapNotNull { i ->
                val d = docs.getJSONObject(i)
                val title = d.optString("title").ifEmpty { return@mapNotNull null }
                val isbns = d.optJSONArray("isbn")
                val isbn = (0 until (isbns?.length() ?: 0)).map { isbns!!.getString(it) }.firstOrNull { it.length == 13 }.orEmpty()
                val cover = d.optLong("cover_i", 0).takeIf { it > 0 }?.let { "https://covers.openlibrary.org/b/id/$it-M.jpg" }.orEmpty()
                BookHit(
                    title = title,
                    author = d.optJSONArray("author_name")?.optString(0).orEmpty(),
                    isbn = isbn,
                    coverUrl = cover,
                    year = d.optInt("first_publish_year", 0).takeIf { it > 0 },
                )
            }
        }
    }
}

package com.readaloud.data

/** A heading found in a book's text: the sentence it starts at and what it says. */
data class Chapter(val sentence: Int, val title: String)

/**
 * Books are stored as a flat list of sentences, so chapters are found by looking for headings such as "Chapter 4",
 * "Part Two" or a lone roman numeral. It is a heuristic, and finds nothing in books without such headings.
 */
object Chapters {
    private val named = Regex("""^(chapter|part|book|section|prologue|epilogue|preface|introduction|foreword|afterword)\b.{0,60}$""", RegexOption.IGNORE_CASE)
    private val numeral = Regex("""^(\d{1,3}|[IVXLC]{1,7})\.?$""")

    fun find(sentences: List<String>): List<Chapter> {
        val found = sentences.withIndex().filter { (_, s) ->
            val t = s.trim()
            t.length <= 70 && (named.matches(t) || numeral.matches(t))
        }.map { (i, s) -> Chapter(i, s.trim().trimEnd('.')) }
        // A handful of stray numerals is not a table of contents
        return if (found.size >= 3) found else emptyList()
    }
}

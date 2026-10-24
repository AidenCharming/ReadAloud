package com.readaloud.data

import com.google.mlkit.vision.text.Text
import android.util.Log
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.cos
import kotlin.math.sin

/** Turns ML Kit's raw recognition result into text that reads in page order and drops obvious junk. */
object OcrText {
    /** Words ML Kit is less sure of than this are usually show-through, ruled lines or smudges. */
    private const val MIN_CONFIDENCE = 0.45f

    private class Piece(val text: String, val x: Float, val y: Float, val height: Float)
    private class Row(var y: Float, val pieces: MutableList<Piece>)

    private val footnoteStart = Regex("""^\d{1,3}:\d{1,3}[a-z]?\b""")
    private val vowel = Regex("[aeiouyAEIOUY]")

    fun clean(result: Text): String {
        val all = result.textBlocks.flatMap { it.lines }.mapNotNull { confident(it) }
        if (all.isEmpty()) return ""
        // Footnotes are set in smaller type at the foot of the page. Drop them by size before grouping, because they sit
        // close enough to the last line of the story to be merged into its row.
        val bodyHeight = all.map { it.height }.sorted()[all.size / 2]
        val bottom = all.maxOf { it.y }
        val lines = all.filterNot { it.y > bottom * 0.7f && it.height < bodyHeight * 0.8f }.ifEmpty { all }

        // Page tilt: the median line angle. Its sign convention is easy to get wrong, so use whichever sign gives fewer rows.
        val angles = result.textBlocks.flatMap { it.lines }.map { it.angle }.sorted()
        val tilt = Math.toRadians((angles.getOrElse(angles.size / 2) { 0f }).toDouble())
        val rows = listOf(tilt, -tilt).map { group(lines, it) }.minBy { it.size }

        val pageBottom = lines.maxOf { it.y }
        val medianHeight = lines.map { it.height }.sorted()[lines.size / 2]

        val out = StringBuilder()
        var prevY: Float? = null
        for ((i, row) in rows.withIndex()) {
            var text = SpellFix.fix(row.pieces.sortedBy { it.x }.joinToString(" ") { fixLookalikes(it.text) })
            // Footnotes sit at the foot of the page and start with a chapter:verse reference; they are not part of the story
            if (row.y > pageBottom * 0.6f && footnoteStart.containsMatchIn(text)) break
            if (!wordLike(text)) continue
            if (i < 2 && isRunningHead(text)) continue
            // End a section title with a full stop so the reader treats it as its own sentence
            if (isHeading(text)) text = "$text."
            // A visible gap between rows marks a new paragraph
            if (prevY != null && row.y - prevY > medianHeight * 1.9f) out.append("\n\n")
            else if (prevY != null) out.append(' ')
            out.append(text)
            prevY = row.y
        }
        return stripVerseNumbers(out.toString())
    }

    private val smallWords = setOf("a", "an", "the", "of", "to", "in", "on", "and", "for", "at", "by", "with", "from")
    private val verseGluedToWord = Regex("""(?<![\w:.,])\d{1,3}(?=["“'‘]?\p{Lu}|["“'‘]\p{L})""")
    private val verseBeforeSentence = Regex("""(^|(?<=[.!?”"']\s))\d{1,3}\s+(?=["“'‘]?\p{Lu})""")

    /** Short Title Case rows with no digits or closing punctuation: "Jesus Casts Out a Demon". */
    private fun isHeading(text: String): Boolean {
        val words = text.split(' ')
        if (words.size !in 2..6 || text.any { it.isDigit() } || text.last() in ".!?,;:”\"'") return false
        return words.all { w -> w.first().isUpperCase() || w.lowercase() in smallWords }
    }

    /** Verse numbers glued to the next word ("20He", "34\"Go") would be read out as numbers, so drop them. */
    private fun stripVerseNumbers(text: String) = text.replace(verseGluedToWord, "").replace(verseBeforeSentence, "")

    /** Page numbers and running heads such as "LUKE 4:42 1291": no lowercase letters and only a few tokens. */
    private fun isRunningHead(text: String) = text.none { it.isLowerCase() } && text.split(' ').size <= 5

    /** Rebuilds a line from its confident words only; null when nothing trustworthy is left. */
    private fun confident(line: Text.Line): Piece? {
        val box = line.boundingBox ?: return null
        val (kept, dropped) = line.elements.partition { it.confidence >= MIN_CONFIDENCE }
        if (dropped.isNotEmpty()) Log.d("OcrText", "dropped " + dropped.joinToString { "${it.text}@${"%.2f".format(it.confidence)}" })
        val text = kept.joinToString(" ") { it.text }.trim()
        if (text.length < 3) return null
        if (Regex("""https?:|www\.|\.com\b|\.org\b|/stream/""", RegexOption.IGNORE_CASE).containsMatchIn(text)) return null
        val c = line.cornerPoints
        // Corner points run clockwise from the top-left, so p0 to p3 is the text height; the bounding box over-counts on tilted lines
        val height = if (c != null && c.size == 4) hypot((c[3].x - c[0].x).toDouble(), (c[3].y - c[0].y).toDouble()).toFloat() else box.height().toFloat()
        return Piece(text, box.exactCenterX(), box.exactCenterY(), height.coerceAtLeast(1f))
    }

    /** Rotates every line centre by [angle] to undo page tilt, then groups lines that share a baseline into rows. */
    private fun group(lines: List<Piece>, angle: Double): List<Row> {
        val c = cos(angle).toFloat()
        val s = sin(angle).toFloat()
        val flat = lines.map { Piece(it.text, it.x * c + it.y * s, -it.x * s + it.y * c, it.height) }
        val medianHeight = flat.map { it.height }.sorted()[flat.size / 2]
        val rows = mutableListOf<Row>()
        for (p in flat.sortedBy { it.y }) {
            val row = rows.lastOrNull()?.takeIf { abs(p.y - it.y) < medianHeight * 0.6f }
            if (row != null) { row.pieces += p; row.y = row.pieces.map { it.y }.average().toFloat() }
            else rows += Row(p.y, mutableListOf(p))
        }
        return rows
    }

    /** Show-through and ruled lines read as strings of letters with no vowels or odd capitals; drop rows that are mostly that. */
    private fun wordLike(text: String): Boolean {
        val words = text.split(' ').map { w -> w.filter { it.isLetter() } }.filter { it.isNotEmpty() }
        if (words.isEmpty()) return text.any { it.isDigit() } // a bare page or verse number is harmless
        val good = words.count { w ->
            (w.length <= 3 || vowel.containsMatchIn(w)) && w.drop(1).none { it.isUpperCase() } || w.all { it.isUpperCase() }
        }
        return good >= words.size * 0.6
    }

    /** A lone 1 or 0 inside an otherwise alphabetic word is nearly always an i/l or o misread. */
    private fun fixLookalikes(text: String): String = text.split(' ').joinToString(" ") { word ->
        val digits = word.count { it.isDigit() }
        val letters = word.count { it.isLetter() }
        if (digits != 1 || letters < 3) word
        else word.mapIndexed { i, c ->
            val inside = (i > 0 && word[i - 1].isLetter()) || (i < word.lastIndex && word[i + 1].isLetter())
            when {
                !inside -> c
                c == '0' -> 'o'
                c == '1' -> 'i'
                else -> c
            }
        }.joinToString("")
    }
}

package com.readaloud.data

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.jsoup.Jsoup
import java.io.InputStream
import java.text.BreakIterator
import java.util.Locale
import java.util.zip.ZipInputStream

object TextExtractor {
    fun extract(context: Context, input: InputStream, ext: String): String = when (ext) {
        "pdf" -> pdf(context, input)
        "epub" -> epub(input)
        else -> input.bufferedReader().readText()
    }

    private fun pdf(context: Context, input: InputStream): String {
        PDFBoxResourceLoader.init(context)
        PDDocument.load(input).use { return PDFTextStripper().getText(it) }
    }

    /** Reads XHTML chapters in zip order; good enough for most books (spine order matches file order in practice). */
    private fun epub(input: InputStream): String {
        val chapters = sortedMapOf<String, String>()
        ZipInputStream(input).use { zip ->
            generateSequence { zip.nextEntry }.forEach { e ->
                val n = e.name.lowercase()
                if (!e.isDirectory && (n.endsWith(".xhtml") || n.endsWith(".html") || n.endsWith(".htm"))) {
                    chapters[e.name] = Jsoup.parse(zip.readBytes().decodeToString()).body().wholeText()
                }
            }
        }
        return chapters.values.joinToString("\n\n")
    }

    /** Collapses whitespace and splits into sentences; the reader highlights and tracks one sentence at a time. */
    fun sentences(text: String): List<String> {
        val clean = text.replace(Regex("-\\s*\\n\\s*"), "").replace(Regex("\\s+"), " ").trim()
        val it = BreakIterator.getSentenceInstance(Locale.getDefault()).apply { setText(clean) }
        val out = mutableListOf<String>()
        var start = it.first()
        var end = it.next()
        while (end != BreakIterator.DONE) {
            val s = clean.substring(start, end).trim()
            if (s.length > 1) out += s
            start = end
            end = it.next()
        }
        return out
    }
}

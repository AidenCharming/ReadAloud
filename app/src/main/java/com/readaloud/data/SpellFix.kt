package com.readaloud.data

import android.content.Context

/**
 * Repairs the misreads text recognition makes most often (t read as c, m as rn, a lost f, a missing apostrophe) by checking
 * whether a one- or two-letter change turns an unknown word into a far more common one. The word list is the 20,000
 * most frequent English words, in frequency order, so a word's position is its commonness.
 */
object SpellFix {
    private var rank: Map<String, Int> = emptyMap()

    fun load(context: Context) {
        if (rank.isNotEmpty()) return
        val map = HashMap<String, Int>(40000)
        context.assets.open("words.txt").bufferedReader().useLines { lines ->
            var i = 0
            for (w in lines) { val t = w.trim(); if (t.isNotEmpty()) map.putIfAbsent(t, ++i) }
        }
        rank = map
    }

    /** What the recogniser wrote, and the letters it may have meant. */
    private val swaps = listOf("c" to listOf("t", "e", "o"), "l" to listOf("f", "t", "i"), "i" to listOf("l"), "rn" to listOf("m"), "vv" to listOf("w"))

    private val contractions = mapOf(
        "hes" to "he's", "shes" to "she's", "thats" to "that's", "whats" to "what's", "theres" to "there's",
        "dont" to "don't", "cant" to "can't", "didnt" to "didn't", "wasnt" to "wasn't", "isnt" to "isn't",
        "couldnt" to "couldn't", "wouldnt" to "wouldn't", "shouldnt" to "shouldn't", "doesnt" to "doesn't",
        "havent" to "haven't", "hasnt" to "hasn't", "youre" to "you're", "theyre" to "they're", "youll" to "you'll",
        "wont" to "won't", "werent" to "weren't", "hadnt" to "hadn't", "arent" to "aren't",
    )

    private val wordPart = Regex("""^(\W*)([A-Za-z]+)(\W*)$""")
    private val sentenceEnd = Regex("""[.!?]["')”’]*$""")

    fun fix(text: String): String {
        if (rank.isEmpty()) return text
        var first = true
        return text.split(' ').joinToString(" ") { token ->
            val out = token.split('-').joinToString("-") { part ->
                val m = wordPart.matchEntire(part) ?: return@joinToString part
                val (pre, core, post) = m.destructured
                pre + fixWord(core, first) + post
            }
            first = sentenceEnd.containsMatchIn(token)
            out
        }
    }

    private fun fixWord(core: String, sentenceStart: Boolean): String {
        val low = core.lowercase()
        val upper = core[0].isUpperCase()
        contractions[low]?.let { return if (upper) it.replaceFirstChar { c -> c.uppercase() } else it }
        if (core.length < 2) return core
        if (upper && !sentenceStart) return core // a name in the middle of a sentence
        if (upper && core.length <= 4 && core[0] != 'I') return core // short capitalised words are mostly names
        if (core.count { it.isUpperCase() } > 1) return core
        val mine = rank[low] ?: Int.MAX_VALUE
        if (mine < 1500) return core
        var best: String? = null
        fun consider(candidate: String, ok: (Int) -> Boolean) {
            val r = rank[candidate] ?: return
            if (ok(r) && (best == null || r < rank.getValue(best!!))) best = candidate
        }
        if (mine == Int.MAX_VALUE) for (j in 0..low.length) consider(low.substring(0, j) + "f" + low.substring(j)) { true } // an f that was not read at all
        if (best == null) for (c in candidates(low)) consider(c) { it.toLong() * 4 < mine }
        val fixed = best ?: return core
        return if (upper) fixed.replaceFirstChar { it.uppercase() } else fixed
    }

    /** Every word at most two misreads away from [word]. */
    private fun candidates(word: String): Set<String> {
        val out = HashSet<String>()
        fun go(s: String, start: Int, left: Int) {
            if (left == 0) return
            for ((pattern, replacements) in swaps) {
                var j = start
                while (true) {
                    j = s.indexOf(pattern, j)
                    if (j < 0) break
                    for (r in replacements) {
                        val t = s.substring(0, j) + r + s.substring(j + pattern.length)
                        out += t
                        go(t, j + r.length, left - 1)
                    }
                    j++
                }
            }
        }
        go(word, 0, 2)
        out -= word
        return out
    }
}

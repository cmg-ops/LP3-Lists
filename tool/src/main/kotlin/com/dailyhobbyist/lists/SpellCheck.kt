package com.dailyhobbyist.lists

import com.dailyhobbyist.lists.dict.ALL_DICT_CHUNKS
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Self-contained spellchecker. The dictionary ships inside the app as
 * generated Kotlin sources (about 120k English words ordered by how
 * common they are), so this needs no OS services, no network, and no
 * special permissions — it works entirely inside Light's sandbox.
 */
object SpellCheck {

    /** word -> frequency rank (lower = more common). */
    private var rank: HashMap<String, Int>? = null

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loading = false

    /** Kick off dictionary loading in the background. Safe to call repeatedly. */
    fun ensureLoaded() {
        if (loading || rank != null) return
        loading = true
        scope.launch {
            val map = HashMap<String, Int>(160_000)
            var i = 0
            for (chunk in ALL_DICT_CHUNKS) {
                for (line in chunk.lineSequence()) {
                    val w = line.trim()
                    if (w.isNotEmpty() && !map.containsKey(w)) {
                        map[w] = i
                        i++
                    }
                }
            }
            rank = map
            _ready.value = true
        }
    }

    private fun known(word: String): Boolean = rank?.containsKey(word) == true

    /**
     * True if [word] should be underlined. Conservative: short words,
     * words with digits, and anything before the dictionary finishes
     * loading are never flagged.
     */
    fun isMisspelled(word: String): Boolean {
        val r = rank ?: return false
        val w = word.lowercase()
        if (w.length < 3) return false
        if (w.any { it !in 'a'..'z' && it != '\'' }) return false
        if (r.containsKey(w)) return false
        // Try without a possessive/apostrophe tail, e.g. "sam's"
        val base = w.substringBefore('\'')
        if (base.length >= 3 && r.containsKey(base)) return false
        return true
    }

    /**
     * Up to [max] correction suggestions for [word], most likely first.
     * Candidates are all dictionary words within edit distance 1 (or 2,
     * if distance 1 finds nothing), ranked by how common they are.
     * If the original word was capitalized, suggestions are capitalized.
     */
    fun suggestions(word: String, max: Int = 3): List<String> {
        val r = rank ?: return emptyList()
        val w = word.lowercase()
        if (w.isEmpty()) return emptyList()

        val e1 = edits1(w)
        var candidates = e1.filter { r.containsKey(it) }
        if (candidates.isEmpty() && w.length <= 10) {
            // Edit distance 2, but only expand the most promising e1 set
            val e2 = HashSet<String>()
            for (e in e1) {
                if (e2.size > 60_000) break
                e2.addAll(edits1(e))
            }
            candidates = e2.filter { r.containsKey(it) }
        }

        val top = candidates
            .distinct()
            .sortedBy { r[it] ?: Int.MAX_VALUE }
            .take(max)

        val capitalized = word.firstOrNull()?.isUpperCase() == true
        return if (capitalized) {
            top.map { s -> s.replaceFirstChar { it.uppercase() } }
        } else {
            top
        }
    }

    /** All strings within one edit (delete, transpose, replace, insert). */
    private fun edits1(w: String): HashSet<String> {
        val letters = "abcdefghijklmnopqrstuvwxyz'"
        val out = HashSet<String>(w.length * 54 + 32)
        for (i in 0..w.length) {
            val left = w.substring(0, i)
            val right = w.substring(i)
            if (right.isNotEmpty()) {
                out.add(left + right.substring(1)) // delete
                if (right.length > 1) {
                    out.add(left + right[1] + right[0] + right.substring(2)) // transpose
                }
                for (c in letters) out.add(left + c + right.substring(1)) // replace
            }
            for (c in letters) out.add(left + c + right) // insert
        }
        return out
    }

    /** A word occurrence inside a larger text. */
    data class WordSpan(val start: Int, val end: Int, val word: String)

    private val wordRegex = Regex("[A-Za-z']+")

    /** All word occurrences in [text]. */
    fun words(text: String): List<WordSpan> =
        wordRegex.findAll(text).map {
            WordSpan(it.range.first, it.range.last + 1, it.value)
        }.toList()

    /** The word occurrence covering text offset [offset], if any. */
    fun wordAt(text: String, offset: Int): WordSpan? =
        words(text).firstOrNull { offset >= it.start && offset <= it.end }
}

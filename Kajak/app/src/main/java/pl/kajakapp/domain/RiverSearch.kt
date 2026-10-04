package pl.kajakapp.domain

import java.text.Normalizer

/** Wyszukiwanie rzek i odcinków: bez względu na wielkość liter i polskie znaki, po wszystkich słowach zapytania. */
object RiverSearch {
    fun normalize(text: String): String =
        Normalizer.normalize(text.lowercase().replace('ł', 'l'), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .trim()

    /** true, gdy każde słowo zapytania występuje w którymkolwiek z pól; puste zapytanie pasuje do wszystkiego. */
    fun matches(query: String, vararg fields: String): Boolean {
        val words = normalize(query).split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return true
        val haystack = fields.joinToString(" ") { normalize(it) }
        return words.all { haystack.contains(it) }
    }
}

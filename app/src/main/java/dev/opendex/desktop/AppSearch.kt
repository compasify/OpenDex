package dev.opendex.desktop

import java.text.Normalizer
import java.util.Locale

private val accents = "\\p{M}+".toRegex()

private fun String.searchForm() = Normalizer.normalize(lowercase(Locale.ROOT), Normalizer.Form.NFD)
    .replace(accents, "").replace('đ', 'd')

fun <T> searchApps(items: List<T>, query: String, label: (T) -> String): List<T> {
    val words = query.searchForm().split(Regex("\\s+")).filter(String::isNotBlank)
    if (words.isEmpty()) return items
    return items.filter { item -> label(item).searchForm().let { name -> words.all(name::contains) } }
}

package ro.fermapp.domain

/*
 * Text helpers shared by the catalog, the copilot and the reports.
 * Common Kotlin has no Unicode normalizer, so accents are mapped explicitly.
 */

private val ACCENTS: Map<Char, Char> = buildMap {
    fun map(base: Char, accented: String) = accented.forEach { put(it, base) }
    map('a', "àáâãäåāăą")
    map('A', "ÀÁÂÃÄÅĀĂĄ")
    map('c', "çćĉċč")
    map('C', "ÇĆĈĊČ")
    map('d', "ďđ")
    map('D', "ĎĐ")
    map('e', "èéêëēĕėęě")
    map('E', "ÈÉÊËĒĔĖĘĚ")
    map('g', "ĝğġģ")
    map('G', "ĜĞĠĢ")
    map('i', "ìíîïĩīĭįı")
    map('I', "ÌÍÎÏĨĪĬĮİ")
    map('l', "ĺļľŀł")
    map('L', "ĹĻĽĿŁ")
    map('n', "ñńņňŉ")
    map('N', "ÑŃŅŇ")
    map('o', "òóôõöøōŏő")
    map('O', "ÒÓÔÕÖØŌŎŐ")
    map('r', "ŕŗř")
    map('R', "ŔŖŘ")
    map('s', "śŝşšș")
    map('S', "ŚŜŞŠȘ")
    map('t', "ţťŧț")
    map('T', "ŢŤŦȚ")
    map('u', "ùúûüũūŭůűų")
    map('U', "ÙÚÛÜŨŪŬŮŰŲ")
    map('y', "ýÿŷ")
    map('Y', "ÝŸŶ")
    map('z', "źżž")
    map('Z', "ŹŻŽ")
}

/** Combining marks U+0300-U+036F, left over when text arrives already decomposed. */
private fun isCombiningMark(char: Char) = char.code in 0x0300..0x036F

/** Removes accents: "Grâu ș.a." -> "Grau s.a.". */
fun stripDiacritics(value: String): String = buildString(value.length) {
    for (char in value) {
        if (isCombiningMark(char)) continue
        append(ACCENTS[char] ?: char)
    }
}

/** Lower case, without accents and surrounding spaces, for searching. */
fun normalizedText(value: String?): String = stripDiacritics(value ?: "").lowercase().trim()

/** URL-friendly identifier: "KWS Kamparis" -> "kws-kamparis". */
fun slug(value: String): String =
    stripDiacritics(value).lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

/**
 * An approximation of String.prototype.localeCompare for names: accents and case only
 * break ties, and with [numeric] runs of digits compare by value ("6R 110" < "6R 1000").
 */
fun localeCompare(a: String, b: String, numeric: Boolean = false): Int {
    val primary = if (numeric) naturalCompare(normalizedText(a), normalizedText(b)) else normalizedText(a).compareTo(normalizedText(b))
    if (primary != 0) return primary
    val accents = stripDiacritics(a).compareTo(stripDiacritics(b))
    if (accents != 0) return -accents.coerceIn(-1, 1)
    // Lower case sorts before upper case, as in ICU.
    return -a.compareTo(b).coerceIn(-1, 1)
}

private fun naturalCompare(a: String, b: String): Int {
    var i = 0
    var j = 0
    while (i < a.length && j < b.length) {
        val ca = a[i]
        val cb = b[j]
        if (ca.isDigit() && cb.isDigit()) {
            val startA = i
            val startB = j
            while (i < a.length && a[i].isDigit()) i++
            while (j < b.length && b[j].isDigit()) j++
            val na = a.substring(startA, i).trimStart('0')
            val nb = b.substring(startB, j).trimStart('0')
            if (na.length != nb.length) return na.length - nb.length
            val cmp = na.compareTo(nb)
            if (cmp != 0) return cmp
        } else {
            if (ca != cb) return ca.compareTo(cb)
            i++
            j++
        }
    }
    return (a.length - i) - (b.length - j)
}

/** Comparator for [localeCompare]. */
fun <T> localeComparator(numeric: Boolean = false, selector: (T) -> String): Comparator<T> =
    Comparator { x, y -> localeCompare(selector(x), selector(y), numeric) }

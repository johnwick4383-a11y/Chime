package com.aireply.keyboard

enum class KType { CHAR, SHIFT, DEL, SYMS, ABC, PAGE, ENTER, SPACE, GAP }

// One key. weight = relative width in its row. alts = characters shown on long-press.
data class Key(
    val label: String,
    val type: KType = KType.CHAR,
    val weight: Float = 1f,
    val alts: String = ""
)

// Samsung-style layouts: QWERTY (optional number row), symbols page 1/2 and 2/2.
// Change a layout here and nothing else needs to change.
object Layouts {
    private val accents = mapOf(
        'a' to "àáâäæãåā", 'e' to "èéêëēėę", 'i' to "îïíīįì", 'o' to "ôöòóœøōõ",
        'u' to "ûüùúū", 'c' to "çćč", 'n' to "ñń", 's' to "ßśš", 'y' to "ÿý", 'z' to "žźż"
    )

    private fun ch(c: Char, alts: String = ""): Key = Key(c.toString(), KType.CHAR, 1f, alts)

    private fun bottom(first: KType, label: String): List<Key> = listOf(
        Key(label, first, 1.5f),
        Key(",", KType.CHAR, 1f, ";:'\"-"),
        Key(" ", KType.SPACE, 5f),
        Key(".", KType.CHAR, 1f, "!?%#@&:;"),
        Key("enter", KType.ENTER, 1.5f)
    )

    fun letters(numberRow: Boolean): List<List<Key>> {
        val rows = ArrayList<List<Key>>()
        if (numberRow) rows.add("1234567890".map { ch(it) })
        val top = "qwertyuiop"
        rows.add(top.mapIndexed { i, c ->
            val digit = if (numberRow) "" else ((i + 1) % 10).toString()
            ch(c, digit + (accents[c] ?: ""))
        })
        val mid = ArrayList<Key>()
        mid.add(Key("", KType.GAP, 0.5f))
        "asdfghjkl".forEach { mid.add(ch(it, accents[it] ?: "")) }
        mid.add(Key("", KType.GAP, 0.5f))
        rows.add(mid)
        val low = ArrayList<Key>()
        low.add(Key("shift", KType.SHIFT, 1.5f))
        "zxcvbnm".forEach { low.add(ch(it, accents[it] ?: "")) }
        low.add(Key("del", KType.DEL, 1.5f))
        rows.add(low)
        rows.add(bottom(KType.SYMS, "!#1"))
        return rows
    }

    fun symbols(page: Int): List<List<Key>> {
        val rows = ArrayList<List<Key>>()
        rows.add("1234567890".map { ch(it) })
        val r2 = if (page == 1) "@#\$_&-+()/" else "£¢€¥^°={}\\"
        rows.add(r2.map { ch(it) })
        val r3 = if (page == 1) "*\"':;!?" else "%©®~|<>"
        val third = ArrayList<Key>()
        third.add(Key(if (page == 1) "1/2" else "2/2", KType.PAGE, 1.5f))
        r3.forEach { third.add(ch(it)) }
        third.add(Key("del", KType.DEL, 1.5f))
        rows.add(third)
        rows.add(bottom(KType.ABC, "ABC"))
        return rows
    }
}

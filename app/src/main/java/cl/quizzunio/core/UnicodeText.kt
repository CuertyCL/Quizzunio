package cl.quizzunio.core

internal fun String.hasValidUnicode(): Boolean {
    var offset = 0
    while (offset < length) {
        val point = codePointAt(offset)
        if (point in 0xD800..0xDFFF) return false
        offset += Character.charCount(point)
    }
    return true
}

internal fun String.utf8ByteCount(): Long {
    var offset = 0
    var bytes = 0L
    while (offset < length) {
        val point = codePointAt(offset)
        require(point !in 0xD800..0xDFFF) { "El texto contiene una secuencia Unicode inválida." }
        val units = Character.charCount(point)
        bytes += when {
            point < 0x80 -> 1
            point < 0x800 -> 2
            else -> units + 2
        }
        offset += units
    }
    return bytes
}

internal fun String.hasNonBlankContent(): Boolean = any {
    !Character.isWhitespace(it) && !Character.isSpaceChar(it) && it != '\u0085'
}

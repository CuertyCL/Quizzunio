package cl.quizzunio.study

import cl.quizzunio.core.utf8ByteCount
import org.junit.Assert.*
import org.junit.Test

class QuizContentValidatorTest {
    @Test fun countsOnlyDecodedUtf8BytesIncludingSupplementaryCharacters() {
        val text = "\tñ漢😀e\u0301\r\n"
        assertEquals(15L, text.utf8ByteCount())
        assertEquals(text.toByteArray(Charsets.UTF_8).size.toLong(), text.utf8ByteCount())
        assertThrows(IllegalArgumentException::class.java) { "\uD800".utf8ByteCount() }
        assertThrows(IllegalArgumentException::class.java) { "\uDC00".utf8ByteCount() }
    }

    @Test fun titleAndFieldsEnforceTheirOwnUnitsAndIncludeExactBoundaries() {
        QuizContentValidator.title("😀".repeat(255))
        assertThrows(IllegalArgumentException::class.java) { QuizContentValidator.title("😀".repeat(256)) }
        val exact = "😀".repeat(16_383) + "漢"
        assertEquals(65_535L, exact.utf8ByteCount())
        QuizContentValidator.question(exact, listOf("ñ", exact, "e\u0301", "∑"), 3)
        assertThrows(IllegalArgumentException::class.java) {
            QuizContentValidator.question(exact + "a", List(4) { "opción" }, 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            QuizContentValidator.question("Pregunta", listOf("ñ", "漢", exact + "a", "∑"), 1)
        }
    }

    @Test fun rejectsMissingBlankAndMalformedFieldsWithoutRequiringDistinctAlternatives() {
        val options = listOf("ñ", "漢", "😀", "😀")
        QuizContentValidator.question("  \t∑\n", options, 0)
        listOf("", "\n\t \u00A0\u0085\u3000", "\uD800").forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { QuizContentValidator.title(invalid) }
            assertThrows(IllegalArgumentException::class.java) { QuizContentValidator.question(invalid, options, 0) }
            assertThrows(IllegalArgumentException::class.java) {
                QuizContentValidator.question("Pregunta", listOf(invalid) + options.drop(1), 0)
            }
        }
        assertThrows(IllegalArgumentException::class.java) { QuizContentValidator.question("Pregunta", options.dropLast(1), 0) }
        assertThrows(IllegalArgumentException::class.java) { QuizContentValidator.question("Pregunta", options, 4) }
    }
}

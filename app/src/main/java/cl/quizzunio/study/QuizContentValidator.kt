package cl.quizzunio.study

import cl.quizzunio.core.hasNonBlankContent
import cl.quizzunio.core.hasValidUnicode
import cl.quizzunio.core.utf8ByteCount

internal object QuizContentValidator {
    const val MAX_QUESTIONS = 15
    const val MAX_TITLE_POINTS = 255
    const val MAX_FIELD_BYTES = 65_535

    fun title(value: String) {
        require(value.hasNonBlankContent() && value.hasValidUnicode() &&
            value.codePointCount(0, value.length) <= MAX_TITLE_POINTS
        ) { "Escribe un título válido de hasta $MAX_TITLE_POINTS puntos de código." }
    }

    fun question(prompt: String, options: List<String>, correct: Int) {
        require(options.size == 4 && correct in 0..3) {
            "Cada pregunta necesita cuatro alternativas y una respuesta correcta entre 1 y 4."
        }
        require(validField(prompt)) { "Cada enunciado debe tener texto de hasta $MAX_FIELD_BYTES bytes UTF-8." }
        require(options.all(::validField)) { "Cada alternativa debe tener texto de hasta $MAX_FIELD_BYTES bytes UTF-8." }
    }

    private fun validField(value: String): Boolean =
        value.hasNonBlankContent() && value.utf8ByteCount() <= MAX_FIELD_BYTES
}

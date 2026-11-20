package cl.quizzunio.study

import org.junit.Assert.*
import org.junit.Test

class LocalQuizGeneratorTest {
    private val material = """
        La fotosíntesis es el proceso que transforma la energía luminosa en energía química para las plantas.
        La mitocondria es un orgánulo que produce energía para las actividades celulares mediante respiración.
        La clorofila es un pigmento que absorbe la luz solar en los tejidos verdes de las plantas.
        La membrana es una estructura que regula el intercambio de sustancias entre las células y su entorno.
        El ribosoma es una estructura responsable de fabricar proteínas siguiendo la información genética.
        El cromosoma es una estructura que contiene material genético organizado para la reproducción celular.
    """.trimIndent()

    @Test fun generatesExactCountFromRealMaterialAndPreservesUnicode() {
        val questions = LocalQuizGenerator().generate(material, 3)
        assertEquals(3, questions.size)
        assertEquals(3, questions.map { it.question }.distinct().size)
        questions.forEach {
            assertEquals(4, it.answers.size)
            assertEquals(4, it.answers.distinct().size)
            assertTrue(it.correctAnswer in 0..3)
            assertTrue(material.contains(it.answers[it.correctAnswer]))
            assertTrue(it.question.contains("_____"))
        }
        assertEquals(questions, LocalQuizGenerator().generate(material, 3))
    }

    @Test fun insufficientMaterialProducesAnExplicitError() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            LocalQuizGenerator().generate("Material insuficiente.", 10)
        }
        assertTrue(error.message.orEmpty().contains("suficientes conceptos"))
    }

    @Test fun neverSilentlyReducesRequestedCount() {
        assertThrows(IllegalArgumentException::class.java) { LocalQuizGenerator().generate(material, 50) }
        listOf(0, 51).forEach { count ->
            assertThrows(IllegalArgumentException::class.java) { LocalQuizGenerator().generate(material, count) }
        }
    }
}

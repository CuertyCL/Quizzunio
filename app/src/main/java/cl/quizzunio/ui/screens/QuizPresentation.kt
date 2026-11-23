package cl.quizzunio.ui.screens

internal enum class QuizProgress(val label: String) {
    Completed("Completado"),
    InProgress("En progreso"),
    NotStarted("Por intentar")
}

internal fun quizProgress(completed: Boolean, answers: List<Int?>): QuizProgress = when {
    completed -> QuizProgress.Completed
    answers.any { it != null } -> QuizProgress.InProgress
    else -> QuizProgress.NotStarted
}

internal enum class ResultBand(val heading: String) {
    Practice("¡Sigue practicando!"),
    Good("¡Buen trabajo!"),
    Excellent("¡Excelente!")
}

internal data class QuizResult(val correct: Int, val total: Int) {
    val percentage = if (total == 0) 0 else ((correct.toLong() * 100) / total).toInt()
    val band = when {
        percentage < 40 -> ResultBand.Practice
        percentage < 70 -> ResultBand.Good
        else -> ResultBand.Excellent
    }
}

internal fun quizResult(correct: Int, total: Int): QuizResult {
    val count = total.coerceAtLeast(0)
    return QuizResult(correct.coerceIn(0, count), count)
}

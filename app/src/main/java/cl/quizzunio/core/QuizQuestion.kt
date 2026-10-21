package cl.quizzunio.core

data class QuizQuestion(
    val question: String,
    val answers: List<String>,
    val correctAnswer: Int
)
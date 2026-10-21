package cl.quizzunio.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import cl.quizzunio.ui.screens.AiProcessingScreen
import cl.quizzunio.ui.screens.CreateQuizScreen
import cl.quizzunio.ui.screens.LoginScreen
import cl.quizzunio.ui.screens.QuizHomeScreen
import cl.quizzunio.ui.screens.QuizPlayScreen
import cl.quizzunio.ui.screens.QuizResultScreen
import cl.quizzunio.ui.screens.RegisterScreen
import cl.quizzunio.ui.screens.SettingsScreen
import cl.quizzunio.ui.screens.WelcomeScreen

/**
 * Rutas y destinos de la aplicación basados en docs/maquetas/Flujo-de-App.png
 */
sealed class Screen(val route: String) {
    object Welcome : Screen("welcome")
    object Login : Screen("login")
    object Register : Screen("register")
    object Home : Screen("home")
    object Settings : Screen("settings")
    object CreateQuiz : Screen("create_quiz")
    object AiProcessing : Screen("ai_processing")
    object QuizPlay : Screen("quiz_play")
    object QuizResult : Screen("quiz_result")
}

/**
 * Orquestador principal de navegación de Quizzunio
 */
@Composable
fun AppNavHost(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    startDestination: String = Screen.Welcome.route
) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier
    ) {
        // Pantalla 01: Bienvenida
        composable(Screen.Welcome.route) {
            WelcomeScreen(
                onNavigateToLogin = {
                    navController.navigate(Screen.Login.route)
                },
                onNavigateToRegister = {
                    navController.navigate(Screen.Register.route)
                }
            )
        }

        // Pantalla 02: Iniciar Sesión
        composable(Screen.Login.route) {
            LoginScreen(
                onLoginSuccess = {
                    navController.navigate(Screen.Home.route) {
                        popUpTo(Screen.Welcome.route) { inclusive = true }
                    }
                },
                onNavigateToRegister = {
                    navController.navigate(Screen.Register.route)
                },
                onForgotPasswordClick = {
                    // Acción para recuperación de contraseña
                }
            )
        }

        // Pantalla 03: Registrarse
        composable(Screen.Register.route) {
            RegisterScreen(
                onRegisterSuccess = {
                    navController.navigate(Screen.Home.route) {
                        popUpTo(Screen.Welcome.route) { inclusive = true }
                    }
                },
                onNavigateToLogin = {
                    navController.navigate(Screen.Login.route)
                }
            )
        }

        // Pantalla 04: Inicio Cuestionarios
        composable(Screen.Home.route) {
            QuizHomeScreen(
                onCreateQuizClick = {
                    navController.navigate(Screen.CreateQuiz.route)
                },
                onSettingsClick = {
                    navController.navigate(Screen.Settings.route)
                },
                onQuizClick = { _ ->
                    navController.navigate(Screen.QuizPlay.route)
                }
            )
        }

        // Pantalla 05: Ajustes
        composable(Screen.Settings.route) {
            SettingsScreen(
                onBackClick = {
                    navController.popBackStack()
                },
                onThemeToggle = {
                    // Alternar tema
                },
                onLogoutClick = {
                    navController.navigate(Screen.Welcome.route) {
                        popUpTo(Screen.Home.route) { inclusive = true }
                    }
                }
            )
        }

        // Pantalla 06: Crear Cuestionario
        composable(Screen.CreateQuiz.route) {
            CreateQuizScreen(
                onBackClick = {
                    navController.popBackStack()
                },
                onSaveDraftClick = {
                    navController.popBackStack()
                },
                onGenerateAiClick = {
                    navController.navigate(Screen.AiProcessing.route)
                }
            )
        }

        // Pantalla 07: Procesamiento IA
        composable(Screen.AiProcessing.route) {
            AiProcessingScreen(
                onProcessingComplete = {
                    navController.navigate(Screen.QuizPlay.route) {
                        popUpTo(Screen.CreateQuiz.route) { inclusive = true }
                    }
                }
            )
        }

        // Pantalla 08: Responder Cuestionario
        composable(Screen.QuizPlay.route) {
            QuizPlayScreen(
                onBackClick = {
                    navController.popBackStack()
                },
                onFinishQuiz = {
                    navController.navigate(Screen.QuizResult.route) {
                        popUpTo(Screen.Home.route)
                    }
                }
            )
        }

        // Pantalla 09: Resultados
        composable(Screen.QuizResult.route) {
            QuizResultScreen(
                correctAnswers = 8,
                totalQuestions = 10,
                onRetryClick = {
                    navController.navigate(Screen.QuizPlay.route) {
                        popUpTo(Screen.Home.route)
                    }
                },
                onReviewAnswersClick = {
                    navController.navigate(Screen.QuizPlay.route) {
                        popUpTo(Screen.Home.route)
                    }
                },
                onHomeClick = {
                    navController.navigate(Screen.Home.route) {
                        popUpTo(Screen.Home.route) { inclusive = true }
                    }
                }
            )
        }
    }
}

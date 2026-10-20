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
 * Destinos de navegación basados en docs/maquetas/Flujo-de-App.png
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
 * NavHost principal de Quizzunio (Quizly)
 * Requiere la dependencia: androidx.navigation:navigation-compose
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
        composable(Screen.Welcome.route) {
            WelcomeScreen(
                onNavigateToLogin = { navController.navigate(Screen.Login.route) },
                onNavigateToRegister = { navController.navigate(Screen.Register.route) }
            )
        }

        composable(Screen.Login.route) {
            LoginScreen(
                onLoginSuccess = {
                    navController.navigate(Screen.Home.route) {
                        popUpTo(Screen.Welcome.route) { inclusive = true }
                    }
                },
                onNavigateToRegister = { navController.navigate(Screen.Register.route) }
            )
        }

        composable(Screen.Register.route) {
            RegisterScreen(
                onRegisterSuccess = {
                    navController.navigate(Screen.Home.route) {
                        popUpTo(Screen.Welcome.route) { inclusive = true }
                    }
                },
                onNavigateToLogin = { navController.navigate(Screen.Login.route) }
            )
        }

        composable(Screen.Home.route) {
            QuizHomeScreen(
                onCreateQuizClick = { navController.navigate(Screen.CreateQuiz.route) },
                onSettingsClick = { navController.navigate(Screen.Settings.route) },
                onQuizClick = { navController.navigate(Screen.QuizPlay.route) }
            )
        }

        composable(Screen.Settings.route) {
            SettingsScreen(
                onBackClick = { navController.popBackStack() },
                onLogoutClick = {
                    navController.navigate(Screen.Welcome.route) {
                        popUpTo(Screen.Home.route) { inclusive = true }
                    }
                }
            )
        }

        composable(Screen.CreateQuiz.route) {
            CreateQuizScreen(
                onBackClick = { navController.popBackStack() },
                onSaveDraftClick = { navController.popBackStack() },
                onGenerateAiClick = { navController.navigate(Screen.AiProcessing.route) }
            )
        }

        composable(Screen.AiProcessing.route) {
            AiProcessingScreen(
                onProcessingComplete = {
                    navController.navigate(Screen.QuizPlay.route) {
                        popUpTo(Screen.CreateQuiz.route) { inclusive = true }
                    }
                }
            )
        }

        composable(Screen.QuizPlay.route) {
            QuizPlayScreen(
                onBackClick = { navController.popBackStack() },
                onFinishQuiz = {
                    navController.navigate(Screen.QuizResult.route) {
                        popUpTo(Screen.Home.route)
                    }
                }
            )
        }

        composable(Screen.QuizResult.route) {
            QuizResultScreen(
                onRetryClick = {
                    navController.navigate(Screen.QuizPlay.route) {
                        popUpTo(Screen.Home.route)
                    }
                },
                onReviewAnswersClick = { navController.popBackStack() },
                onHomeClick = {
                    navController.navigate(Screen.Home.route) {
                        popUpTo(Screen.Home.route) { inclusive = true }
                    }
                }
            )
        }
    }
}

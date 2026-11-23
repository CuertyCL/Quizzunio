package cl.quizzunio.ui.navigation

import android.widget.Toast
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import cl.quizzunio.study.LocalStudyQuizViewModel
import cl.quizzunio.study.rememberStudyQuizViewModel
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
import cl.quizzunio.auth.LocalAuthViewModel
import cl.quizzunio.auth.SessionState
import cl.quizzunio.study.AccountStudyCoordinator
import cl.quizzunio.study.AccountQuizStore
import cl.quizzunio.study.RemoteQuizRepository
import cl.quizzunio.ui.screens.QuizEditorScreen
import cl.quizzunio.ui.screens.LocalQuizEditorNavigation
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.CircularProgressIndicator
import cl.quizzunio.study.StudyNavigationTarget

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
    val studyModel = rememberStudyQuizViewModel()
    val studyState by studyModel.state.collectAsState()
    val context = LocalContext.current
    val authModel = LocalAuthViewModel.current
    val auth = authModel?.state?.collectAsState()?.value
    val user = (auth?.session as? SessionState.Authenticated)?.user
    val accountOwner by studyModel.accountOwner.collectAsState()
    val workspace by studyModel.workspace.collectAsState()
    val expectedWorkspace = if (user != null) AccountQuizStore(context, authModel.session.server, user.userId).workspaceKey else null
    val navigationTarget by studyModel.navigationTarget.collectAsState()
    var previousUserId by remember { mutableStateOf<Long?>(null) }
    var showAnonymousWarning by remember { mutableStateOf(false) }
    LaunchedEffect(user?.userId, auth?.server, auth?.session is SessionState.Anonymous) {
        if (auth?.session is SessionState.Restoring || auth?.session is SessionState.Unavailable) return@LaunchedEffect
        val coordinator = if (user != null) AccountStudyCoordinator(user.userId,
            RemoteQuizRepository(authModel.client, authModel.session), AccountQuizStore(context, authModel.session.server, user.userId)) else null
        if (studyModel.workspace.value != expectedWorkspace) studyModel.bindAccount(coordinator)
        if (previousUserId != null && user == null) navController.navigate(Screen.Home.route) {
            popUpTo(navController.graph.id) { inclusive = true }
        }
        previousUserId = user?.userId
    }
    if (auth?.session is SessionState.Restoring || auth?.session is SessionState.Unavailable) {
        Column {
            if (auth.session is SessionState.Restoring) { CircularProgressIndicator(); Text("Restaurando sesión...") }
            else {
                Text((auth.session as SessionState.Unavailable).message)
                TextButton(onClick = { authModel?.restore() }, enabled = !auth.busy) { Text("Reintentar restauración") }
                TextButton(onClick = { authModel?.abandon() }, enabled = !auth.busy) { Text("Abandonar sesión local y continuar como anónimo") }
            }
            auth.message?.let { Text(it) }
        }
        return
    }
    if (accountOwner != user?.userId || workspace != expectedWorkspace) { CircularProgressIndicator(); return }
    LaunchedEffect(navigationTarget) {
        when (navigationTarget) {
            StudyNavigationTarget.Play -> if (navController.currentDestination?.route == Screen.CreateQuiz.route) {
                navController.navigate(Screen.QuizPlay.route) { popUpTo(Screen.CreateQuiz.route) { inclusive = true } }
            }
            StudyNavigationTarget.Results -> if (navController.currentDestination?.route == Screen.QuizPlay.route) {
                navController.navigate(Screen.QuizResult.route) { popUpTo(Screen.Home.route) }
            }
            StudyNavigationTarget.EditorClosed -> if (navController.currentDestination?.route == "edit_quiz/{localId}") navController.popBackStack()
            null -> Unit
        }
        studyModel.consumeNavigation()
    }
    CompositionLocalProvider(LocalStudyQuizViewModel provides studyModel,
        LocalQuizEditorNavigation provides { id -> navController.navigate("edit_quiz/$id") }) {
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
                    if (authModel?.state?.value?.session !is SessionState.Authenticated) return@LoginScreen
                    navController.navigate(Screen.Home.route) {
                        popUpTo(navController.graph.id) { inclusive = true }
                    }
                },
                onNavigateToRegister = {
                    navController.navigate(Screen.Register.route)
                },
                onForgotPasswordClick = {
                    // Acción para recuperación de contraseña
                    authModel?.showMessage("El servidor todavía no dispone de recuperación de contraseña. No se ha enviado ningún correo.")
                }
            )
        }

        // Pantalla 03: Registrarse
        composable(Screen.Register.route) {
            RegisterScreen(
                onRegisterSuccess = {
                    if (authModel?.state?.value?.session !is SessionState.Authenticated) return@RegisterScreen
                    navController.navigate(Screen.Home.route) {
                        popUpTo(navController.graph.id) { inclusive = true }
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
                    authModel?.interaction()
                    navController.navigate(Screen.CreateQuiz.route)
                },
                onSettingsClick = {
                    navController.navigate(Screen.Settings.route)
                },
                onQuizClick = { id ->
                    authModel?.interaction()
                    if (studyModel.selectQuiz(id)) navController.navigate(
                        if (studyModel.state.value.activeQuiz?.completed == true) Screen.QuizResult.route else Screen.QuizPlay.route)
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
                    if (user != null) authModel?.logout()
                    else if (studyState.quizzes.isNotEmpty() || studyState.documents.isNotEmpty()) showAnonymousWarning = true
                    else navController.navigate(Screen.Welcome.route)
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
                    studyModel.requestGeneratedQuiz()
                },
                onGenerateAiClick = {
                    studyModel.startRemoteGeneration {
                        navController.navigate(Screen.AiProcessing.route)
                    }
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

        composable("edit_quiz/{localId}") { entry ->
            QuizEditorScreen(entry.arguments?.getString("localId").orEmpty(), onBack = { navController.popBackStack() })
        }

        // Pantalla 08: Responder Cuestionario
        composable(Screen.QuizPlay.route) {
            QuizPlayScreen(
                onBackClick = {
                    navController.popBackStack()
                },
                onFinishQuiz = {
                    studyModel.requestResults()
                }
            )
        }

        // Pantalla 09: Resultados
        composable(Screen.QuizResult.route) {
            if (studyState.activeQuiz == null) {
                LaunchedEffect(studyState.isBusy) {
                    if (!studyState.isBusy) navController.navigate(Screen.Home.route) {
                        popUpTo(Screen.Home.route) { inclusive = true }
                    }
                }
                return@composable
            }
            QuizResultScreen(
                correctAnswers = studyState.correctAnswers,
                totalQuestions = studyState.activeQuiz?.questions?.size ?: 1,
                onRetryClick = {
                    studyModel.resetAttempt()
                    navController.navigate(Screen.QuizPlay.route) {
                        popUpTo(Screen.Home.route)
                    }
                },
                onReviewAnswersClick = {
                    studyModel.reviewAnswers()
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
    if (showAnonymousWarning) AlertDialog(
        onDismissRequest = { showAnonymousWarning = false },
        title = { Text("Cuestionarios del dispositivo") },
        text = { Text("Tus cuestionarios y respuestas seguirán guardados en este dispositivo. Al iniciar sesión quedarán separados de la cuenta; puedes importarlos después mediante una acción explícita. Los materiales todavía sin generar no se conservan.") },
        confirmButton = { TextButton(onClick = { showAnonymousWarning = false; navController.navigate(Screen.Welcome.route) }) { Text("Continuar al acceso") } },
        dismissButton = { TextButton(onClick = { showAnonymousWarning = false }) { Text("Seguir como anónimo") } }
    )
    auth?.message?.let { message -> AlertDialog(onDismissRequest = { authModel?.clearMessage() }, title = { Text("Cuenta") },
        text = { Text(message) }, confirmButton = { TextButton(onClick = { authModel?.clearMessage() }) { Text("Entendido") } }) }
    studyState.message?.let { message ->
        AlertDialog(
            onDismissRequest = { studyModel.clearMessage() },
            title = { Text("Cuestionarios") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { studyModel.clearMessage() }) { Text("Entendido") }
            }
        )
    }
    }
}

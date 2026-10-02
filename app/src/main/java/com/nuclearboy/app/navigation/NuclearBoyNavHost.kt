package com.nuclearboy.app.navigation

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.nuclearboy.app.ui.projects.ProjectViewModel
import com.nuclearboy.app.ui.splash.SplashScreen
import com.nuclearboy.app.ui.tutorial.TutorialScreen
import com.nuclearboy.ui.chat.ChatScreen
import kotlinx.coroutines.launch

object NavRoutes {
    const val SPLASH = "splash"
    const val PROJECT_LIST = "project_list"
    const val CHAT = "chat/{projectId}?initialMessage={initialMessage}"
    const val SETTINGS = "settings"
    const val ONBOARDING = "onboarding"
    const val TUTORIAL = "tutorial"
    const val SKILL_MANAGER = "skill_manager"
    const val TERMINAL = "terminal"

    fun chatRoute(projectId: String, initialMessage: String = "") =
        "chat/$projectId" + if (initialMessage.isNotEmpty())
            "?initialMessage=${java.net.URLEncoder.encode(initialMessage, "UTF-8")}" else ""
}

@Composable
fun NuclearBoyNavHost(
    navController: NavHostController,
    projectViewModel: ProjectViewModel,
    onMenuClick: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    NavHost(
        navController = navController,
        startDestination = NavRoutes.SPLASH,
        enterTransition = { fadeIn(tween(300)) + slideInHorizontally(tween(300)) { it / 4 } },
        exitTransition = { fadeOut(tween(200)) + slideOutHorizontally(tween(200)) { -it / 4 } },
        popEnterTransition = { fadeIn(tween(300)) },
        popExitTransition = { fadeOut(tween(200)) + slideOutHorizontally(tween(200)) { it / 4 } },
    ) {
        // ── Splash ────────────────────────────────────────
        composable(NavRoutes.SPLASH) {
            SplashScreen(
                onComplete = {
                    // Wait for the initial project scan before choosing the
                    // destination. This restores the last project reliably
                    // after a force-stop instead of briefly/forever opening
                    // an empty general conversation.
                    scope.launch {
                        val startupProjectId = projectViewModel.awaitStartupProjectId()
                        projectViewModel.selectProject(startupProjectId)
                        navController.navigate(NavRoutes.chatRoute(startupProjectId)) {
                            popUpTo(NavRoutes.SPLASH) { inclusive = true }
                        }
                    }
                },
            )
        }

        // ── Chat (General Agent + 项目对话) ──────────────
        composable(
            route = NavRoutes.CHAT,
            arguments = listOf(
                navArgument("projectId") { type = NavType.StringType },
                navArgument("initialMessage") { type = NavType.StringType; defaultValue = "" },
            ),
        ) { backStackEntry ->
            val projectId = backStackEntry.arguments?.getString("projectId") ?: return@composable
            val initialMessage = backStackEntry.arguments?.getString("initialMessage") ?: ""
            val ctx = androidx.compose.ui.platform.LocalContext.current

            ChatScreen(
                projectId = projectId,
                initialMessage = initialMessage,
                onNavigateBack = { navController.popBackStack() },
                onMenuClick = onMenuClick,
                onNotification = { msg, project ->
                    when (msg) {
                        "thinking" -> com.nuclearboy.app.service.AgentForegroundService.start(ctx, project)
                        "stop" -> com.nuclearboy.app.service.AgentForegroundService.stop(ctx)
                        else -> com.nuclearboy.app.service.AgentForegroundService.update(ctx, msg, project)
                    }
                },
            )
        }

        composable(NavRoutes.SETTINGS) {
            com.nuclearboy.app.ui.settings.SettingsScreen(
                onNavigateBack = { navController.popBackStack() },
                onMenuClick = onMenuClick,
                onNavigateToTutorial = { navController.navigate(NavRoutes.TUTORIAL) },
                onNavigateToTerminal = { navController.navigate(NavRoutes.TERMINAL) },
            )
        }

        // ── 远程终端 ──────────────────────────────────────
        composable(NavRoutes.TERMINAL) {
            com.nuclearboy.app.ui.terminal.TerminalScreen(
                onNavigateBack = { navController.popBackStack() },
            )
        }

        // ── API Key Tutorial ──────────────────────────────
        composable(NavRoutes.TUTORIAL) {
            TutorialScreen(
                onNavigateBack = { navController.popBackStack() },
            )
        }

        composable(NavRoutes.SKILL_MANAGER) {
            com.nuclearboy.app.ui.skills.SkillManagerPanel(
                skillManager = projectViewModel.skillManager,
                onNavigateBack = { navController.popBackStack() },
            )
        }

        composable(NavRoutes.ONBOARDING) {
            val settingsViewModel: com.nuclearboy.app.ui.settings.SettingsViewModel = hiltViewModel()
            com.nuclearboy.app.ui.onboarding.OnboardingScreen(
                apiKeyManager = settingsViewModel.apiKeyManager,
                onComplete = {
                    navController.navigate(NavRoutes.chatRoute("__general__")) {
                        popUpTo(NavRoutes.ONBOARDING) { inclusive = true }
                    }
                },
            )
        }
    }
}

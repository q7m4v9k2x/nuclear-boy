package com.nuclearboy.app

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.nuclearboy.app.navigation.NavRoutes
import com.nuclearboy.app.navigation.NuclearBoyNavHost
import com.nuclearboy.app.ui.projects.ProjectViewModel
import com.nuclearboy.app.ui.sidebar.SidebarContent
import com.nuclearboy.app.ui.theme.NuclearBoyTheme
import com.nuclearboy.app.update.UpdateDownloader
import dagger.hilt.android.AndroidEntryPoint
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* 用户拒绝也不阻塞，只是收不到任务完成提醒 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        android.util.Log.e("NuclearBoy", "[MainActivity] onCreate")

        // Android 13+ 通知需运行时授权，否则前台服务和远程任务完成提醒都不显示
        maybeRequestNotificationPermission()

        // Check API key status
        val apiKeyManager = com.nuclearboy.api.deepseek.ApiKeyManager(this)
        val keyStatus = if (apiKeyManager.getActiveKey() != null) "configured" else "missing"
        android.util.Log.e("NuclearBoy", "[MainActivity] API key status: $keyStatus")

        handleShareIntent(intent)

        enableEdgeToEdge()
        setContent { NuclearBoyTheme(darkTheme = true) { NuclearBoyMainScreen() } }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // DownloadManager broadcasts are lost if Android kills the process during
        // an update. Re-query the persisted download after every return from the
        // unknown-source settings page and finish installation when authorized.
        UpdateDownloader.ensurePendingDownloadReceiver(applicationContext)
        lifecycleScope.launch {
            val pending = withContext(Dispatchers.IO) {
                UpdateDownloader.pendingInstallFile(applicationContext)
            }
            if (pending != null) {
                UpdateDownloader.install(applicationContext, pending)
            }
        }
    }

    /** 外部 App 通过分享菜单把文本发进来 → 投递到聊天输入框；桌面快捷方式 → 导航指令。 */
    private fun handleShareIntent(intent: android.content.Intent?) {
        if (intent?.action == android.content.Intent.ACTION_SEND && intent.type == "text/plain") {
            intent.getStringExtra(android.content.Intent.EXTRA_TEXT)?.let {
                com.nuclearboy.common.SharedIntentBus.emit(it)
            }
        }
        if (intent?.action == "com.nuclearboy.app.SHORTCUT_TERMINAL") {
            com.nuclearboy.common.NavCommandBus.emit(com.nuclearboy.common.NavCommandBus.TARGET_TERMINAL)
        }
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

@Composable
private fun NuclearBoyMainScreen() {
    val navController = rememberNavController()
    val projectViewModel: ProjectViewModel = hiltViewModel()
    val projects by projectViewModel.projects.collectAsState()
    val activeSkills by projectViewModel.activeSkills.collectAsState()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    // Track current route
    val currentEntry by navController.currentBackStackEntryAsState()
    val currentRoute = currentEntry?.destination?.route
    val currentProjectId = currentEntry?.arguments?.getString("projectId")

    // 桌面快捷方式等外部导航指令 → 跳到对应页面
    LaunchedEffect(Unit) {
        com.nuclearboy.common.NavCommandBus.commands.collect { target ->
            when (target) {
                com.nuclearboy.common.NavCommandBus.TARGET_TERMINAL ->
                    navController.navigate(NavRoutes.TERMINAL) { launchSingleTop = true }
            }
        }
    }

    // Log drawer state changes
    LaunchedEffect(drawerState.isOpen) {
    }

    // Log navigation events
    LaunchedEffect(currentRoute, currentProjectId) {
    }

    // Back handler: close drawer first, then normal back
    BackHandler(enabled = drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }

    fun navigateToProject(projectId: String) {
        scope.launch { drawerState.close() }
        projectViewModel.selectProject(projectId)
        navController.navigate(NavRoutes.chatRoute(projectId)) {
            popUpTo(NavRoutes.chatRoute("__general__")) { inclusive = false }
            launchSingleTop = true
        }
    }

    fun createAndNavigate(name: String) {
        scope.launch {
            drawerState.close()
            val projectId = projectViewModel.createProjectAwait(name) ?: name
            projectViewModel.selectProject(projectId)
            navController.navigate(NavRoutes.chatRoute(projectId)) {
                popUpTo(NavRoutes.chatRoute("__general__")) { inclusive = false }
                launchSingleTop = true
            }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = true,
        scrimColor = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.6f),
        drawerContent = {
            SidebarContent(
                projects = projects,
                currentProjectId = currentProjectId,
                activeSkills = activeSkills,
                onGeneralAgentSelected = {
                    projectViewModel.selectProject("__general__")
                    scope.launch { drawerState.close() }
                    navController.navigate(NavRoutes.chatRoute("__general__")) {
                        popUpTo(NavRoutes.chatRoute("__general__")) { inclusive = true }
                        launchSingleTop = true
                    }
                },
                onProjectSelected = { navigateToProject(it) },
                onCreateProject = { createAndNavigate(it) },
                onDeleteProject = { projectViewModel.deleteProject(it) },
                onRenameProject = { id, newName -> projectViewModel.renameProject(id, newName) },
                onSettingsClick = {
                    scope.launch { drawerState.close() }
                    navController.navigate(NavRoutes.SETTINGS)
                },
                onSkillManagerClick = {
                    scope.launch { drawerState.close() }
                    navController.navigate(NavRoutes.SKILL_MANAGER)
                },
                onClose = { scope.launch { drawerState.close() } },
            )
        },
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            NuclearBoyNavHost(
                navController = navController,
                projectViewModel = projectViewModel,
                onMenuClick = {
                    projectViewModel.refreshProjects()
                    scope.launch { drawerState.open() }
                },
            )
        }
    }
}

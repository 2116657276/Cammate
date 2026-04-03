package com.liveaicapture.mvp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.liveaicapture.mvp.ui.CameraScreen
import com.liveaicapture.mvp.ui.CommunityPublishScreen
import com.liveaicapture.mvp.ui.CommunityScreen
import com.liveaicapture.mvp.ui.CommunityToolsScreen
import com.liveaicapture.mvp.ui.FeedbackScreen
import com.liveaicapture.mvp.ui.HomeScreen
import com.liveaicapture.mvp.ui.AiComposeScreen
import com.liveaicapture.mvp.ui.LoginScreen
import com.liveaicapture.mvp.ui.MainViewModel
import com.liveaicapture.mvp.ui.PoseRecommendScreen
import com.liveaicapture.mvp.ui.RegisterScreen
import com.liveaicapture.mvp.ui.RetouchScreen
import com.liveaicapture.mvp.ui.SettingsScreen
import com.liveaicapture.mvp.ui.theme.CamMateTheme
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CamMateTheme {
                CamMateApp()
            }
        }
    }
}

@Composable
fun CamMateApp() {
    val navController = rememberNavController()
    val vm: MainViewModel = viewModel()
    val authState by vm.authUiState.collectAsStateWithLifecycle()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val openHome: () -> Unit = {
        navController.navigate("home") {
            launchSingleTop = true
            restoreState = true
        }
    }
    val openCommunity: () -> Unit = {
        navController.navigate("community") {
            launchSingleTop = true
            restoreState = true
        }
    }
    val openSettings: () -> Unit = {
        navController.navigate("settings") {
            launchSingleTop = true
            restoreState = true
        }
    }

    LaunchedEffect(authState.checkingSession, authState.authenticated, currentRoute) {
        if (authState.checkingSession) return@LaunchedEffect
        val route = currentRoute ?: return@LaunchedEffect

        if (route == "splash") {
            val target = if (authState.authenticated) "home" else "login"
            navController.navigate(target) {
                popUpTo("splash") { inclusive = true }
                launchSingleTop = true
            }
            return@LaunchedEffect
        }

        val authPages = setOf("login", "register")
        if (authState.authenticated && route in authPages) {
            navController.navigate("home") {
                popUpTo("login") { inclusive = true }
                launchSingleTop = true
            }
            return@LaunchedEffect
        }

        if (!authState.authenticated && route !in authPages) {
            navController.navigate("login") {
                popUpTo(route) { inclusive = true }
                launchSingleTop = true
            }
        }
    }

    NavHost(
        navController = navController,
        startDestination = "splash",
    ) {
        composable("splash") {
            SplashScreen()
        }
        composable("login") {
            LoginScreen(
                viewModel = vm,
                openRegister = { navController.navigate("register") },
            )
        }
        composable("register") {
            RegisterScreen(
                viewModel = vm,
                onBackToLogin = { navController.popBackStack() },
            )
        }
        composable("home") {
            HomeScreen(
                openCamera = { navController.navigate("camera") },
                openPoseRecommend = { navController.navigate("pose_recommend") },
                openAiCompose = { navController.navigate("ai_compose") },
                openCommunity = openCommunity,
                openSettings = openSettings,
            )
        }
        composable("camera") {
            CameraScreen(
                viewModel = vm,
                backToCapture = openHome,
                openRetouch = {
                    navController.navigate("retouch") {
                        popUpTo("camera") { inclusive = false }
                        launchSingleTop = true
                    }
                },
                openFeedback = { navController.navigate("feedback") },
            )
        }
        composable("pose_recommend") {
            PoseRecommendScreen(
                viewModel = vm,
                onBackToCapture = openHome,
            )
        }
        composable("ai_compose") {
            AiComposeScreen(
                viewModel = vm,
                onBackToCapture = openHome,
            )
        }
        composable("settings") {
            SettingsScreen(
                viewModel = vm,
                openHome = openHome,
                openCommunity = openCommunity,
            )
        }
        composable("retouch") {
            RetouchScreen(
                viewModel = vm,
                onBackToCamera = {
                    if (!navController.popBackStack()) {
                        navController.navigate("camera") {
                            popUpTo("camera") { inclusive = false }
                            launchSingleTop = true
                        }
                    }
                },
                openFeedback = { navController.navigate("feedback") },
            )
        }
        composable("feedback") {
            FeedbackScreen(
                viewModel = vm,
                finishToCamera = {
                    navController.navigate("camera") {
                        popUpTo("camera") { inclusive = false }
                        launchSingleTop = true
                    }
                },
            )
        }
        composable("community") {
            CommunityScreen(
                viewModel = vm,
                openCapture = openHome,
                openSettings = openSettings,
                openPublish = { navController.navigate("community_publish") },
                openTools = { navController.navigate("community_tools") },
            )
        }
        composable("community_publish") {
            CommunityPublishScreen(
                viewModel = vm,
                onBackToCommunity = {
                    if (!navController.popBackStack()) {
                        openCommunity()
                    }
                },
            )
        }
        composable("community_tools") {
            CommunityToolsScreen(
                viewModel = vm,
                onBackToCommunity = {
                    if (!navController.popBackStack()) {
                        openCommunity()
                    }
                },
            )
        }
    }
}

@Composable
fun SplashScreen() {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "CamMate",
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
    }
}

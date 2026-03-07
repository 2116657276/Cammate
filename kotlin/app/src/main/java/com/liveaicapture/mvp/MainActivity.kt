package com.liveaicapture.mvp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.liveaicapture.mvp.ui.CameraScreen
import com.liveaicapture.mvp.ui.FeedbackScreen
import com.liveaicapture.mvp.ui.LoginScreen
import com.liveaicapture.mvp.ui.MainViewModel
import com.liveaicapture.mvp.ui.RegisterScreen
import com.liveaicapture.mvp.ui.RetouchScreen
import com.liveaicapture.mvp.ui.SettingsScreen
import com.liveaicapture.mvp.ui.theme.LiveAICaptureTheme
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LiveAICaptureTheme {
                LiveAICaptureApp()
            }
        }
    }
}

@Composable
private fun LiveAICaptureApp() {
    val navController = rememberNavController()
    val vm: MainViewModel = viewModel()
    val authState by vm.authUiState.collectAsStateWithLifecycle()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    LaunchedEffect(authState.checkingSession, authState.authenticated, currentRoute) {
        if (authState.checkingSession) return@LaunchedEffect
        val route = currentRoute ?: return@LaunchedEffect

        if (route == "splash") {
            val target = if (authState.authenticated) "camera" else "login"
            navController.navigate(target) {
                popUpTo("splash") { inclusive = true }
                launchSingleTop = true
            }
            return@LaunchedEffect
        }

        val authPages = setOf("login", "register")
        if (authState.authenticated && route in authPages) {
            navController.navigate("camera") {
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
        composable("camera") {
            CameraScreen(
                viewModel = vm,
                openSettings = { navController.navigate("settings") },
                openRetouch = {
                    navController.navigate("retouch") {
                        popUpTo("camera") { inclusive = false }
                        launchSingleTop = true
                    }
                },
            )
        }
        composable("settings") {
            SettingsScreen(
                viewModel = vm,
                onBack = {
                    if (!navController.popBackStack()) {
                        navController.navigate("camera") {
                            launchSingleTop = true
                        }
                    }
                },
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
    }
}

@Composable
private fun SplashScreen() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF07101E), Color(0xFF16314A), Color(0xFF2B4C6B)),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text("LiveAICapture", color = Color.White)
    }
}

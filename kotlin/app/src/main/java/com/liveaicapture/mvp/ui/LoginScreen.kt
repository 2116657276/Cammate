package com.liveaicapture.mvp.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun LoginScreen(
    viewModel: MainViewModel,
    openRegister: () -> Unit,
) {
    val context = LocalContext.current
    val authState by viewModel.authUiState.collectAsStateWithLifecycle()

    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }

    val canSubmit = email.isNotBlank() && password.length >= 6 && !authState.loading

    LaunchedEffect(authState.errorMessage) {
        authState.errorMessage?.takeIf { it.isNotBlank() }?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF081424), Color(0xFF122A40), Color(0xFF1E3751)),
                ),
            )
            .padding(20.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xC81A2F46)),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("LiveAICapture", color = Color(0xFF7CE3FF), fontWeight = FontWeight.Bold)
                Text("欢迎回来", color = Color.White, fontWeight = FontWeight.SemiBold)

                OutlinedTextField(
                    value = email,
                    onValueChange = {
                        email = it
                        viewModel.clearAuthError()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("邮箱") },
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = {
                        password = it
                        viewModel.clearAuthError()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("密码（至少6位）") },
                )

                authState.errorMessage?.let {
                    Text(it, color = Color(0xFFFFB4AB))
                }

                Button(
                    onClick = { viewModel.login(email.trim(), password) },
                    enabled = canSubmit,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (authState.loading) "登录中..." else "登录")
                }

                TextButton(onClick = openRegister, modifier = Modifier.fillMaxWidth()) {
                    Text("没有账号？去注册", color = Color(0xFFBCE8FF))
                }
            }
        }
    }
}

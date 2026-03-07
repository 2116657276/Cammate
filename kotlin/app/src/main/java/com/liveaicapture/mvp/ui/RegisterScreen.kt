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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun RegisterScreen(
    viewModel: MainViewModel,
    onBackToLogin: () -> Unit,
) {
    val context = LocalContext.current
    val authState by viewModel.authUiState.collectAsStateWithLifecycle()

    var nickname by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var confirmPassword by rememberSaveable { mutableStateOf("") }

    val passwordMatched = password == confirmPassword
    val canSubmit = email.isNotBlank() && password.length >= 6 && passwordMatched && !authState.loading

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
                    listOf(Color(0xFF0D1020), Color(0xFF213050), Color(0xFF31486D)),
                ),
            )
            .padding(20.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xC6203551)),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("创建账号", color = Color.White, fontWeight = FontWeight.SemiBold)

                OutlinedTextField(
                    value = nickname,
                    onValueChange = {
                        nickname = it
                        viewModel.clearAuthError()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("昵称（可选）") },
                )
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
                OutlinedTextField(
                    value = confirmPassword,
                    onValueChange = {
                        confirmPassword = it
                        viewModel.clearAuthError()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("确认密码") },
                )

                if (!passwordMatched && confirmPassword.isNotBlank()) {
                    Text("两次密码不一致", color = Color(0xFFFFB4AB))
                }
                authState.errorMessage?.let {
                    Text(it, color = Color(0xFFFFB4AB))
                }

                Button(
                    onClick = { viewModel.register(email.trim(), password, nickname.trim()) },
                    enabled = canSubmit,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (authState.loading) "注册中..." else "注册并登录")
                }

                TextButton(onClick = onBackToLogin, modifier = Modifier.fillMaxWidth()) {
                    Text("已有账号，返回登录", color = Color(0xFFBCE8FF))
                }
            }
        }
    }
}

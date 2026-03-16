package com.liveaicapture.mvp.ui

import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.liveaicapture.mvp.ui.components.CamMatePage
import com.liveaicapture.mvp.ui.components.SectionCard

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

    val emailNormalized = email.trim()
        .replace('\uFF20', '@')
        .replace('\u3002', '.')
        .replace('\uFF0E', '.')
    val emailValid = Regex("""[^@\s]+@[^@\s]+\.[^@\s]+""").matches(emailNormalized)
    val passwordMatched = password == confirmPassword
    val nicknameTooLong = nickname.trim().length > 32
    val canSubmit = (
        emailNormalized.isNotBlank() &&
            emailValid &&
            !nicknameTooLong &&
            password.length >= 6 &&
            passwordMatched &&
            !authState.loading
        )

    LaunchedEffect(authState.errorMessage) {
        authState.errorMessage?.takeIf { it.isNotBlank() }?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
        }
    }

    CamMatePage(
        title = "创建 CamMate 账号",
        subtitle = "注册后即可使用完整拍摄流程",
        onBack = onBackToLogin,
    ) {
        item {
            SectionCard {
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
                    label = { Text("密码（至少 6 位）") },
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
                    Text("两次输入的密码不一致", color = Color(0xFFB42318), style = MaterialTheme.typography.bodyLarge)
                }
                if (email.isNotBlank() && !emailValid) {
                    Text("邮箱格式不正确", color = Color(0xFFB42318), style = MaterialTheme.typography.bodyLarge)
                }
                if (nicknameTooLong) {
                    Text("昵称最多 32 个字符", color = Color(0xFFB42318), style = MaterialTheme.typography.bodyLarge)
                }
                authState.errorMessage?.let {
                    Text(it, color = Color(0xFFB42318), style = MaterialTheme.typography.bodyLarge)
                }

                Button(
                    onClick = { viewModel.register(emailNormalized, password, nickname.trim()) },
                    enabled = canSubmit,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (authState.loading) "注册中..." else "注册并登录")
                }

                TextButton(onClick = onBackToLogin, modifier = Modifier.fillMaxWidth()) {
                    Text("已有账号，返回登录")
                }
            }
        }
    }
}

package rw.rutongo.mmems.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import rw.rutongo.mmems.android.data.AppSettings
import rw.rutongo.mmems.android.ui.theme.GreenDark

@Composable
fun LoginScreen(
    state: AppUiState,
    defaultBaseUrl: String,
    onLogin: (String, String, String) -> Unit,
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf(defaultBaseUrl) }
    var showPassword by remember { mutableStateOf(false) }
    var showServer by remember { mutableStateOf(false) }

    Box(
        Modifier
            .fillMaxSize()
            .background(GreenDark)
            .imePadding(),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text("RUTONGO", style = MaterialTheme.typography.displaySmall, color = androidx.compose.ui.graphics.Color.White, fontWeight = FontWeight.Black)
            Text(
                "Mechanical equipment management",
                style = MaterialTheme.typography.titleMedium,
                color =
                    androidx.compose.ui.graphics
                        .Color(0xFFC6E8D6),
            )
            Column(Modifier.height(32.dp)) {}
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Sign in", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text("Username") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Password") },
                        singleLine = true,
                        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(onClick = { showPassword = !showPassword }) {
                                Icon(if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility, contentDescription = "Show password")
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    state.loginError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    }
                    Button(
                        onClick = { onLogin(username, password, baseUrl) },
                        enabled = !state.signingIn,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                    ) {
                        if (state.signingIn) CircularProgressIndicator(Modifier.height(22.dp), strokeWidth = 2.dp) else Text("SIGN IN")
                    }
                    TextButton(onClick = { showServer = !showServer }) { Text(if (showServer) "Hide server setting" else "Server setting") }
                    if (showServer) {
                        OutlinedTextField(
                            value = baseUrl,
                            onValueChange = { baseUrl = it },
                            label = { Text("Server URL") },
                            supportingText = { Text("Default emulator address: ${AppSettings.DEFAULT_API}") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            Column(Modifier.height(20.dp)) {}
            Text(
                "Photos and inspections are stored encrypted on this device. Work can continue underground without a connection.",
                color =
                    androidx.compose.ui.graphics
                        .Color(0xFFC6E8D6),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (state.loading) {
            CircularProgressIndicator(Modifier.align(Alignment.Center), color = androidx.compose.ui.graphics.Color.White)
        }
    }
}

package rw.rutongo.mmems.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import rw.rutongo.mmems.android.data.AppSettings
import rw.rutongo.mmems.android.ui.components.FormLabel
import rw.rutongo.mmems.android.ui.components.PrimaryButton
import rw.rutongo.mmems.android.ui.components.SectionDivider
import rw.rutongo.mmems.android.ui.theme.SpecColors
import rw.rutongo.mmems.android.ui.theme.SpecSpacing
import rw.rutongo.mmems.android.ui.theme.SpecTypography

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
            .background(SpecColors.Background)
            .imePadding(),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = SpecSpacing.PhoneMargin, vertical = 48.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("RUTONGO MINES", style = SpecTypography.ScreenTitle)
            Text("MECHANICAL DEPARTMENT", style = SpecTypography.Eyebrow)
            SectionDivider()
            Text("Sign in", style = SpecTypography.SectionTitle)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FormLabel("Username")
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FormLabel("Password")
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    singleLine = true,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility, contentDescription = "Show or hide password")
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            state.loginError?.let { Text(it, color = SpecColors.ErrorText, style = SpecTypography.BodySmall) }
            PrimaryButton(
                text = if (state.signingIn) "Signing in…" else "SIGN IN",
                onClick = { onLogin(username, password, baseUrl) },
                enabled = !state.signingIn,
            )
            TextButton(onClick = { showServer = !showServer }) { Text(if (showServer) "Hide server setting" else "Server setting") }
            if (showServer) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    FormLabel("Server URL")
                    OutlinedTextField(
                        value = baseUrl,
                        onValueChange = { baseUrl = it },
                        supportingText = { Text("Default emulator address: ${AppSettings.DEFAULT_API}") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "Photos and inspections are stored encrypted on this device. Work can continue underground without a connection.",
                style = SpecTypography.BodySmall,
            )
        }
        if (state.loading) {
            CircularProgressIndicator(Modifier.align(Alignment.Center), color = SpecColors.Action)
        }
    }
}

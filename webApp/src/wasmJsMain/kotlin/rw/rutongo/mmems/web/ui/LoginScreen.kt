package rw.rutongo.mmems.web.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import rw.rutongo.mmems.web.WebIntent
import rw.rutongo.mmems.web.WebState
import rw.rutongo.mmems.web.ui.components.ErrorNote

@Composable
fun LoginScreen(
    state: WebState,
    onIntent: (WebIntent) -> Unit,
) {
    AuthBackground {
        Column(modifier = Modifier.fillMaxWidth()) {
            BrandMark()
            Spacer(Modifier.height(36.dp))
            Text("Sign in", style = WebSpecTypography.ScreenTitle)
            Spacer(Modifier.height(8.dp))
            Text(
                "Use your Rutongo MMEMS account. Web access is available to authorised roles only.",
                style = MaterialTheme.typography.bodyMedium,
                color = WebSpecColors.SecondaryText,
            )
            Spacer(Modifier.height(28.dp))
            OutlinedTextField(
                value = state.username,
                onValueChange = { onIntent(WebIntent.UsernameChanged(it)) },
                label = { Text("Username") },
                singleLine = true,
                enabled = !state.loginBusy,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = state.password,
                onValueChange = { onIntent(WebIntent.PasswordChanged(it)) },
                label = { Text("Password") },
                singleLine = true,
                enabled = !state.loginBusy,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onIntent(WebIntent.SubmitLogin) }),
                modifier = Modifier.fillMaxWidth(),
            )
            state.loginError?.let {
                Spacer(Modifier.height(16.dp))
                ErrorNote(it)
            }
            Spacer(Modifier.height(28.dp))
            Button(
                onClick = { onIntent(WebIntent.SubmitLogin) },
                enabled = !state.loginBusy,
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                if (state.loginBusy) {
                    CircularProgressIndicator(modifier = Modifier.height(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Text("Sign in", style = WebSpecTypography.Button)
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "Sessions expire automatically. Refresh uses a secure HttpOnly cookie.",
                style = MaterialTheme.typography.bodySmall,
                color = WebSpecColors.SecondaryText,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
fun ChangePasswordScreen(
    busy: Boolean,
    onIntent: (WebIntent) -> Unit,
    onSignOut: () -> Unit,
) {
    var current by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    AuthBackground {
        Column(modifier = Modifier.fillMaxWidth()) {
            BrandMark()
            Spacer(Modifier.height(36.dp))
            Text("Change your password", style = WebSpecTypography.SectionTitle)
            Spacer(Modifier.height(8.dp))
            Text(
                "Your account requires a new password before you can use the web application.",
                style = MaterialTheme.typography.bodyMedium,
                color = WebSpecColors.SecondaryText,
            )
            Spacer(Modifier.height(28.dp))
            OutlinedTextField(current, { current = it }, label = { Text("Current password") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), enabled = !busy, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(newPassword, { newPassword = it }, label = { Text("New password") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), enabled = !busy, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(confirm, { confirm = it }, label = { Text("Confirm new password") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), enabled = !busy, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(28.dp))
            Button(
                onClick = { onIntent(WebIntent.ChangePassword(current, newPassword, confirm)) },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.height(18.dp), strokeWidth = 2.dp)
                } else {
                    Text("Change password", style = WebSpecTypography.Button)
                }
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onSignOut, modifier = Modifier.fillMaxWidth()) { Text("Sign out") }
        }
    }
}

@Composable
private fun BrandMark() {
    Column {
        Text("RUTONGO MINES", style = WebSpecTypography.SectionTitle)
        Text("MECHANICAL DEPARTMENT", style = WebSpecTypography.Label)
    }
}

@Composable
private fun AuthBackground(content: @Composable () -> Unit) {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(WebSpecColors.Background)
                .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 460.dp),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.Center,
        ) {
            content()
        }
    }
}

package rw.rutongo.mmems.web

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import rw.rutongo.mmems.web.data.WebApi
import rw.rutongo.mmems.web.data.apiBaseUrl
import rw.rutongo.mmems.web.ui.ChangePasswordScreen
import rw.rutongo.mmems.web.ui.HomeShell
import rw.rutongo.mmems.web.ui.LoginScreen
import rw.rutongo.mmems.web.ui.RutongoTheme

@Composable
fun App() {
    val viewModel = remember { WebViewModel(WebApi(apiBaseUrl())) }
    DisposableEffect(viewModel) { onDispose { viewModel.close() } }
    val state by viewModel.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is WebEffect.Message -> snackbar.showSnackbar(effect.text)
                WebEffect.SignedOut -> snackbar.showSnackbar("Signed out.")
            }
        }
    }
    RutongoTheme {
        MaterialTheme {
            Box(modifier = Modifier.fillMaxSize()) {
                when (state.stage) {
                    AuthStage.LOGIN -> LoginScreen(state) { viewModel.onIntent(it) }
                    AuthStage.CHANGE_PASSWORD ->
                        ChangePasswordScreen(
                            busy = state.accountBusy,
                            onIntent = { viewModel.onIntent(it) },
                            onSignOut = { viewModel.onIntent(WebIntent.Logout) },
                        )
                    AuthStage.HOME -> HomeShell(state) { viewModel.onIntent(it) }
                }
                SnackbarHost(
                    hostState = snackbar,
                    modifier = Modifier.align(Alignment.BottomCenter),
                    snackbar = { data -> Snackbar(data) },
                )
            }
        }
    }
}

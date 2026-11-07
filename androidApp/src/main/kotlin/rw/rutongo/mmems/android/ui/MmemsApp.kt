package rw.rutongo.mmems.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import rw.rutongo.mmems.android.AppContainer
import rw.rutongo.mmems.android.ui.theme.MmemsTheme

@Composable
fun MmemsApp(container: AppContainer) {
    val appViewModel: AppViewModel =
        viewModel(
            factory =
                object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T = AppViewModel(container) as T
                },
        )
    val state by appViewModel.state.collectAsStateWithLifecycle()
    MmemsTheme {
        when {
            state.loading ->
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Text("Loading Rutongo MMEMS", style = MaterialTheme.typography.bodyMedium)
                }
            state.me == null -> LoginScreen(state, container.settings.apiBaseUrl, onLogin = appViewModel::login)
            else -> MainScaffold(container, state.me!!, onLogout = appViewModel::logout)
        }
    }
}

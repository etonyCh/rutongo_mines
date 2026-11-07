package rw.rutongo.mmems.android.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import rw.rutongo.mmems.android.AppContainer
import rw.rutongo.mmems.android.data.ApiResult
import rw.rutongo.mmems.android.sync.SyncWorker
import rw.rutongo.mmems.shared.api.MeView

data class AppUiState(
    val loading: Boolean = true,
    val me: MeView? = null,
    val loginError: String? = null,
    val signingIn: Boolean = false,
)

class AppViewModel(
    private val container: AppContainer,
) : ViewModel() {
    private val _state = MutableStateFlow(AppUiState())
    val state: StateFlow<AppUiState> = _state.asStateFlow()

    init {
        bootstrap()
    }

    private fun bootstrap() {
        if (!container.tokens.isLoggedIn()) {
            _state.value = AppUiState(loading = false)
            return
        }
        val cached = container.store.profile()
        viewModelScope.launch {
            when (val result = container.api.me()) {
                is ApiResult.Ok -> {
                    container.store.saveProfile(result.value)
                    _state.value = AppUiState(loading = false, me = result.value)
                }
                is ApiResult.Invalid -> {
                    container.tokens.clear()
                    _state.value = AppUiState(loading = false, loginError = "Your session expired. Sign in again.")
                }
                else ->
                    _state.value =
                        if (cached != null) {
                            AppUiState(loading = false, me = cached)
                        } else {
                            AppUiState(loading = false, loginError = (result as? ApiResult.Retryable)?.message ?: "The server is unreachable.")
                        }
            }
        }
    }

    fun login(
        username: String,
        password: String,
        baseUrl: String,
    ) {
        if (username.isBlank() || password.isBlank()) {
            _state.value = _state.value.copy(loginError = "Enter your username and password.")
            return
        }
        container.settings.apiBaseUrl = baseUrl
        _state.value = _state.value.copy(signingIn = true, loginError = null)
        viewModelScope.launch {
            when (val result = container.api.login(username.trim(), password, container.settings.deviceLabel)) {
                is ApiResult.Ok -> {
                    when (val me = container.api.me()) {
                        is ApiResult.Ok -> {
                            container.store.saveProfile(me.value)
                            _state.value = AppUiState(loading = false, me = me.value)
                            SyncWorker.enqueueNow(container.appContext)
                        }
                        else -> _state.value = AppUiState(loading = false, loginError = "Signed in, but the profile could not be loaded.")
                    }
                }
                is ApiResult.Invalid -> _state.value = AppUiState(loading = false, loginError = result.message)
                else -> _state.value = AppUiState(loading = false, loginError = (result as? ApiResult.Retryable)?.message ?: "Sign-in failed.")
            }
        }
    }

    fun logout() {
        container.tokens.clear()
        container.store.purgeLocalData()
        java.io.File(container.appContext.filesDir, "inspection_photos").deleteRecursively()
        _state.value = AppUiState(loading = false)
    }

    fun dismissError() {
        _state.value = _state.value.copy(loginError = null)
    }
}

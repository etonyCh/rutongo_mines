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
import rw.rutongo.mmems.shared.api.MaintenanceCreateDto
import rw.rutongo.mmems.shared.api.MaintenanceViewDto

data class MaintenanceUiState(
    val refreshing: Boolean = false,
    val message: String? = null,
    val error: String? = null,
)

class MaintenanceViewModel(
    private val container: AppContainer,
) : ViewModel() {
    private val _state = MutableStateFlow(MaintenanceUiState())
    val state: StateFlow<MaintenanceUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        if (_state.value.refreshing) return
        _state.value = _state.value.copy(refreshing = true, error = null)
        viewModelScope.launch {
            val result = container.syncEngine.syncMaintenance()
            _state.value =
                when (result) {
                    is ApiResult.Ok -> MaintenanceUiState(message = "Maintenance data refreshed.")
                    is ApiResult.Invalid -> MaintenanceUiState(error = if (result.code == "FORBIDDEN") null else result.message)
                    is ApiResult.Conflict -> MaintenanceUiState(error = result.message)
                    is ApiResult.Retryable -> MaintenanceUiState(error = result.message)
                }
        }
    }

    fun create(
        equipmentId: String,
        input: MaintenanceCreateDto,
    ) {
        container.store.enqueueMaintenance(equipmentId, input)
        SyncWorker.enqueueNow(container.appContext)
        _state.value = _state.value.copy(message = "Maintenance queued. It will sync automatically.", error = null)
    }

    fun decide(
        record: MaintenanceViewDto,
        action: String,
        comment: String?,
    ) {
        viewModelScope.launch {
            when (
                val result =
                    container.api.transitionMaintenance(
                        maintenanceId = record.id,
                        action = action,
                        version = record.version,
                        comment = comment,
                    )
            ) {
                is ApiResult.Ok -> {
                    container.store.upsertMaintenance(result.value)
                    _state.value = MaintenanceUiState(message = "Maintenance decision saved.")
                }
                is ApiResult.Invalid -> _state.value = _state.value.copy(error = "${result.code}: ${result.message}")
                is ApiResult.Conflict -> _state.value = _state.value.copy(error = result.message)
                is ApiResult.Retryable -> _state.value = _state.value.copy(error = result.message)
            }
        }
    }

    fun clearMessage() {
        _state.value = _state.value.copy(message = null, error = null)
    }
}

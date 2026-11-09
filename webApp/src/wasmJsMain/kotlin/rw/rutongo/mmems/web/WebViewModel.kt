@file:Suppress("TooManyFunctions", "LongMethod")
@file:OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)

package rw.rutongo.mmems.web

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import rw.rutongo.mmems.shared.api.MeView
import rw.rutongo.mmems.shared.web.WebTab
import rw.rutongo.mmems.shared.web.dashboardKindFor
import rw.rutongo.mmems.shared.web.defaultTab
import rw.rutongo.mmems.shared.web.isVisibleFor
import rw.rutongo.mmems.web.data.WebApi
import rw.rutongo.mmems.web.data.WebResult
import rw.rutongo.mmems.web.data.isAuthFailure
import kotlin.io.encoding.Base64

class WebViewModel(
    private val api: WebApi,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(WebState())
    val state = _state.asStateFlow()
    private val _effects = Channel<WebEffect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    init {
        restoreSession()
    }

    fun onIntent(intent: WebIntent) {
        when (intent) {
            is WebIntent.UsernameChanged -> _state.value = _state.value.copy(username = intent.value, loginError = null)
            is WebIntent.PasswordChanged -> _state.value = _state.value.copy(password = intent.value, loginError = null)
            WebIntent.SubmitLogin -> login()
            WebIntent.Logout -> logout()
            is WebIntent.SelectTab -> selectTab(intent.tab)
            is WebIntent.SelectSite -> selectSite(intent.siteId)
            WebIntent.Refresh -> refreshCurrent()
            is WebIntent.DownloadReportPdf -> downloadPdf(intent.reportId, intent.versionNo)
            is WebIntent.ChangePassword -> changePassword(intent.current, intent.new, intent.confirm)
            is WebIntent.CreateUser -> createUser(intent)
            is WebIntent.SetUserActive -> setUserActive(intent.id, intent.active)
            is WebIntent.UpdateNotificationRule -> updateRule(intent.type, intent.update)
            is WebIntent.UploadImport -> uploadImport(intent.profileId, intent.fileName, intent.base64)
            WebIntent.CommitImport -> commitImport()
            WebIntent.ClearImport -> _state.value = _state.value.copy(importPreview = null, importFileName = null)
            is WebIntent.DownloadExport -> downloadExport(intent.dataset, intent.format)
        }
    }

    fun close() {
        scope.cancel()
    }

    private fun restoreSession() {
        scope.launch {
            when (val result = api.me()) {
                is WebResult.Success -> enterHome(result.value)
                is WebResult.Failure -> _state.value = _state.value.copy(stage = AuthStage.LOGIN, loginBusy = false)
            }
        }
    }

    private fun login() {
        val current = _state.value
        if (current.loginBusy) return
        if (current.username.isBlank() || current.password.isBlank()) {
            _state.value = current.copy(loginError = "Enter your username and password.")
            return
        }
        _state.value = current.copy(loginBusy = true, loginError = null)
        scope.launch {
            when (val result = api.login(current.username, current.password)) {
                is WebResult.Failure -> _state.value = _state.value.copy(loginBusy = false, loginError = result.message)
                is WebResult.Success -> {
                    if (result.value.mustChangePassword) {
                        _state.value = _state.value.copy(stage = AuthStage.CHANGE_PASSWORD, loginBusy = false, password = "")
                    } else {
                        when (val me = api.me()) {
                            is WebResult.Success -> enterHome(me.value)
                            is WebResult.Failure -> _state.value = _state.value.copy(loginBusy = false, loginError = me.message)
                        }
                    }
                }
            }
        }
    }

    private fun enterHome(me: MeView) {
        val tab = defaultTab(me.role)
        _state.value =
            _state.value.copy(
                stage = AuthStage.HOME,
                loginBusy = false,
                loginError = null,
                password = "",
                me = me,
                tab = tab,
            )
        loadSites()
        loadTab(tab)
    }

    private fun logout() {
        scope.launch {
            api.logout()
            _state.value = WebState()
            _effects.send(WebEffect.SignedOut)
        }
    }

    private fun changePassword(
        current: String,
        new: String,
        confirm: String,
    ) {
        val state = _state.value
        if (state.accountBusy) return
        if (new != confirm) {
            emitMessage("The new password confirmation does not match.")
            return
        }
        _state.value = state.copy(accountBusy = true)
        scope.launch {
            when (val result = api.changePassword(current, new)) {
                is WebResult.Failure -> {
                    _state.value = _state.value.copy(accountBusy = false)
                    emitMessage(result.message)
                }
                is WebResult.Success -> {
                    _state.value = _state.value.copy(accountBusy = false)
                    emitMessage("Password changed.")
                    restoreSession()
                }
            }
        }
    }

    private fun selectTab(tab: WebTab) {
        val me = _state.value.me ?: return
        if (!tab.isVisibleFor(me.role)) return
        if (_state.value.tab == tab) return
        _state.value = _state.value.copy(tab = tab)
        loadTab(tab)
    }

    private fun selectSite(siteId: String?) {
        if (_state.value.selectedSiteId == siteId) return
        _state.value = _state.value.copy(selectedSiteId = siteId)
        refreshCurrent()
    }

    private fun refreshCurrent() {
        val tab = _state.value.tab ?: return
        loadTab(tab)
    }

    private fun loadSites() {
        scope.launch {
            _state.value = _state.value.copy(sitesBusy = true)
            when (val result = api.sites()) {
                is WebResult.Success -> {
                    val selected = _state.value.selectedSiteId
                    _state.value =
                        _state.value.copy(
                            sites = result.value,
                            sitesBusy = false,
                            selectedSiteId = selected?.takeIf { id -> result.value.any { it.id == id } },
                        )
                }
                is WebResult.Failure -> {
                    _state.value = _state.value.copy(sitesBusy = false)
                    handleFailure(result)
                }
            }
        }
    }

    private fun loadTab(tab: WebTab) {
        when (tab) {
            WebTab.DASHBOARD -> loadDashboard()
            WebTab.SITES -> {
                loadSites()
                loadDashboard()
            }
            WebTab.EQUIPMENT -> loadEquipment()
            WebTab.REPORTS -> loadReports()
            WebTab.OPERATIONS -> loadOperations()
            WebTab.PEOPLE -> {
                loadUsers()
                loadAudit()
            }
            WebTab.CONFIGURATION -> loadConfiguration()
            WebTab.MORE -> Unit
        }
    }

    private fun loadDashboard() {
        val state = _state.value
        val me = state.me ?: return
        scope.launch {
            _state.value = _state.value.copy(dashboardBusy = true)
            when (val result = api.dashboard(dashboardKindFor(me.role).name, state.selectedSiteId)) {
                is WebResult.Success -> _state.value = _state.value.copy(dashboard = result.value, dashboardBusy = false)
                is WebResult.Failure -> {
                    _state.value = _state.value.copy(dashboardBusy = false)
                    handleFailure(result)
                }
            }
        }
    }

    private fun loadEquipment() {
        val siteId = _state.value.selectedSiteId
        scope.launch {
            _state.value = _state.value.copy(equipmentBusy = true)
            when (val result = api.equipment()) {
                is WebResult.Success -> {
                    val items = result.value.filter { siteId == null || it.siteId == siteId }
                    _state.value = _state.value.copy(equipment = items, equipmentBusy = false)
                }
                is WebResult.Failure -> {
                    _state.value = _state.value.copy(equipmentBusy = false)
                    handleFailure(result)
                }
            }
        }
    }

    private fun loadReports() {
        val siteId = _state.value.selectedSiteId
        scope.launch {
            _state.value = _state.value.copy(reportsBusy = true)
            when (val result = api.reports(siteId)) {
                is WebResult.Success -> _state.value = _state.value.copy(reports = result.value, reportsBusy = false)
                is WebResult.Failure -> {
                    _state.value = _state.value.copy(reportsBusy = false)
                    handleFailure(result)
                }
            }
        }
    }

    private fun loadUsers() {
        scope.launch {
            _state.value = _state.value.copy(usersBusy = true)
            when (val result = api.users()) {
                is WebResult.Success -> _state.value = _state.value.copy(users = result.value, usersBusy = false)
                is WebResult.Failure -> {
                    _state.value = _state.value.copy(usersBusy = false)
                    handleFailure(result)
                }
            }
        }
    }

    private fun loadAudit() {
        scope.launch {
            _state.value = _state.value.copy(auditBusy = true)
            when (val result = api.auditEvents()) {
                is WebResult.Success -> _state.value = _state.value.copy(auditEvents = result.value, auditBusy = false)
                is WebResult.Failure -> {
                    _state.value = _state.value.copy(auditBusy = false)
                    handleFailure(result)
                }
            }
        }
    }

    private fun loadOperations() {
        scope.launch {
            _state.value = _state.value.copy(operationsBusy = true)
            when (val profiles = api.importProfiles()) {
                is WebResult.Failure -> {
                    _state.value = _state.value.copy(operationsBusy = false)
                    handleFailure(profiles)
                }
                is WebResult.Success -> {
                    when (val exports = api.exportDatasets()) {
                        is WebResult.Success ->
                            _state.value =
                                _state.value.copy(
                                    profiles = profiles.value,
                                    exports = exports.value,
                                    operationsBusy = false,
                                )
                        is WebResult.Failure -> {
                            _state.value = _state.value.copy(profiles = profiles.value, operationsBusy = false)
                            handleFailure(exports)
                        }
                    }
                }
            }
        }
    }

    private fun loadConfiguration() {
        scope.launch {
            _state.value = _state.value.copy(configBusy = true)
            when (val result = api.notificationRules()) {
                is WebResult.Success -> _state.value = _state.value.copy(notificationRules = result.value, configBusy = false)
                is WebResult.Failure -> {
                    _state.value = _state.value.copy(configBusy = false)
                    handleFailure(result)
                }
            }
        }
    }

    private fun createUser(intent: WebIntent.CreateUser) {
        scope.launch {
            when (val result = api.createUser(intent.username, intent.fullName, intent.employeeId, intent.role, intent.initialPassword, intent.siteIds)) {
                is WebResult.Success -> {
                    emitMessage("User ${result.value.username} created.")
                    loadUsers()
                }
                is WebResult.Failure -> emitMessage(result.message)
            }
        }
    }

    private fun setUserActive(
        id: String,
        active: Boolean,
    ) {
        scope.launch {
            when (val result = api.setUserActive(id, active)) {
                is WebResult.Success -> {
                    emitMessage(if (active) "User activated." else "User deactivated.")
                    loadUsers()
                }
                is WebResult.Failure -> emitMessage(result.message)
            }
        }
    }

    private fun updateRule(
        type: String,
        update: rw.rutongo.mmems.shared.api.NotificationRuleUpdateDto,
    ) {
        scope.launch {
            when (val result = api.updateNotificationRule(type, update)) {
                is WebResult.Success -> {
                    val rules =
                        _state.value.notificationRules
                            .orEmpty()
                            .map { if (it.type == result.value.type) result.value else it }
                    _state.value = _state.value.copy(notificationRules = rules)
                    emitMessage("Notification rule updated.")
                }
                is WebResult.Failure -> emitMessage(result.message)
            }
        }
    }

    private fun uploadImport(
        profileId: String,
        fileName: String,
        base64: String,
    ) {
        scope.launch {
            _state.value = _state.value.copy(importBusy = true, importFileName = fileName)
            when (val result = api.importPreview(profileId, fileName, Base64.decode(base64))) {
                is WebResult.Success -> _state.value = _state.value.copy(importPreview = result.value, importBusy = false)
                is WebResult.Failure -> {
                    _state.value = _state.value.copy(importBusy = false)
                    emitMessage(result.message)
                }
            }
        }
    }

    private fun commitImport() {
        val preview = _state.value.importPreview ?: return
        if (_state.value.importBusy) return
        scope.launch {
            _state.value = _state.value.copy(importBusy = true)
            when (val result = api.commitImport(preview.batch.id, false)) {
                is WebResult.Success -> {
                    _state.value =
                        _state.value.copy(
                            importPreview = preview.copy(batch = result.value),
                            importBusy = false,
                        )
                    emitMessage("Import committed: ${result.value.newCount} new, ${result.value.updatedCount} updated, ${result.value.rejectedCount} rejected.")
                }
                is WebResult.Failure -> {
                    _state.value = _state.value.copy(importBusy = false)
                    emitMessage(result.message)
                }
            }
        }
    }

    private fun downloadPdf(
        reportId: String,
        versionNo: Int,
    ) {
        scope.launch {
            when (val result = api.downloadReportPdf(reportId, versionNo)) {
                is WebResult.Success -> emitMessage("PDF download started.")
                is WebResult.Failure -> emitMessage(result.message)
            }
        }
    }

    private fun downloadExport(
        dataset: String,
        format: String,
    ) {
        scope.launch {
            when (val result = api.downloadExport(dataset, format, _state.value.selectedSiteId)) {
                is WebResult.Success -> emitMessage("$dataset export started.")
                is WebResult.Failure -> emitMessage(result.message)
            }
        }
    }

    private fun handleFailure(failure: WebResult.Failure) {
        if (failure.isAuthFailure()) {
            _state.value = WebState()
            emitMessage("Your session has ended. Sign in again.")
        } else {
            emitMessage(failure.message)
        }
    }

    private fun emitMessage(text: String) {
        scope.launch { _effects.send(WebEffect.Message(text)) }
    }
}

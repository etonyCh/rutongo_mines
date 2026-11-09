package rw.rutongo.mmems.web

import rw.rutongo.mmems.shared.api.DashboardDto
import rw.rutongo.mmems.shared.api.EquipmentDto
import rw.rutongo.mmems.shared.api.ExportDatasetDto
import rw.rutongo.mmems.shared.api.MeView
import rw.rutongo.mmems.shared.api.NotificationRuleDto
import rw.rutongo.mmems.shared.api.NotificationRuleUpdateDto
import rw.rutongo.mmems.shared.api.ReportViewDto
import rw.rutongo.mmems.shared.api.SiteDto
import rw.rutongo.mmems.shared.web.WebTab
import rw.rutongo.mmems.web.data.AuditEventRow
import rw.rutongo.mmems.web.data.ImportPreview
import rw.rutongo.mmems.web.data.ImportProfile
import rw.rutongo.mmems.web.data.WebUser

enum class AuthStage { LOGIN, CHANGE_PASSWORD, HOME }

data class WebState(
    val stage: AuthStage = AuthStage.LOGIN,
    val username: String = "",
    val password: String = "",
    val loginBusy: Boolean = false,
    val loginError: String? = null,
    val me: MeView? = null,
    val tab: WebTab? = null,
    val sites: List<SiteDto> = emptyList(),
    val selectedSiteId: String? = null,
    val sitesBusy: Boolean = false,
    val dashboard: DashboardDto? = null,
    val dashboardBusy: Boolean = false,
    val equipment: List<EquipmentDto>? = null,
    val equipmentBusy: Boolean = false,
    val reports: List<ReportViewDto>? = null,
    val reportsBusy: Boolean = false,
    val users: List<WebUser>? = null,
    val usersBusy: Boolean = false,
    val auditEvents: List<AuditEventRow>? = null,
    val auditBusy: Boolean = false,
    val profiles: List<ImportProfile>? = null,
    val exports: List<ExportDatasetDto>? = null,
    val operationsBusy: Boolean = false,
    val importPreview: ImportPreview? = null,
    val importFileName: String? = null,
    val importBusy: Boolean = false,
    val notificationRules: List<NotificationRuleDto>? = null,
    val configBusy: Boolean = false,
    val accountBusy: Boolean = false,
) {
    val siteName: String?
        get() = selectedSiteId?.let { id -> sites.firstOrNull { it.id == id }?.name }
}

sealed interface WebIntent {
    data class UsernameChanged(
        val value: String,
    ) : WebIntent

    data class PasswordChanged(
        val value: String,
    ) : WebIntent

    data object SubmitLogin : WebIntent

    data object Logout : WebIntent

    data class SelectTab(
        val tab: WebTab,
    ) : WebIntent

    data class SelectSite(
        val siteId: String?,
    ) : WebIntent

    data object Refresh : WebIntent

    data class DownloadReportPdf(
        val reportId: String,
        val versionNo: Int,
    ) : WebIntent

    data class ChangePassword(
        val current: String,
        val new: String,
        val confirm: String,
    ) : WebIntent

    data class CreateUser(
        val username: String,
        val fullName: String,
        val employeeId: String,
        val role: String,
        val initialPassword: String,
        val siteIds: List<String>,
    ) : WebIntent

    data class SetUserActive(
        val id: String,
        val active: Boolean,
    ) : WebIntent

    data class UpdateNotificationRule(
        val type: String,
        val update: NotificationRuleUpdateDto,
    ) : WebIntent

    data class UploadImport(
        val profileId: String,
        val fileName: String,
        val base64: String,
    ) : WebIntent

    data object CommitImport : WebIntent

    data object ClearImport : WebIntent

    data class DownloadExport(
        val dataset: String,
        val format: String,
    ) : WebIntent
}

sealed interface WebEffect {
    data class Message(
        val text: String,
    ) : WebEffect

    data object SignedOut : WebEffect
}

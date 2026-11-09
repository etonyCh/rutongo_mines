package rw.rutongo.mmems.shared.web

import rw.rutongo.mmems.shared.api.DashboardDto
import rw.rutongo.mmems.shared.api.DashboardSiteMetricsDto
import rw.rutongo.mmems.shared.enums.DashboardKind
import rw.rutongo.mmems.shared.enums.Role

enum class WebTab(
    val label: String,
) {
    DASHBOARD("Dashboard"),
    SITES("Sites"),
    EQUIPMENT("Equipment"),
    REPORTS("Reports"),
    OPERATIONS("Operations"),
    PEOPLE("People"),
    CONFIGURATION("Configuration"),
    MORE("More"),
}

fun tabsFor(role: Role): List<WebTab> =
    when (role) {
        Role.MECHANIC -> listOf(WebTab.DASHBOARD, WebTab.EQUIPMENT, WebTab.MORE)
        Role.SUPERVISOR -> listOf(WebTab.DASHBOARD, WebTab.EQUIPMENT, WebTab.REPORTS, WebTab.MORE)
        Role.MANAGER, Role.GENERAL_MANAGER -> listOf(WebTab.DASHBOARD, WebTab.SITES, WebTab.EQUIPMENT, WebTab.REPORTS, WebTab.MORE)
        Role.ADMINISTRATOR -> listOf(WebTab.DASHBOARD, WebTab.OPERATIONS, WebTab.PEOPLE, WebTab.CONFIGURATION, WebTab.MORE)
    }

fun defaultTab(role: Role): WebTab = tabsFor(role).first()

fun dashboardKindFor(role: Role): DashboardKind =
    when (role) {
        Role.MECHANIC -> DashboardKind.MECHANIC
        Role.SUPERVISOR -> DashboardKind.SUPERVISOR
        Role.MANAGER, Role.ADMINISTRATOR -> DashboardKind.MANAGER
        Role.GENERAL_MANAGER -> DashboardKind.EXECUTIVE
    }

fun WebTab.isVisibleFor(role: Role): Boolean = this in tabsFor(role)

data class SiteComparisonRow(
    val siteId: String,
    val siteCode: String,
    val siteName: String,
    val totalEquipment: Int,
    val operational: Int,
    val down: Int,
    val underRepair: Int,
    val openBreakdowns: Int,
    val missingInspections: Int,
    val approvedReports: Int,
    val availabilityPercent: Double,
)

fun DashboardDto.comparisonRows(): List<SiteComparisonRow> =
    sites
        .map { it.toComparisonRow() }
        .sortedWith(compareBy({ it.siteCode }, { it.siteName }))

fun DashboardSiteMetricsDto.toComparisonRow(): SiteComparisonRow =
    SiteComparisonRow(
        siteId = siteId,
        siteCode = siteCode,
        siteName = siteName,
        totalEquipment = equipment.total,
        operational = equipment.operational,
        down = equipment.down,
        underRepair = equipment.underRepair,
        openBreakdowns = breakdowns.open,
        missingInspections = inspections.missing,
        approvedReports = reports.approved,
        availabilityPercent = if (equipment.total == 0) 0.0 else (equipment.operational.toDouble() * 100.0) / equipment.total,
    )

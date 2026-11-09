package rw.rutongo.mmems.shared.web

import rw.rutongo.mmems.shared.api.DashboardBreakdownMetricsDto
import rw.rutongo.mmems.shared.api.DashboardDto
import rw.rutongo.mmems.shared.api.DashboardEquipmentMetricsDto
import rw.rutongo.mmems.shared.api.DashboardInspectionMetricsDto
import rw.rutongo.mmems.shared.api.DashboardReportMetricsDto
import rw.rutongo.mmems.shared.api.DashboardSiteMetricsDto
import rw.rutongo.mmems.shared.enums.DashboardKind
import rw.rutongo.mmems.shared.enums.Role
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebNavigationTest {
    @Test
    fun `manager navigation matches the specification`() {
        assertEquals(
            listOf(WebTab.DASHBOARD, WebTab.SITES, WebTab.EQUIPMENT, WebTab.REPORTS, WebTab.MORE),
            tabsFor(Role.MANAGER),
        )
        assertEquals(
            listOf(WebTab.DASHBOARD, WebTab.OPERATIONS, WebTab.PEOPLE, WebTab.CONFIGURATION, WebTab.MORE),
            tabsFor(Role.ADMINISTRATOR),
        )
        assertTrue(WebTab.SITES.isVisibleFor(Role.GENERAL_MANAGER))
        assertFalse(WebTab.CONFIGURATION.isVisibleFor(Role.MANAGER))
    }

    @Test
    fun `general manager receives the executive dashboard`() {
        assertEquals(DashboardKind.EXECUTIVE, dashboardKindFor(Role.GENERAL_MANAGER))
        assertEquals(DashboardKind.MANAGER, dashboardKindFor(Role.MANAGER))
    }

    @Test
    fun `site comparison sorts sites and computes availability`() {
        val dashboard =
            DashboardDto(
                kind = DashboardKind.MANAGER,
                role = Role.MANAGER,
                from = "2026-11-01T00:00:00Z",
                to = "2026-11-08T00:00:00Z",
                generatedAt = "2026-11-08T00:00:00Z",
                sites =
                    listOf(
                        site(
                            code = "B",
                            total = 4,
                            operational = 3,
                            down = 1,
                            missing = 2,
                            approved = 1,
                        ),
                        site(
                            code = "A",
                            total = 0,
                        ),
                    ),
            )
        val rows = dashboard.comparisonRows()
        assertEquals(listOf("A", "B"), rows.map { it.siteCode })
        assertEquals(0.0, rows[0].availabilityPercent)
        assertEquals(75.0, rows[1].availabilityPercent)
        assertEquals(2, rows[1].missingInspections)
        assertEquals(1, rows[1].approvedReports)
    }

    @Suppress("LongParameterList")
    private fun site(
        code: String,
        total: Int,
        operational: Int = 0,
        down: Int = 0,
        missing: Int = 0,
        approved: Int = 0,
    ): DashboardSiteMetricsDto =
        DashboardSiteMetricsDto(
            siteId = "site-$code",
            siteCode = code,
            siteName = "Site $code",
            equipment = DashboardEquipmentMetricsDto(total = total, operational = operational, down = down),
            inspections = DashboardInspectionMetricsDto(missing = missing),
            breakdowns = DashboardBreakdownMetricsDto(open = down),
            reports = DashboardReportMetricsDto(approved = approved),
        )
}

package rw.rutongo.mmems.identity

import org.junit.jupiter.api.Test
import rw.rutongo.mmems.shared.enums.Role
import kotlin.test.assertEquals

class RolePermissionsTest {
    @Test
    fun `role permission matrix matches the sprint one policy`() {
        val expected =
            mapOf(
                Role.MECHANIC to setOf("EQUIPMENT_VIEW", "INSPECTION_SUBMIT", "STATUS_UPDATE", "BREAKDOWN_MANAGE", "MAINTENANCE_RECORD", "PART_REQUEST", "REPORT_VIEW_OWN_SITE"),
                Role.SUPERVISOR to setOf("EQUIPMENT_VIEW", "STATUS_UPDATE", "STATUS_OVERRIDE", "INSPECTION_REVIEW", "BREAKDOWN_MANAGE", "MAINTENANCE_RECORD", "MAINTENANCE_VERIFY", "PART_REQUEST", "PART_APPROVE", "PART_ISSUE", "PERSONNEL_VIEW", "REPORT_PREPARE", "REPORT_VIEW_OWN_SITE"),
                Role.MANAGER to setOf("EQUIPMENT_VIEW", "PART_APPROVE", "PART_ISSUE", "PERSONNEL_VIEW", "STATUS_OVERRIDE", "INSPECTION_REVIEW", "REPORT_APPROVE", "REPORT_VIEW_APPROVED", "MAINTENANCE_VIEW", "COST_VIEW", "AUDIT_VIEW"),
                Role.GENERAL_MANAGER to setOf("EQUIPMENT_VIEW", "PERSONNEL_VIEW", "REPORT_VIEW_APPROVED", "MAINTENANCE_VIEW", "COST_VIEW"),
                Role.ADMINISTRATOR to setOf("EQUIPMENT_VIEW", "USER_MANAGE", "CONFIG_MANAGE", "IMPORT_RUN", "CHECKLIST_MANAGE", "MAINTENANCE_VIEW", "PART_MANAGE", "PERSONNEL_VIEW", "PERSONNEL_MANAGE", "AUDIT_VIEW"),
            )
        val allPermissions = expected.values.flatten().toSet()

        Role.entries.forEach { role ->
            val actual = allPermissions.filterTo(mutableSetOf()) { RolePermissions.allows(role, it) }
            assertEquals(expected.getValue(role), actual, "Permission mismatch for $role")
        }
    }
}

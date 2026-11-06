package rw.rutongo.mmems.modules.status.domain

import rw.rutongo.mmems.shared.enums.OperationalStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StatusPolicyTest {
    @Test fun `down requires a problem`() {
        assertEquals("problem", StatusPolicy.validate(StatusChangeInput(OperationalStatus.DOWN, null, null)).single().field)
    }

    @Test fun `under repair requires a problem and action`() {
        assertEquals(setOf("problem", "actionTaken"), StatusPolicy.validate(StatusChangeInput(OperationalStatus.UNDER_REPAIR, null, null)).map { it.field }.toSet())
    }

    @Test fun `under repair accepts a problem and action`() {
        assertTrue(StatusPolicy.validate(StatusChangeInput(OperationalStatus.UNDER_REPAIR, "Pump failure", "Replaced seal")).isEmpty())
    }

    @Test fun `operational allows an empty problem and remains the only operational status`() {
        assertTrue(StatusPolicy.validate(StatusChangeInput(OperationalStatus.OPERATIONAL, null, null)).isEmpty())
        assertEquals(EquipmentCondition.OPERATIONAL_WITH_DEFECTS, StatusPolicy.condition(OperationalStatus.OPERATIONAL, 1))
    }

    @Test fun `condition is derived without adding a fourth status`() {
        assertEquals(EquipmentCondition.NOT_RECORDED, StatusPolicy.condition(null, 0))
        assertEquals(EquipmentCondition.DOWN, StatusPolicy.condition(OperationalStatus.DOWN, 3))
        assertEquals(EquipmentCondition.UNDER_REPAIR, StatusPolicy.condition(OperationalStatus.UNDER_REPAIR, 1))
        assertEquals(EquipmentCondition.OPERATIONAL, StatusPolicy.condition(OperationalStatus.OPERATIONAL, 0))
        assertEquals(setOf("OPERATIONAL", "UNDER_REPAIR", "DOWN"), OperationalStatus.entries.map { it.name }.toSet())
    }
}

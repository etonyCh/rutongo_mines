package rw.rutongo.mmems.modules.status.domain

import rw.rutongo.mmems.shared.enums.OperationalStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StatusPolicyTest {
    @Test fun `down requires a problem`() {
        assertEquals("problem", StatusPolicy.validate(StatusChangeInput(OperationalStatus.DOWN,null,null)).single().field)
    }
    @Test fun `under repair requires a problem and action`() {
        assertEquals(setOf("problem","actionTaken"), StatusPolicy.validate(StatusChangeInput(OperationalStatus.UNDER_REPAIR,null,null)).map { it.field }.toSet())
    }
    @Test fun `operational allows an empty problem and remains the only operational status`() {
        assertTrue(StatusPolicy.validate(StatusChangeInput(OperationalStatus.OPERATIONAL,null,null)).isEmpty())
        assertEquals(EquipmentCondition.OPERATIONAL_WITH_DEFECTS,StatusPolicy.condition(OperationalStatus.OPERATIONAL,1))
    }
}

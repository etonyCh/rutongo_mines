package rw.rutongo.mmems.modules.maintenance.domain

import rw.rutongo.mmems.shared.enums.MaintenanceVerificationStatus
import rw.rutongo.mmems.shared.enums.ServiceStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServiceDueTest {
    @Test
    fun `document example uses the last verified service and reports 80 hours remaining`() {
        val result = serviceDue(current = 1920.0, lastVerified = 1750.0, interval = 250)

        assertEquals(2000.0, result.nextDue)
        assertEquals(80.0, result.remaining)
        assertEquals(ServiceStatus.OK, result.status)
    }

    @Test
    fun `due soon starts at the threshold and overdue starts at the due hours`() {
        assertEquals(ServiceStatus.DUE_SOON, serviceDue(1960.0, 1750.0, 250, dueSoon = 50.0).status)
        assertEquals(ServiceStatus.OVERDUE, serviceDue(2000.0, 1750.0, 250).status)
    }

    @Test
    fun `missing readings and missing verified services are never guessed`() {
        val noVerified = serviceDue(1920.0, null, 250)
        assertEquals(ServiceStatus.NO_VERIFIED_SERVICE, noVerified.status)
        assertNull(noVerified.nextDue)
        assertNull(noVerified.remaining)

        val notRecorded = serviceDue(null, 1750.0, 250)
        assertEquals(ServiceStatus.NOT_RECORDED, notRecorded.status)
        assertNull(notRecorded.nextDue)
        assertNull(notRecorded.remaining)
    }

    @Test
    fun `maintenance verification follows the decision workflow`() {
        assertTrue(
            MaintenancePolicy.canTransition(
                MaintenanceVerificationStatus.PENDING_VERIFICATION,
                MaintenanceVerificationStatus.VERIFIED,
            ),
        )
        assertFalse(
            MaintenancePolicy.canTransition(
                MaintenanceVerificationStatus.VERIFIED,
                MaintenanceVerificationStatus.REJECTED,
            ),
        )
    }
}

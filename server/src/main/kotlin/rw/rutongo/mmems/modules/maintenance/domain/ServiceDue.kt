package rw.rutongo.mmems.modules.maintenance.domain

import rw.rutongo.mmems.shared.enums.MaintenanceVerificationStatus
import rw.rutongo.mmems.shared.enums.ServiceStatus

data class ServiceDue(
    val current: Double?,
    val lastVerified: Double?,
    val nextDue: Double?,
    val remaining: Double?,
    val status: ServiceStatus,
)

fun serviceDue(
    current: Double?,
    lastVerified: Double?,
    interval: Int,
    dueSoon: Double = 50.0,
): ServiceDue {
    require(interval > 0) { "A service interval must be positive." }
    require(dueSoon >= 0.0) { "The due-soon threshold cannot be negative." }
    return when {
        current == null -> ServiceDue(null, lastVerified, null, null, ServiceStatus.NOT_RECORDED)
        lastVerified == null -> ServiceDue(current, null, null, null, ServiceStatus.NO_VERIFIED_SERVICE)
        else -> {
            val next = lastVerified + interval
            val remaining = next - current
            ServiceDue(
                current = current,
                lastVerified = lastVerified,
                nextDue = next,
                remaining = remaining,
                status =
                    when {
                        remaining <= 0.0 -> ServiceStatus.OVERDUE
                        remaining <= dueSoon -> ServiceStatus.DUE_SOON
                        else -> ServiceStatus.OK
                    },
            )
        }
    }
}

object MaintenancePolicy {
    private val transitions =
        mapOf(
            MaintenanceVerificationStatus.DRAFT to setOf(MaintenanceVerificationStatus.PENDING_VERIFICATION),
            MaintenanceVerificationStatus.PENDING_VERIFICATION to
                setOf(
                    MaintenanceVerificationStatus.VERIFIED,
                    MaintenanceVerificationStatus.REJECTED,
                    MaintenanceVerificationStatus.CORRECTION_REQUESTED,
                ),
            MaintenanceVerificationStatus.CORRECTION_REQUESTED to
                setOf(
                    MaintenanceVerificationStatus.VERIFIED,
                    MaintenanceVerificationStatus.REJECTED,
                    MaintenanceVerificationStatus.PENDING_VERIFICATION,
                ),
            MaintenanceVerificationStatus.VERIFIED to emptySet(),
            MaintenanceVerificationStatus.REJECTED to emptySet(),
        )

    fun canTransition(
        from: MaintenanceVerificationStatus,
        to: MaintenanceVerificationStatus,
    ): Boolean = to in transitions.getValue(from)
}

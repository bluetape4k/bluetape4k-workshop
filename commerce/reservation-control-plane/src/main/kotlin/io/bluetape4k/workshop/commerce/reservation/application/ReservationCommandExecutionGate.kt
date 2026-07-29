package io.bluetape4k.workshop.commerce.reservation.application

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.commerce.reservation.redis.AdmissionOutcome
import io.bluetape4k.workshop.commerce.reservation.redis.InFlightCommandSuppressor
import io.bluetape4k.workshop.commerce.reservation.redis.ReservationAdmissionGate
import io.bluetape4k.workshop.commerce.reservation.redis.SuppressionOutcome

/**
 * PostgreSQL idempotency 전에 local/Redis admission과 짧은 duplicate suppression을 적용합니다.
 * 모든 durable command outcome은 여전히 PostgreSQL이 소유합니다.
 */
internal class ReservationCommandExecutionGate(
    private val admission: ReservationAdmissionGate,
    private val suppression: InFlightCommandSuppressor,
    private val credentials: ReservationCredentialService,
    private val tenantId: String,
) {
    fun <T> execute(
        operation: String,
        rawIdempotencyKey: String,
        action: () -> T,
    ): T {
        val opaqueCommandId = credentials.idempotencyDigest(tenantId, operation, rawIdempotencyKey).take(24)
        return when (
            val admissionOutcome =
                admission.execute {
                    when (val suppressionOutcome = suppression.execute(opaqueCommandId, action)) {
                        is SuppressionOutcome.Executed -> suppressionOutcome.value
                        SuppressionOutcome.Suppressed -> throw ReservationCommandException(
                            "COMMAND_IN_PROGRESS",
                            null,
                            true
                        )
                    }
                }
        ) {
            is AdmissionOutcome.Executed -> {
                admissionOutcome.value.also {
                    log.debug {
                        "reservation_command_gate_completed operation=$operation mode=${admissionOutcome.mode}"
                    }
                }
            }
            is AdmissionOutcome.Rejected -> {
                throw ReservationCommandException("ADMISSION_REJECTED", null, true)
            }
        }
    }

    companion object : KLogging()
}

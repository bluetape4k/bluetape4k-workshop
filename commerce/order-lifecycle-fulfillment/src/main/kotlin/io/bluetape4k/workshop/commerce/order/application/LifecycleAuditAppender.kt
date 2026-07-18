package io.bluetape4k.workshop.commerce.order.application

import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.commerce.order.domain.AggregateType
import io.bluetape4k.workshop.commerce.order.persistence.LifecycleAuditRecord
import io.bluetape4k.workshop.commerce.order.persistence.LifecycleAuditRepository
import org.springframework.stereotype.Component
import java.util.UUID

@Component
internal class LifecycleAuditAppender(
    private val repository: LifecycleAuditRepository,
) {
    fun append(
        orderId: UUID,
        aggregateType: AggregateType,
        aggregateId: UUID,
        revision: Long,
        from: Enum<*>?,
        to: Enum<*>,
        reason: String? = null,
        actor: String,
    ) = repository
        .append(
            LifecycleAuditRecord(
                eventId = Uuid.V7.nextId(),
                orderId = orderId,
                aggregateType = aggregateType,
                aggregateId = aggregateId,
                revision = revision,
                fromStatus = from?.name,
                toStatus = to.name,
                reasonCode = reason?.take(80),
                actorType = actor.take(40)
            )
        ).also {
            log.debug {
                "lifecycle_transition orderId=$orderId aggregateType=$aggregateType aggregateId=$aggregateId " +
                    "revision=$revision from=${from?.name ?: "NONE"} to=${to.name} actor=$actor"
            }
        }

    companion object : KLogging()
}

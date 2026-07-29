package io.bluetape4k.workshop.optimization.planning.persistence

import io.bluetape4k.exposed.core.auditable.AuditableLongIdTable
import io.bluetape4k.workshop.optimization.planning.domain.PlanningProvider
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

internal object PlanningCallbackInboxTable: AuditableLongIdTable("planning_callback_inbox") {
    val provider = enumerationByName<PlanningProvider>("provider", 32)
    val eventId = varchar("event_id", 200)
    val planningRequestId = javaUUID("planning_request_id")
    val providerRevision = long("provider_revision")
    val outcome = enumerationByName<CallbackOutcome>("outcome", 40)
    val processedAt = timestamp("processed_at").nullable()

    init {
        uniqueIndex(provider, eventId)
    }
}

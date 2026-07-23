@file:Suppress("MagicNumber") // SQL column sizes are schema declarations.

package io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence

import io.bluetape4k.spring.data.exposed.jdbc.repository.ExposedJdbcRepository
import io.bluetape4k.spring.data.exposed.jdbc.repository.support.ExposedEntityInformationImpl
import io.bluetape4k.spring.data.exposed.jdbc.repository.support.SimpleExposedJdbcRepository
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.dao.Entity
import org.jetbrains.exposed.v1.dao.java.UUIDEntity
import org.jetbrains.exposed.v1.dao.java.UUIDEntityClass
import org.jetbrains.exposed.v1.javatime.timestamp
import java.util.UUID

private const val DIGEST_LENGTH = 64

internal object EventLog : UUIDTable("voucher_event_log", "event_id") {
    val tenantId = varchar("tenant_id", DIGEST_LENGTH)
    val streamType = varchar("stream_type", 64)
    val streamId = javaUUID("stream_id")
    val streamVersion = long("stream_version")
    val globalPosition = long("global_position")
    val eventType = varchar("event_type", 128)
    val schemaVersion = integer("schema_version")
    val occurredAt = timestamp("occurred_at")
    val recordedAt = timestamp("recorded_at")
    val correlationId = varchar("correlation_id", 128)
    val causationId = varchar("causation_id", 128).nullable()
    val actorSurrogate = varchar("actor_surrogate", DIGEST_LENGTH)
    val actorHmacKeyVersion = integer("actor_hmac_key_version")
    val payload = text("payload")
    val canonicalChecksum = varchar("canonical_checksum", 64)

    init {
        uniqueIndex(streamId, streamVersion)
        uniqueIndex(globalPosition)
        index(false, tenantId, streamType, streamId, streamVersion)
    }
}

internal object StreamHeads : UUIDTable("voucher_stream_head", "stream_id") {
    val tenantId = varchar("tenant_id", 64)
    val streamType = varchar("stream_type", 64)
    val version = long("version")
    val updatedAt = timestamp("updated_at")

    init {
        uniqueIndex(tenantId, streamType, id)
    }
}

internal object AppendFences : UUIDTable("voucher_append_fence", "fence_id") {
    val nextGlobalPosition = long("next_global_position")
}

internal object IdempotencyReceipts : UUIDTable("voucher_idempotency_receipt", "receipt_id") {
    val tenantId = varchar("tenant_id", 64)
    val principalDigest = varchar("principal_digest", 64)
    val operation = varchar("operation", 64)
    val resourceId = varchar("resource_id", 128)
    val keyDigest = varchar("key_digest", 64)
    val fingerprint = varchar("fingerprint", 64)
    val status =
        enumerationByName<io.bluetape4k.workshop.commerce.voucher.eventsourced.idempotency.ReceiptStatus>(
            "status",
            24,
        )
    val ownerTokenDigest = varchar("owner_token_digest", 64).nullable()
    val leaseDeadline = timestamp("lease_deadline").nullable()
    val commandDeadline = timestamp("command_deadline")
    val terminalOutcome =
        enumerationByName<io.bluetape4k.workshop.commerce.voucher.eventsourced.idempotency.ReceiptOutcome>(
            "terminal_outcome",
            48,
        ).nullable()
    val terminalStatus = integer("terminal_status").nullable()
    val allocationId = javaUUID("allocation_id").nullable()
    val hmacKeyVersion = integer("hmac_key_version").nullable()
    val generationKeyVersion = integer("generation_key_version").nullable()
    val verificationKeyVersion = integer("verification_key_version").nullable()
    val terminalObservedAt = timestamp("terminal_observed_at").nullable()
    val terminalStreamPosition = long("terminal_stream_position").nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    init {
        uniqueIndex(tenantId, principalDigest, operation, resourceId, keyDigest)
        index(false, status, commandDeadline)
    }
}

internal object EventSnapshots : UUIDTable("voucher_event_snapshot", "snapshot_id") {
    val tenantId = varchar("tenant_id", 64)
    val streamType = varchar("stream_type", 64)
    val streamId = javaUUID("stream_id")
    val streamVersion = long("stream_version")
    val schemaVersion = integer("schema_version")
    val keyVersion = integer("key_version")
    val canonicalDigest = varchar("canonical_digest", 64)
    val payload = text("payload")
    val createdAt = timestamp("created_at")

    init {
        uniqueIndex(streamId, streamVersion)
    }
}

internal object ProjectionCheckpoints : UUIDTable("voucher_projection_checkpoint", "checkpoint_id") {
    val projection = varchar("projection", 64)
    val generation = long("generation")
    val position = long("position")
    val fencingToken = long("fencing_token")
    val updatedAt = timestamp("updated_at")

    init {
        uniqueIndex(projection, generation)
    }
}

internal object ProjectionLeases : UUIDTable("voucher_projection_lease", "lease_id") {
    val projection = varchar("projection", 64)
    val generation = long("generation")
    val ownerDigest = varchar("owner_digest", DIGEST_LENGTH)
    val leaseDeadline = timestamp("lease_deadline")
    val fencingToken = long("fencing_token")
    val updatedAt = timestamp("updated_at")

    init {
        uniqueIndex(projection, generation)
    }
}

internal object ProjectionProcessedEvents : UUIDTable("voucher_projection_processed_event", "processed_id") {
    val projection = varchar("projection", 64)
    val generation = long("generation")
    val eventId = javaUUID("event_id")
    val globalPosition = long("global_position")
    val processedAt = timestamp("processed_at")

    init {
        uniqueIndex(projection, generation, eventId)
        index(false, projection, generation, globalPosition)
    }
}

internal object ProjectionReadModels : UUIDTable("voucher_projection_read_model", "read_model_id") {
    val projection = varchar("projection", 64)
    val generation = long("generation")
    val tenantId = varchar("tenant_id", 64)
    val streamType = varchar("stream_type", 64)
    val streamId = javaUUID("stream_id")
    val streamVersion = long("stream_version")
    val globalPosition = long("global_position")
    val eventType = varchar("event_type", 128)
    val payloadDigest = varchar("payload_digest", DIGEST_LENGTH)
    val fencingToken = long("fencing_token")
    val updatedAt = timestamp("updated_at")

    init {
        uniqueIndex(projection, generation, tenantId, streamType, streamId)
        index(false, projection, generation, globalPosition)
    }
}

internal object CampaignProjectionReadModels : UUIDTable("voucher_campaign_projection", "campaign_projection_id") {
    val projection = varchar("projection", 64)
    val generation = long("generation")
    val tenantId = varchar("tenant_id", 64)
    val campaignId = javaUUID("campaign_id")
    val state =
        enumerationByName<io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.CampaignState>(
            "state",
            16,
        )
    val streamVersion = long("stream_version")
    val globalPosition = long("global_position")
    val policyVersion = long("policy_version")
    val capacity = integer("capacity")
    val allocatedCount = integer("allocated_count")
    val perUserLimit = integer("per_user_limit")
    val redemptionTtlSeconds = long("redemption_ttl_seconds")
    val startsAt = timestamp("starts_at")
    val endsAt = timestamp("ends_at")
    val fencingToken = long("fencing_token")
    val updatedAt = timestamp("updated_at")

    init {
        uniqueIndex(projection, generation, tenantId, campaignId)
        index(false, projection, generation, globalPosition)
    }
}

internal object ProjectionPoisonEvents : UUIDTable("voucher_projection_poison_event", "poison_id") {
    val projection = varchar("projection", 64)
    val generation = long("generation")
    val eventId = javaUUID("event_id")
    val globalPosition = long("global_position")
    val eventType = varchar("event_type", 128)
    val reasonClass = varchar("reason_class", 64)
    val attempts = integer("attempts")
    val state =
        enumerationByName<io.bluetape4k.workshop.commerce.voucher.eventsourced.projection.ProjectionPoisonState>(
            "state",
            16,
        )
    val nextRetryAt = timestamp("next_retry_at")
    val resolvedAt = timestamp("resolved_at").nullable()
    val occurredAt = timestamp("occurred_at")
    val updatedAt = timestamp("updated_at")

    init {
        uniqueIndex(projection, generation, eventId)
    }
}

internal object ProjectionGenerations : UUIDTable("voucher_projection_generation", "generation_id") {
    val projection = varchar("projection", 64)
    val generation = long("generation")
    val state =
        enumerationByName<io.bluetape4k.workshop.commerce.voucher.eventsourced.projection.ProjectionGenerationState>(
            "state",
            16,
        )
    val targetPosition = long("target_position")
    val currentPosition = long("current_position")
    val fencingToken = long("fencing_token")
    val cancellationRevision = long("cancellation_revision")
    val canonicalDigest = varchar("canonical_digest", DIGEST_LENGTH).nullable()
    val retryableFailure = bool("retryable_failure")
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    init {
        uniqueIndex(projection, generation)
        index(false, projection, state)
    }
}

internal object ActiveProjectionGenerations : UUIDTable("voucher_active_projection_generation", "active_id") {
    val projection = varchar("projection", 64)
    val generation = long("generation")
    val revision = long("revision")
    val updatedAt = timestamp("updated_at")

    init {
        uniqueIndex(projection)
    }
}

internal object OperatorAudits : UUIDTable("voucher_operator_audit", "audit_id") {
    val actorDigest = varchar("actor_digest", DIGEST_LENGTH)
    val tenant = varchar("tenant", 64)
    val requestDigest = varchar("request_digest", DIGEST_LENGTH)
    val action =
        enumerationByName<io.bluetape4k.workshop.commerce.voucher.eventsourced.operations.OperatorAuditAction>(
            "action",
            32,
        )
    val projection = varchar("projection", 64)
    val generation = long("generation")
    val expectedFencingToken = long("expected_fencing_token")
    val beforeState =
        enumerationByName<io.bluetape4k.workshop.commerce.voucher.eventsourced.projection.ProjectionGenerationState>(
            "before_state",
            16,
        ).nullable()
    val afterState =
        enumerationByName<io.bluetape4k.workshop.commerce.voucher.eventsourced.projection.ProjectionGenerationState>(
            "after_state",
            16,
        ).nullable()
    val checkpointPosition = long("checkpoint_position")
    val streamPosition = long("stream_position")
    val outcome =
        enumerationByName<io.bluetape4k.workshop.commerce.voucher.eventsourced.operations.OperatorAuditOutcome>(
            "outcome",
            16,
        )
    val reasonClass = varchar("reason_class", 64).nullable()
    val occurredAt = timestamp("occurred_at")

    init {
        uniqueIndex(tenant, requestDigest, action)
        index(false, projection, generation, occurredAt)
    }
}

internal class EventLogEntity(id: EntityID<UUID>) : UUIDEntity(id) {
    companion object : UUIDEntityClass<EventLogEntity>(EventLog)
}

internal abstract class EventSourcedExposedJdbcRepository<E : Entity<ID>, ID : Any>(
    domainClass: Class<E>,
) : ExposedJdbcRepository<E, ID> by SimpleExposedJdbcRepository(
        ExposedEntityInformationImpl(domainClass),
    )

internal abstract class AppendOnlyEventSourcedRepository<E : Entity<ID>, ID : Any>(
    domainClass: Class<E>,
) : EventSourcedExposedJdbcRepository<E, ID>(domainClass) {
    final override fun <S : E> save(entity: S): S = immutableMutation()

    final override fun <S : E> saveAll(entities: Iterable<S>): List<S> = immutableMutation()

    final override fun deleteById(id: ID): Unit = immutableMutation()

    final override fun delete(entity: E): Unit = immutableMutation()

    final override fun deleteAllById(ids: Iterable<ID>): Unit = immutableMutation()

    final override fun deleteAll(entities: Iterable<E>): Unit = immutableMutation()

    final override fun deleteAll(): Unit = immutableMutation()

    protected fun <T> immutableMutation(): T =
        throw UnsupportedOperationException("append-only repository")
}

internal class EventLogRepository :
    AppendOnlyEventSourcedRepository<EventLogEntity, UUID>(EventLogEntity::class.java)

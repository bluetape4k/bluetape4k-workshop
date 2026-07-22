package io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence

import io.bluetape4k.workshop.commerce.metering.eventsourcing.idempotency.CommandDigest
import io.bluetape4k.workshop.commerce.metering.eventsourcing.idempotency.CommandScope
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

enum class CommandReceiptStatus { IN_PROGRESS, SUCCEEDED, FAILED }

data class CommandReceiptInsert(
    val scope: CommandScope,
    val fingerprint: CommandDigest,
    val ownerToken: UUID,
    val leaseUntil: Instant,
    val retentionUntil: Instant,
    val now: Instant,
)

data class CommandReceiptSnapshot(
    val id: UUID,
    val fingerprint: CommandDigest,
    val status: CommandReceiptStatus,
    val ownerToken: UUID,
    val leaseUntil: Instant,
    val httpStatus: Int?,
    val response: String?,
)

data class CommandReceiptCompletion(
    val receiptId: UUID,
    val owner: UUID,
    val httpStatus: Int,
    val response: String,
    val retentionUntil: Instant,
    val now: Instant,
)

@Repository
class CommandReceiptRepository :
    EventSourcingExposedJdbcRepository<CommandReceiptEntity, UUID>(CommandReceiptEntity::class.java) {

    fun insertOwnerIfAbsent(request: CommandReceiptInsert): Boolean = CommandReceipts.insertIgnore {
        it[id] = UUID.randomUUID()
        it[tenantId] = request.scope.tenantId
        it[operation] = request.scope.operation
        it[keyDigest] = request.scope.keyDigest.value
        it[fingerprint] = request.fingerprint.value
        it[status] = CommandReceiptStatus.IN_PROGRESS.name
        it[ownerToken] = request.ownerToken
        it[leaseUntil] = request.leaseUntil
        it[retentionUntil] = request.retentionUntil
        it[createdAt] = request.now
        it[updatedAt] = request.now
    }.insertedCount == 1

    fun find(scope: CommandScope): CommandReceiptSnapshot? = CommandReceiptEntity.find {
        (CommandReceipts.tenantId eq scope.tenantId) and
            (CommandReceipts.operation eq scope.operation) and
            (CommandReceipts.keyDigest eq scope.keyDigest.value)
    }.firstOrNull()?.snapshot()

    fun takeover(
        current: CommandReceiptSnapshot,
        owner: UUID,
        leaseUntil: Instant,
        retentionUntil: Instant,
        now: Instant,
    ): Boolean = CommandReceipts.update(
        where = {
            (CommandReceipts.id eq current.id) and
                (CommandReceipts.status eq CommandReceiptStatus.IN_PROGRESS.name) and
                (CommandReceipts.ownerToken eq current.ownerToken) and
                (CommandReceipts.leaseUntil eq current.leaseUntil)
        },
    ) {
        it[ownerToken] = owner
        it[CommandReceipts.leaseUntil] = leaseUntil
        it[CommandReceipts.retentionUntil] = retentionUntil
        it[updatedAt] = now
    } == 1

    fun complete(completion: CommandReceiptCompletion): Boolean = CommandReceipts.update(
        where = {
            (CommandReceipts.id eq completion.receiptId) and
                (CommandReceipts.ownerToken eq completion.owner) and
                (CommandReceipts.status eq CommandReceiptStatus.IN_PROGRESS.name)
        },
    ) {
        it[status] = CommandReceiptStatus.SUCCEEDED.name
        it[httpStatus] = completion.httpStatus
        it[response] = completion.response
        it[terminalAt] = completion.now
        it[CommandReceipts.retentionUntil] = completion.retentionUntil
        it[updatedAt] = completion.now
    } == 1

    fun isActiveOwner(receiptId: UUID, owner: UUID, now: Instant): Boolean =
        CommandReceipts.selectAll().where {
            (CommandReceipts.id eq receiptId) and
                (CommandReceipts.ownerToken eq owner) and
                (CommandReceipts.status eq CommandReceiptStatus.IN_PROGRESS.name) and
                (CommandReceipts.leaseUntil greater now)
        }.forUpdate().singleOrNull() != null

    private fun CommandReceiptEntity.snapshot() = CommandReceiptSnapshot(
        id.value, CommandDigest(fingerprint), CommandReceiptStatus.valueOf(status),
        ownerToken, leaseUntil, httpStatus, response,
    )
}

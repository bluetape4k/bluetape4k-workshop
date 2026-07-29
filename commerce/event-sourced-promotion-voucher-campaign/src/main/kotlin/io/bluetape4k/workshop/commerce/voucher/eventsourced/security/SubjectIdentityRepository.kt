@file:Suppress("MagicNumber") // SQL column sizes are schema declarations.

package io.bluetape4k.workshop.commerce.voucher.eventsourced.security

import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requireNotNull
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.TenantId
import io.bluetape4k.workshop.commerce.voucher.eventsourced.operations.EventSourcedDatabaseLane
import io.bluetape4k.workshop.commerce.voucher.eventsourced.operations.EventSourcedDatabasePermitGate
import io.bluetape4k.workshop.commerce.voucher.eventsourced.operations.EventSourcedPermitTransactionRunner
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.javatime.timestamp
import java.io.Serializable
import java.time.Clock
import java.time.Instant
import java.util.UUID

internal object SubjectIdentityMappings : UUIDTable("voucher_subject_identity_mapping", "surrogate_id") {
    val tenantId = varchar("tenant_id", 64)
    val identityDigest = varchar("identity_digest", 64)
    val hmacKeyVersion = integer("hmac_key_version")
    val createdAt = timestamp("created_at")

    init {
        uniqueIndex(tenantId, identityDigest, hmacKeyVersion)
        index(false, tenantId, id)
    }
}

@ConsistentCopyVisibility
internal data class SubjectIdentity private constructor(
    val tenantId: TenantId,
    val surrogate: UUID,
    val identityDigest: String,
    val hmacKeyVersion: Int,
    val createdAt: Instant,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L

        operator fun invoke(
            tenantId: TenantId,
            surrogate: UUID,
            identityDigest: String,
            hmacKeyVersion: Int,
            createdAt: Instant,
        ): SubjectIdentity =
            SubjectIdentity(
                tenantId = tenantId,
                surrogate = surrogate,
                identityDigest = identityDigest.requireNotBlank("identityDigest"),
                hmacKeyVersion = hmacKeyVersion,
                createdAt = createdAt,
            )
    }
}

/**
 * reversible identity mapping을 위한 transaction-bound persistence boundary입니다.
 *
 * erasure와 key-ring lookup만 지원되는 mutation path이므로 generic CRUD를 의도적으로 노출하지 않습니다.
 */
internal class SubjectIdentityRepository(
    private val keyRing: EventSourcedHmacKeyRing,
    private val clock: Clock = Clock.systemUTC(),
    private val nextSurrogate: () -> UUID = Uuid.V7::nextUUID,
) {
    fun resolve(
        tenantId: TenantId,
        domain: String,
        identity: String,
    ): SubjectIdentity {
        val validDomain = domain.requireNotBlank("identity.domain")
        val validIdentity = identity.requireNotBlank("identity")
        val candidates =
            keyRing.digestsForLookup(
                purpose = HmacPurpose.SUBJECT_IDENTITY,
                tenantId = tenantId,
                domain = validDomain,
                value = validIdentity,
            )
        candidates.firstNotNullOfOrNull { findByDigest(tenantId, it) }?.let { return it }

        return insert(
            digest =
                keyRing.digest(
                    purpose = HmacPurpose.SUBJECT_IDENTITY,
                    tenantId = tenantId,
                    domain = validDomain,
                    value = validIdentity,
                ),
            tenantId = tenantId,
        )
    }

    fun insert(
        digest: KeyedDigest,
        tenantId: TenantId,
    ): SubjectIdentity {
        val surrogate = nextSurrogate()
        val createdAt = clock.instant()
        SubjectIdentityMappings.insertIgnore { row ->
            row[SubjectIdentityMappings.id] = surrogate
            row[SubjectIdentityMappings.tenantId] = tenantId.value
            row[SubjectIdentityMappings.identityDigest] = digest.value
            row[SubjectIdentityMappings.hmacKeyVersion] = digest.keyVersion
            row[SubjectIdentityMappings.createdAt] = createdAt
        }
        return findByDigest(tenantId, digest).requireNotNull("subjectIdentity")
    }

    fun findBySurrogate(
        tenantId: TenantId,
        surrogate: UUID,
    ): SubjectIdentity? =
        SubjectIdentityMappings
            .selectAll()
            .where {
                (SubjectIdentityMappings.tenantId eq tenantId.value) and
                    (SubjectIdentityMappings.id eq surrogate)
            }.singleOrNull()
            ?.let(::toSubjectIdentity)

    fun erase(
        tenantId: TenantId,
        domain: String,
        identity: String,
    ): Int {
        val candidates =
            keyRing.digestsForLookup(
                purpose = HmacPurpose.SUBJECT_IDENTITY,
                tenantId = tenantId,
                domain = domain.requireNotBlank("identity.domain"),
                value = identity.requireNotBlank("identity"),
            )
        val erased =
            candidates.sumOf { digest ->
                SubjectIdentityMappings.deleteWhere {
                    (SubjectIdentityMappings.tenantId eq tenantId.value) and
                        (SubjectIdentityMappings.identityDigest eq digest.value) and
                        (SubjectIdentityMappings.hmacKeyVersion eq digest.keyVersion)
                }
            }
        if (erased > 0) {
            log.info { "Erased subject identity mappings. count=$erased" }
        }
        return erased
    }

    fun countFor(
        tenantId: TenantId,
        domain: String,
        identity: String,
    ): Long =
        keyRing
            .digestsForLookup(
                purpose = HmacPurpose.SUBJECT_IDENTITY,
                tenantId = tenantId,
                domain = domain.requireNotBlank("identity.domain"),
                value = identity.requireNotBlank("identity"),
            ).sumOf { digest ->
                SubjectIdentityMappings
                    .selectAll()
                    .where {
                        (SubjectIdentityMappings.tenantId eq tenantId.value) and
                            (SubjectIdentityMappings.identityDigest eq digest.value) and
                            (SubjectIdentityMappings.hmacKeyVersion eq digest.keyVersion)
                    }.count()
            }

    private fun findByDigest(
        tenantId: TenantId,
        digest: KeyedDigest,
    ): SubjectIdentity? =
        SubjectIdentityMappings
            .selectAll()
            .where {
                (SubjectIdentityMappings.tenantId eq tenantId.value) and
                    (SubjectIdentityMappings.identityDigest eq digest.value) and
                    (SubjectIdentityMappings.hmacKeyVersion eq digest.keyVersion)
            }.singleOrNull()
            ?.let(::toSubjectIdentity)

    private fun toSubjectIdentity(row: ResultRow): SubjectIdentity =
        SubjectIdentity(
            tenantId = TenantId(row[SubjectIdentityMappings.tenantId]),
            surrogate = row[SubjectIdentityMappings.id].value,
            identityDigest = row[SubjectIdentityMappings.identityDigest],
            hmacKeyVersion = row[SubjectIdentityMappings.hmacKeyVersion],
            createdAt = row[SubjectIdentityMappings.createdAt],
        )

    private companion object : KLogging()
}

internal interface SubjectIdentityService {
    fun resolve(
        tenantId: TenantId,
        domain: String,
        identity: String,
    ): SubjectIdentity

    fun erase(
        tenantId: TenantId,
        domain: String,
        identity: String,
    ): Int
}

internal class ExposedSubjectIdentityService(
    database: Database,
    permits: EventSourcedDatabasePermitGate,
    private val repository: SubjectIdentityRepository,
) : SubjectIdentityService {
    private val transactions =
        EventSourcedPermitTransactionRunner(
            database = database,
            permits = permits,
            lane = EventSourcedDatabaseLane.FOREGROUND,
        )

    override fun resolve(
        tenantId: TenantId,
        domain: String,
        identity: String,
    ): SubjectIdentity = transactions.inTransaction { repository.resolve(tenantId, domain, identity) }

    override fun erase(
        tenantId: TenantId,
        domain: String,
        identity: String,
    ): Int = transactions.inTransaction { repository.erase(tenantId, domain, identity) }
}

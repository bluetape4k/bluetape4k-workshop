package io.bluetape4k.workshop.commerce.ticket.identity

import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requireBeTrue
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requireNotEmpty
import io.bluetape4k.support.toUtf8Bytes
import io.bluetape4k.workshop.commerce.ticket.persistence.IdentityKind
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketExposedJdbcRepository
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketIdentityAliasEntity
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketIdentityAliases
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketIdentitySubjectEntity
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketJdbcExecutor
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import java.io.Serializable
import java.nio.ByteBuffer
import java.text.Normalizer
import java.time.Instant
import java.util.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** active HMAC read ring과 현재 write version 하나입니다. */
class IdentityKeyRing(
    val currentVersion: Int,
    val activeReadVersions: Set<Int>,
    keys: Map<Int, ByteArray>,
) {
    private val keys: Map<Int, ByteArray> = keys.mapValues { it.value.copyOf() }

    init {
        require(currentVersion in activeReadVersions) { "currentVersion[$currentVersion] in activeReadVersions[$activeReadVersions]" }
        this.activeReadVersions.requireNotEmpty("activeReadVersions")
        this.activeReadVersions
            .all { version -> this.keys[version]?.size?.let { it >= 32 } == true }
            .requireBeTrue { "activeReadVersions all keys must have 32+ characters." }
    }

    fun digest(
        version: Int,
        kind: IdentityKind,
        canonical: String,
    ): ByteArray {
        val mac = Mac.getInstance(HMAC_SHA_256)
        mac.init(SecretKeySpec(keys.getValue(version), HMAC_SHA_256))
        mac.update(IDENTITY_DOMAIN.toUtf8Bytes())
        mac.update(0)
        mac.update(kind.name.toUtf8Bytes())
        mac.update(0)
        mac.update(canonical.toUtf8Bytes())

        return mac.doFinal()
    }

    companion object: KLogging() {
        private const val HMAC_SHA_256 = "HmacSHA256"
        private const val IDENTITY_DOMAIN = "ticket-identity-subject-v1"
    }
}

/** raw identifier를 포함하지 않는 안정적인 internal subject입니다. */
data class IdentitySubject(
    val subjectId: UUID,
    val kind: IdentityKind,
): Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/** 모든 active digest 위의 advisory lock으로 직렬화되는 PostgreSQL alias authority입니다. */
class IdentitySubjectRepository:
    TicketExposedJdbcRepository<TicketIdentitySubjectEntity, UUID>(TicketIdentitySubjectEntity::class)

class IdentityAliasRepository(
    private val jdbc: TicketJdbcExecutor,
    private val subjectRepo: IdentitySubjectRepository = IdentitySubjectRepository(),
): TicketExposedJdbcRepository<TicketIdentityAliasEntity, Long>(TicketIdentityAliasEntity::class) {

    fun resolveOrCreate(
        kind: IdentityKind,
        candidates: Map<Int, ByteArray>,
        currentVersion: Int,
    ): IdentitySubject =
        jdbc.transaction {
            val lockKeys = candidates.values.map(::advisoryKey).distinct().sorted()
            lockKeys.forEach { key ->
                transaction.exec("SELECT pg_advisory_xact_lock($key)") { result ->
                    check(result.next())
                }
            }

            val subject = candidates.entries
                .sortedByDescending { it.key }
                .firstNotNullOfOrNull { (version, digest) ->
                    findSubject(kind, version, digest)
                }
                ?: IdentitySubject(Uuid.V7.nextId(), kind).also { insertSubject(it) }

            ensureAlias(subject, currentVersion, candidates.getValue(currentVersion))
            subject
        }

    fun aliasVersions(subjectId: UUID): Set<Int> =
        jdbc.transaction {
            findAll { TicketIdentityAliases.subjectId eq subjectId }
                .mapTo(linkedSetOf()) { it.keyVersion }
        }

    private fun findSubject(
        kind: IdentityKind,
        version: Int,
        digest: ByteArray,
    ): IdentitySubject? {
        val whereOp: Op<Boolean> = (TicketIdentityAliases.identityKind eq kind.name) and
                (TicketIdentityAliases.keyVersion eq version) and
                (TicketIdentityAliases.digest eq digest)

        return findAll { whereOp }.singleOrNull()
            ?.let { IdentitySubject(it.subjectId, kind) }
    }

    private fun insertSubject(subject: IdentitySubject) {
        val entity = TicketIdentitySubjectEntity.new(subject.subjectId) {
            identityKind = subject.kind.name
            createdAt = Instant.now()
        }
        subjectRepo.save(entity)
    }

    private fun ensureAlias(
        subject: IdentitySubject,
        version: Int,
        digest: ByteArray,
    ) {
        val whereOp = (TicketIdentityAliases.identityKind eq subject.kind.name) and
                (TicketIdentityAliases.keyVersion eq version) and
                (TicketIdentityAliases.digest eq digest)
        if (exists { whereOp }) {
            return
        }

        save(
            TicketIdentityAliasEntity.new {
                identityKind = subject.kind.name
                keyVersion = version
                this.digest = digest
                subjectId = subject.subjectId
                createdAt = Instant.now()
            },
        )
    }

    private fun advisoryKey(digest: ByteArray): Long =
        ByteBuffer.wrap(digest, 0, Long.SIZE_BYTES).long
}

/** 모든 active read key를 통해 raw request identity를 안정적인 database subject로 해석합니다. */
class IdentityService(
    private val keys: IdentityKeyRing,
    private val aliases: IdentityAliasRepository,
) {

    fun resolve(
        kind: IdentityKind,
        canonical: String,
    ): IdentitySubject {
        val normalized = Normalizer.normalize(canonical.trim(), Normalizer.Form.NFC)
        normalized.requireNotBlank("canonical")

        val candidates = keys.activeReadVersions.associateWith {
            keys.digest(it, kind, normalized)
        }
        return aliases.resolveOrCreate(kind, candidates, keys.currentVersion)
    }
}

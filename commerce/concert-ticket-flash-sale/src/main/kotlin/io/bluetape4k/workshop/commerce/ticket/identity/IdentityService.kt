package io.bluetape4k.workshop.commerce.ticket.identity

import io.bluetape4k.workshop.commerce.ticket.persistence.IdentityKind
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketExposedJdbcRepository
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketIdentityAliasEntity
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketIdentityAliases
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketIdentitySubjectEntity
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketIdentitySubjects
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketJdbcExecutor
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import java.time.Instant
import java.io.Serializable
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8
import java.text.Normalizer
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Active HMAC read ring plus the one current write version. */
class IdentityKeyRing(
    val currentVersion: Int,
    activeReadVersions: Set<Int>,
    keys: Map<Int, ByteArray>,
) {
    val activeReadVersions: Set<Int> = activeReadVersions.toSet()
    private val keys: Map<Int, ByteArray> = keys.mapValues { it.value.copyOf() }

    init {
        require(currentVersion in this.activeReadVersions) { "current identity key must be readable" }
        require(this.activeReadVersions.isNotEmpty()) { "identity read ring must not be empty" }
        require(this.activeReadVersions.all { version -> this.keys[version]?.size?.let { it >= 32 } == true }) {
            "every active identity key must contain at least 32 bytes"
        }
    }

    fun digest(
        version: Int,
        kind: IdentityKind,
        canonical: String,
    ): ByteArray {
        val mac = Mac.getInstance(HMAC_SHA_256)
        mac.init(SecretKeySpec(keys.getValue(version), HMAC_SHA_256))
        mac.update(IDENTITY_DOMAIN.toByteArray(UTF_8))
        mac.update(0)
        mac.update(kind.name.toByteArray(UTF_8))
        mac.update(0)
        mac.update(canonical.toByteArray(UTF_8))
        return mac.doFinal()
    }

    companion object {
        private const val HMAC_SHA_256 = "HmacSHA256"
        private const val IDENTITY_DOMAIN = "ticket-identity-subject-v1"
    }
}

/** Stable internal subject with no raw identifier. */
data class IdentitySubject(
    val subjectId: UUID,
    val kind: IdentityKind,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/** PostgreSQL alias authority serialized by advisory locks over every active digest. */
class IdentitySubjectRepository :
    TicketExposedJdbcRepository<TicketIdentitySubjectEntity, UUID>(TicketIdentitySubjectEntity::class.java)

class IdentityAliasRepository(
    private val jdbc: TicketJdbcExecutor,
    private val subjects: IdentitySubjectRepository = IdentitySubjectRepository(),
) : TicketExposedJdbcRepository<TicketIdentityAliasEntity, Long>(TicketIdentityAliasEntity::class.java) {
    fun resolveOrCreate(
        kind: IdentityKind,
        candidates: Map<Int, ByteArray>,
        currentVersion: Int,
    ): IdentitySubject =
        jdbc.transaction {
            val lockKeys = candidates.values.map(::advisoryKey).distinct().sorted()
            lockKeys.forEach { key ->
                exposed.exec("SELECT pg_advisory_xact_lock($key)") { result -> check(result.next()) }
            }

            val existing =
                candidates.entries
                    .sortedByDescending { it.key }
                    .firstNotNullOfOrNull { (version, digest) -> findSubject(kind, version, digest) }
            val subject = existing ?: IdentitySubject(UUID.randomUUID(), kind).also { insertSubject(it) }
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
    ): IdentitySubject? = findAll {
        (TicketIdentityAliases.identityKind eq kind.name) and
            (TicketIdentityAliases.keyVersion eq version) and
            (TicketIdentityAliases.digest eq digest)
    }.singleOrNull()?.let { IdentitySubject(it.subjectId, kind) }

    private fun insertSubject(subject: IdentitySubject) {
        val entity = TicketIdentitySubjectEntity.new(subject.subjectId) {
            identityKind = subject.kind.name
            createdAt = Instant.now()
        }
        subjects.save(entity)
    }

    private fun ensureAlias(
        subject: IdentitySubject,
        version: Int,
        digest: ByteArray,
    ) {
        if (exists {
                (TicketIdentityAliases.identityKind eq subject.kind.name) and
                    (TicketIdentityAliases.keyVersion eq version) and
                    (TicketIdentityAliases.digest eq digest)
            }
        ) {
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

    private fun advisoryKey(digest: ByteArray): Long = ByteBuffer.wrap(digest, 0, Long.SIZE_BYTES).long
}

/** Resolves raw request identity to a stable database subject through every active read key. */
class IdentityService(
    private val keys: IdentityKeyRing,
    private val aliases: IdentityAliasRepository,
) {
    fun resolve(
        kind: IdentityKind,
        canonical: String,
    ): IdentitySubject {
        val normalized = Normalizer.normalize(canonical.trim(), Normalizer.Form.NFC)
        require(normalized.isNotEmpty()) { "canonical identity must not be blank" }
        val candidates = keys.activeReadVersions.associateWith { keys.digest(it, kind, normalized) }
        return aliases.resolveOrCreate(kind, candidates, keys.currentVersion)
    }
}

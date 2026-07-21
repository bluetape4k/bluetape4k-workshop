package io.bluetape4k.workshop.commerce.ticket.identity

import io.bluetape4k.workshop.commerce.ticket.persistence.IdentityKind
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketJdbcExecutor
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
class IdentityAliasRepository(
    private val jdbc: TicketJdbcExecutor,
) {
    fun resolveOrCreate(
        kind: IdentityKind,
        candidates: Map<Int, ByteArray>,
        currentVersion: Int,
    ): IdentitySubject =
        jdbc.transaction {
            val lockKeys = candidates.values.map(::advisoryKey).distinct().sorted()
            connection.prepareStatement("SELECT pg_advisory_xact_lock(?)").use { statement ->
                lockKeys.forEach { key ->
                    statement.setLong(1, key)
                    statement.executeQuery().use { result -> check(result.next()) }
                }
            }

            val existing =
                candidates.entries
                    .sortedByDescending { it.key }
                    .firstNotNullOfOrNull { (version, digest) -> find(kind, version, digest) }
            val subject = existing ?: IdentitySubject(UUID.randomUUID(), kind).also { insertSubject(it) }
            ensureAlias(subject, currentVersion, candidates.getValue(currentVersion))
            subject
        }

    fun aliasVersions(subjectId: UUID): Set<Int> =
        jdbc.transaction {
            connection.prepareStatement(
                "SELECT key_version FROM ticket_identity_aliases WHERE subject_id = ? ORDER BY key_version",
            ).use { statement ->
                statement.setObject(1, subjectId)
                statement.executeQuery().use { result ->
                    buildSet { while (result.next()) add(result.getInt(1)) }
                }
            }
        }

    private fun io.bluetape4k.workshop.commerce.ticket.persistence.TicketJdbcTransaction.find(
        kind: IdentityKind,
        version: Int,
        digest: ByteArray,
    ): IdentitySubject? =
        connection.prepareStatement(
            """
            SELECT subject_id FROM ticket_identity_aliases
            WHERE identity_kind = ? AND key_version = ? AND digest = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, kind.name)
            statement.setInt(2, version)
            statement.setBytes(3, digest)
            statement.executeQuery().use { result ->
                if (result.next()) IdentitySubject(result.getObject(1, UUID::class.java), kind) else null
            }
        }

    private fun io.bluetape4k.workshop.commerce.ticket.persistence.TicketJdbcTransaction.insertSubject(
        subject: IdentitySubject,
    ) {
        connection.prepareStatement(
            "INSERT INTO ticket_identity_subjects(subject_id, identity_kind) VALUES (?, ?)",
        ).use { statement ->
            statement.setObject(1, subject.subjectId)
            statement.setString(2, subject.kind.name)
            statement.executeUpdate()
        }
    }

    private fun io.bluetape4k.workshop.commerce.ticket.persistence.TicketJdbcTransaction.ensureAlias(
        subject: IdentitySubject,
        version: Int,
        digest: ByteArray,
    ) {
        connection.prepareStatement(
            """
            INSERT INTO ticket_identity_aliases(identity_kind, key_version, digest, subject_id)
            VALUES (?, ?, ?, ?) ON CONFLICT (identity_kind, key_version, digest) DO NOTHING
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, subject.kind.name)
            statement.setInt(2, version)
            statement.setBytes(3, digest)
            statement.setObject(4, subject.subjectId)
            statement.executeUpdate()
        }
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

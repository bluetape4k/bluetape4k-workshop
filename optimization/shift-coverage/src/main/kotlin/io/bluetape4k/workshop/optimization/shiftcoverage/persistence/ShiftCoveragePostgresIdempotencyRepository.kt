package io.bluetape4k.workshop.optimization.shiftcoverage.persistence

import io.bluetape4k.workshop.optimization.shiftcoverage.application.IdempotencyClaim
import io.bluetape4k.workshop.optimization.shiftcoverage.application.IdempotencyClaimKind
import io.bluetape4k.workshop.optimization.shiftcoverage.application.IdempotencyNamespace
import io.bluetape4k.workshop.optimization.shiftcoverage.application.IdempotencyRecord
import io.bluetape4k.workshop.optimization.shiftcoverage.application.ShiftCoverageIdempotencyPort
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Repository

/** PostgreSQL unique claim으로 process 재시작 뒤에도 mutation idempotency를 보존합니다. */
@Repository
@Profile("postgres")
class ShiftCoveragePostgresIdempotencyRepository : ShiftCoverageIdempotencyPort {
    override fun begin(namespace: IdempotencyNamespace, fingerprintSha256: String): IdempotencyClaim =
        ShiftCoverageTransactionSupport.inMutation {
            validateFingerprint(fingerprintSha256)
            val inserted = ShiftCoverageIdempotencyTable.insertIgnore { statement ->
                statement[method] = namespace.method
                statement[route] = namespace.route
                statement[demoScope] = namespace.demoScope
                statement[principal] = namespace.principal
                statement[key] = namespace.key.value
                statement[ShiftCoverageIdempotencyTable.fingerprintSha256] = fingerprintSha256
                statement[status] = IN_PROGRESS
            }.insertedCount > 0
            if (inserted) return@inMutation IdempotencyClaim(IdempotencyClaimKind.NEW)

            val record = find(namespace) ?: return@inMutation IdempotencyClaim(IdempotencyClaimKind.NEW)
            when {
                record.fingerprintSha256 != fingerprintSha256 -> IdempotencyClaim(IdempotencyClaimKind.REUSED)
                record.response == null -> IdempotencyClaim(IdempotencyClaimKind.IN_PROGRESS)
                else -> IdempotencyClaim(IdempotencyClaimKind.REPLAY, record.response)
            }
        }

    override fun complete(namespace: IdempotencyNamespace, response: String): IdempotencyRecord =
        ShiftCoverageTransactionSupport.inMutation {
            val changed = ShiftCoverageIdempotencyTable.update(where = { predicateExpression(namespace) }) { statement ->
                statement[ShiftCoverageIdempotencyTable.response] = response
                statement[status] = COMPLETED
            }
            require(changed == 1) { "idempotency claim does not exist" }
            find(namespace) ?: error("completed idempotency claim disappeared")
        }

    override fun abort(namespace: IdempotencyNamespace) {
        ShiftCoverageTransactionSupport.inMutation {
            ShiftCoverageIdempotencyTable.deleteWhere { predicateExpression(namespace) }
        }
    }

    private fun find(namespace: IdempotencyNamespace): IdempotencyRecord? = ShiftCoverageIdempotencyTable.selectAll()
        .where { predicateExpression(namespace) }
        .singleOrNull()
        ?.let { row -> IdempotencyRecord(row[ShiftCoverageIdempotencyTable.fingerprintSha256], row[ShiftCoverageIdempotencyTable.response]) }

    private fun predicateExpression(namespace: IdempotencyNamespace) =
        (ShiftCoverageIdempotencyTable.method eq namespace.method) and
            (ShiftCoverageIdempotencyTable.route eq namespace.route) and
            (ShiftCoverageIdempotencyTable.demoScope eq namespace.demoScope) and
            (ShiftCoverageIdempotencyTable.principal eq namespace.principal) and
            (ShiftCoverageIdempotencyTable.key eq namespace.key.value)

    private fun validateFingerprint(value: String) {
        require(value.matches(Regex("[0-9a-f]{64}"))) { "fingerprint must be lowercase SHA-256" }
    }

    private companion object {
        const val IN_PROGRESS = "IN_PROGRESS"
        const val COMPLETED = "COMPLETED"
    }
}

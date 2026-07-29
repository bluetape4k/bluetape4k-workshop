package io.bluetape4k.workshop.commerce.voucher.eventsourced.snapshot

import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.EventSnapshots
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.StreamKey
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import io.bluetape4k.support.requireEquals
import io.bluetape4k.support.requireLe
import io.bluetape4k.support.requirePositiveNumber
import io.bluetape4k.support.requireZeroOrPositiveNumber
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.sql.SQLException
import java.time.Instant
import java.util.HexFormat

internal const val SNAPSHOT_THRESHOLD = 250L
internal const val MAX_SNAPSHOT_BYTES = 1024 * 1024

internal class SnapshotMetadata(
    val streamVersion: Long,
    val schemaVersion: Int,
    val keyVersion: Int,
) {
    init {
        streamVersion.requireZeroOrPositiveNumber("streamVersion")
        schemaVersion.requirePositiveNumber("schemaVersion")
        keyVersion.requirePositiveNumber("keyVersion")
    }
}

internal class EventSnapshot(
    val stream: StreamKey,
    val metadata: SnapshotMetadata,
    val canonicalState: String,
    val canonicalDigest: String = snapshotDigest(canonicalState),
    val createdAt: Instant = Instant.now(),
) {
    init {
        canonicalState.toByteArray(UTF_8).size.requireLe(MAX_SNAPSHOT_BYTES, "snapshot.bytes")
        canonicalDigest.requireEquals(snapshotDigest(canonicalState), "canonicalDigest")
    }
}

/** snapshot row는 rehydration을 가속하지만 event-log authority를 절대 대체하지 않습니다. */
internal class EventSnapshotRepository {
    fun save(snapshot: EventSnapshot) {
        TransactionManager.current()
        EventSnapshots.insert { row ->
            row[EventSnapshots.tenantId] = snapshot.stream.tenantId.value
            row[EventSnapshots.streamType] = snapshot.stream.streamType
            row[EventSnapshots.streamId] = snapshot.stream.streamId
            row[EventSnapshots.streamVersion] = snapshot.metadata.streamVersion
            row[EventSnapshots.schemaVersion] = snapshot.metadata.schemaVersion
            row[EventSnapshots.keyVersion] = snapshot.metadata.keyVersion
            row[EventSnapshots.canonicalDigest] = snapshot.canonicalDigest
            row[EventSnapshots.payload] = snapshot.canonicalState
            row[EventSnapshots.createdAt] = snapshot.createdAt
        }
    }

    fun latest(stream: StreamKey): EventSnapshot? {
        TransactionManager.current()
        return EventSnapshots
            .selectAll()
            .where {
                (EventSnapshots.tenantId eq stream.tenantId.value) and
                    (EventSnapshots.streamType eq stream.streamType) and
                    (EventSnapshots.streamId eq stream.streamId)
            }.orderBy(EventSnapshots.streamVersion to SortOrder.DESC)
            .limit(1)
            .singleOrNull()
            ?.let { row ->
                EventSnapshot(
                    stream = stream,
                    metadata =
                        SnapshotMetadata(
                            streamVersion = row[EventSnapshots.streamVersion],
                            schemaVersion = row[EventSnapshots.schemaVersion],
                            keyVersion = row[EventSnapshots.keyVersion],
                        ),
                    canonicalState = row[EventSnapshots.payload],
                    canonicalDigest = row[EventSnapshots.canonicalDigest],
                    createdAt = row[EventSnapshots.createdAt],
                )
            }
    }
}

/** authoritative append transaction이 commit된 뒤 optional acceleration state를 씁니다. */
internal interface SnapshotTransactionRunner {
    fun <T> inTransaction(block: () -> T): T
}

internal class ExposedSnapshotTransactionRunner(
    private val database: Database,
) : SnapshotTransactionRunner {
    override fun <T> inTransaction(block: () -> T): T = transaction(database) { block() }
}

internal class SnapshotWriter(
    private val transactions: SnapshotTransactionRunner,
    private val snapshots: EventSnapshotRepository,
) {
    fun writeAfterAppend(snapshot: EventSnapshot): Boolean =
        try {
            transactions.inTransaction { snapshots.save(snapshot) }
            true
        } catch (e: SQLException) {
            log.warn(e) { "voucher_snapshot_write_failed streamType=${snapshot.stream.streamType}" }
            false
        }

    companion object : KLogging()
}

internal fun shouldWriteSnapshot(streamVersion: Long): Boolean =
    streamVersion > 0 && streamVersion % SNAPSHOT_THRESHOLD == 0L

private fun snapshotDigest(value: String): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(value.toByteArray(UTF_8))
        .let(HexFormat.of()::formatHex)

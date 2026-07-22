package io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence

import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.StreamKey
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

data class NewAggregateSnapshot(
    val stream: StreamKey,
    val streamVersion: Long,
    val reducerVersion: Int,
    val statePayload: String,
    val lastEventHash: String,
    val createdAt: Instant,
) {
    init {
        require(streamVersion > 0) { "snapshot_stream_version_invalid" }
        require(reducerVersion > 0) { "snapshot_reducer_version_invalid" }
        require(statePayload.isNotBlank()) { "snapshot_state_payload_invalid" }
        require(lastEventHash.isNotBlank()) { "snapshot_event_hash_invalid" }
    }
}

data class StoredAggregateSnapshot(
    val snapshotId: UUID,
    val stream: StreamKey,
    val streamVersion: Long,
    val reducerVersion: Int,
    val statePayload: String,
    val lastEventHash: String,
    val createdAt: Instant,
)

@Repository
class SnapshotRepository :
    AppendOnlyEventSourcingExposedJdbcRepository<AggregateSnapshotEntity, UUID>(AggregateSnapshotEntity::class.java) {

    fun append(snapshot: NewAggregateSnapshot): StoredAggregateSnapshot {
        val snapshotId = UUID.randomUUID()
        AggregateSnapshots.insert {
            it[id] = snapshotId
            it[tenantId] = snapshot.stream.tenantId
            it[streamType] = snapshot.stream.streamType
            it[streamId] = snapshot.stream.streamId
            it[streamVersion] = snapshot.streamVersion
            it[reducerVersion] = snapshot.reducerVersion
            it[statePayload] = snapshot.statePayload
            it[lastEventHash] = snapshot.lastEventHash
            it[createdAt] = snapshot.createdAt
        }
        return snapshot.toStored(snapshotId)
    }

    fun latest(stream: StreamKey, reducerVersion: Int): StoredAggregateSnapshot? {
        require(reducerVersion > 0) { "snapshot_reducer_version_invalid" }
        return AggregateSnapshots.selectAll()
            .where {
                (AggregateSnapshots.tenantId eq stream.tenantId) and
                    (AggregateSnapshots.streamType eq stream.streamType) and
                    (AggregateSnapshots.streamId eq stream.streamId) and
                    (AggregateSnapshots.reducerVersion eq reducerVersion)
            }
            .orderBy(AggregateSnapshots.streamVersion to SortOrder.DESC)
            .limit(1)
            .singleOrNull()
            ?.let { row ->
                StoredAggregateSnapshot(
                    snapshotId = row[AggregateSnapshots.id].value,
                    stream = stream,
                    streamVersion = row[AggregateSnapshots.streamVersion],
                    reducerVersion = row[AggregateSnapshots.reducerVersion],
                    statePayload = row[AggregateSnapshots.statePayload],
                    lastEventHash = row[AggregateSnapshots.lastEventHash],
                    createdAt = row[AggregateSnapshots.createdAt],
                )
            }
    }

    private fun NewAggregateSnapshot.toStored(snapshotId: UUID): StoredAggregateSnapshot = StoredAggregateSnapshot(
        snapshotId, stream, streamVersion, reducerVersion, statePayload, lastEventHash, createdAt,
    )
}

package io.bluetape4k.workshop.exposed.javers.persistence

import io.bluetape4k.javers.repository.CdoSnapshotRepository

/**
 * Redis-backed order audit namespace가 startup 시 안전하게 이어질 수 있는지 확인한다.
 *
 * provider의 head load가 malformed sequence/commit-id를 검증한다. head가 없으면 caller가 documented provider
 * snapshot-index key의 존재 여부를 O(1)로 확인해 snapshot-only partial loss를 초기 상태로 오인하지 않게 한다.
 */
internal fun CdoSnapshotRepository.requireConsistentOrderAuditHead(
    snapshotIndexExists: () -> Boolean,
) {
    if (getHeadId() == null) {
        check(!snapshotIndexExists()) {
            "Corrupted Redis audit metadata. persisted Order snapshots exist without head metadata."
        }
    }
}

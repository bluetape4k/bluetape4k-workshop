package io.bluetape4k.workshop.commerce.voucher.eventsourced.snapshot

import io.bluetape4k.io.digest.Digesters
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import io.bluetape4k.support.requireEquals
import io.bluetape4k.support.requireLe
import io.bluetape4k.support.requirePositiveNumber
import io.bluetape4k.support.requireZeroOrPositiveNumber
import io.bluetape4k.support.toUtf8Bytes
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.EventSnapshots
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.StreamKey
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.sql.SQLException
import java.time.Instant
import java.util.*

/**
 * `SNAPSHOT_THRESHOLD`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
 *
 * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
 */
internal const val SNAPSHOT_THRESHOLD = 250L
/**
 * `MAX_SNAPSHOT_BYTES`은 바우처·프로모션에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
 *
 * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
 */
internal const val MAX_SNAPSHOT_BYTES = 1024 * 1024

/**
 * SnapshotMetadata은 이벤트 소싱 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
 * 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
internal class SnapshotMetadata(
    /**
     * `streamVersion`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val streamVersion: Long,
    /**
     * `schemaVersion`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val schemaVersion: Int,
    /**
     * `keyVersion`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val keyVersion: Int,
) {
    init {
        streamVersion.requireZeroOrPositiveNumber("streamVersion")
        schemaVersion.requirePositiveNumber("schemaVersion")
        keyVersion.requirePositiveNumber("keyVersion")
    }
}

/**
 * EventSnapshot은 이벤트 소싱 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
 * 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
internal class EventSnapshot(
    /**
     * `stream`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val stream: StreamKey,
    /**
     * `metadata`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val metadata: SnapshotMetadata,
    /**
     * `canonicalState`은 바우처·프로모션에서 업무 규칙의 허용 여부를 나타내는 flag입니다.
     *
     * true와 false 각각의 분기를 명시적으로 처리하고 기본값이 안전한 거부인지 확인합니다.
     */
    val canonicalState: String,
    /**
     * `canonicalDigest`은 바우처·프로모션에서 업무 규칙의 허용 여부를 나타내는 flag입니다.
     *
     * true와 false 각각의 분기를 명시적으로 처리하고 기본값이 안전한 거부인지 확인합니다.
     */
    val canonicalDigest: String = snapshotDigest(canonicalState),
    /**
     * `createdAt`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val createdAt: Instant = Instant.now(),
) {
    init {
        canonicalState.toUtf8Bytes().size.requireLe(MAX_SNAPSHOT_BYTES, "snapshot.bytes")
        canonicalDigest.requireEquals(snapshotDigest(canonicalState), "canonicalDigest")
    }
}

/** snapshot row는 rehydration을 가속하지만 event-log authority를 절대 대체하지 않습니다. */
/** snapshot row는 rehydration을 가속하지만 event-log authority를 절대 대체하지 않습니다. */
internal class EventSnapshotRepository {
    /**
     * save은 이벤트 소싱 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param snapshot 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
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

    /**
     * latest은 이벤트 소싱 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param stream 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
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
                    metadata = SnapshotMetadata(
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
/** authoritative append transaction이 commit된 뒤 optional acceleration state를 씁니다. */
internal interface SnapshotTransactionRunner {
/**
 * `inTransaction`은 이벤트 기반 프로모션 바우처 캠페인 업무 흐름에서 입력을 검증하거나 확정 상태를 변환하는 단계입니다.
 *
 * 호출 전에는 인증 주체·tenant 범위·멱등 키·기대 버전을 확인하고, 호출 후에는 commit된 source-of-truth와 비동기 알림을 구분하여 재시도·보상·감사
 * 처리를 일관되게 수행해야 합니다.
 */
    fun <T> inTransaction(block: () -> T): T
}

/**
 * ExposedSnapshotTransactionRunner은 이벤트 소싱 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행
 * 계약을
 * 이 타입의 규칙에 맞춰 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
internal class ExposedSnapshotTransactionRunner(
    /**
     * `database`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val database: Database,
): SnapshotTransactionRunner {
/**
 * `inTransaction`은 이벤트 기반 프로모션 바우처 캠페인 업무 흐름에서 입력을 검증하거나 확정 상태를 변환하는 단계입니다.
 *
 * 호출 전에는 인증 주체·tenant 범위·멱등 키·기대 버전을 확인하고, 호출 후에는 commit된 source-of-truth와 비동기 알림을 구분하여 재시도·보상·감사
 * 처리를 일관되게 수행해야 합니다.
 */
    override fun <T> inTransaction(block: () -> T): T = transaction(database) { block() }
}

/**
 * SnapshotWriter은 이벤트 소싱 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
 * 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
internal class SnapshotWriter(
    /**
     * `transactions`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val transactions: SnapshotTransactionRunner,
    /**
     * `snapshots`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val snapshots: EventSnapshotRepository,
) {
    /**
     * writeAfterAppend은 이벤트 소싱 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param snapshot 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    fun writeAfterAppend(snapshot: EventSnapshot): Boolean =
        try {
            transactions.inTransaction { snapshots.save(snapshot) }
            true
        } catch (e: SQLException) {
            log.warn(e) { "voucher_snapshot_write_failed streamType=${snapshot.stream.streamType}" }
            false
        }

    companion object: KLogging()
}

/**
 * shouldWriteSnapshot은 이벤트 소싱 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 * @param streamVersion 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
 */
internal fun shouldWriteSnapshot(streamVersion: Long): Boolean =
    streamVersion > 0 && streamVersion % SNAPSHOT_THRESHOLD == 0L

/**
 * snapshotDigest은 이벤트 소싱 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 * @param value 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
 */
private fun snapshotDigest(value: String): String = Digesters.SHA256.digestHexUtf8(value)

package io.bluetape4k.workshop.leader.jobsafety.audit

import java.util.ArrayDeque
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 직렬화된 audit payload를 최근 순서로 보관하는 bounded observation store입니다.
 *
 * 이 store는 전송 성공 여부나 재시도 이력을 나타내는 authoritative history가 아닙니다.
 * 입력과 snapshot 모두 방어적으로 복사하며, count와 retained byte 합계를 같은 lock으로
 * 갱신하여 report가 예산을 초과한 중간 상태를 관찰하지 않도록 합니다.
 *
 * @param maxEntries 보관할 최대 payload 개수입니다.
 * @param maxBytes 보관할 serialized payload byte의 최대 합계입니다.
 */
class BoundedAuditPayloadStore(
    private val maxEntries: Int,
    private val maxBytes: Long,
) {

    private val lock = ReentrantLock()
    private val payloads = ArrayDeque<ByteArray>()

    /** 현재 보관 중인 serialized payload byte의 정확한 합계입니다. */
    val retainedBytes: Long
        get() = lock.withLock { retainedBytesInternal }

    /** 현재 보관 중인 payload 개수입니다. */
    val size: Int
        get() = lock.withLock { payloads.size }

    private var retainedBytesInternal: Long = 0

    init {
        require(maxEntries > 0) { "maxEntries must be positive: $maxEntries" }
        require(maxBytes > 0) { "maxBytes must be positive: $maxBytes" }
    }

    /**
     * payload를 최근 observation으로 추가합니다.
     *
     * 하나의 payload가 전체 byte 예산을 초과하면 기존 payload를 제거하지 않고 버립니다.
     * 예산을 맞추기 위해 필요한 경우 가장 오래된 payload부터 제거합니다.
     *
     * @return payload가 저장되었으면 `true`, 단일 payload가 예산을 초과했으면 `false`입니다.
     */
    fun add(payload: ByteArray): Boolean {
        val copied = payload.copyOf()
        val copiedBytes = copied.size.toLong()
        if (copiedBytes > maxBytes) return false

        lock.withLock {
            while (payloads.isNotEmpty() &&
                (payloads.size >= maxEntries || copiedBytes > maxBytes - retainedBytesInternal)
            ) {
                retainedBytesInternal -= payloads.removeFirst().size.toLong()
            }

            payloads.addLast(copied)
            retainedBytesInternal += copiedBytes
        }
        return true
    }

    /**
     * 최근 순서의 payload snapshot을 반환합니다.
     *
     * 각 배열은 독립적으로 복사되므로 호출자가 반환값을 변경해도 store 상태는 바뀌지 않습니다.
     */
    fun snapshot(): List<ByteArray> = lock.withLock {
        payloads.map(ByteArray::copyOf)
    }
}

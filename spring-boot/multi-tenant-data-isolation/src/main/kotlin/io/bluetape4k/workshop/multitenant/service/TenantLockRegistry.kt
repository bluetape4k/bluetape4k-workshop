package io.bluetape4k.workshop.multitenant.service

import io.bluetape4k.workshop.multitenant.domain.TenantId
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 결정적인 workshop test 를 위한 tenant-keyed lock registry 입니다.
 */
@Component
class TenantLockRegistry(
    private val keyFactory: TenantKeyFactory,
) {

    private val locks = ConcurrentHashMap<String, ReentrantLock>()

    /**
     * tenant scope 를 포함하는 key 의 lock 을 잡은 상태로 [block] 을 실행합니다.
     */
    fun <T> withInvoiceLock(tenantId: TenantId, invoiceId: Long, block: (String) -> T): T {
        val key = keyFactory.invoiceLockKey(tenantId, invoiceId)
        val lock = locks.computeIfAbsent(key) { ReentrantLock() }
        return lock.withLock { block(key) }
    }

    /**
     * workshop scenario 중 생성된 lock key 를 반환합니다.
     */
    fun keys(): Set<String> = locks.keys.toSet()

    /**
     * 모든 lock entry 를 지웁니다.
     */
    fun clear() {
        locks.clear()
    }
}

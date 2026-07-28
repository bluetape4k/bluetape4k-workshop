package io.bluetape4k.workshop.multitenant.service

import io.bluetape4k.workshop.multitenant.domain.TenantId
import org.springframework.stereotype.Component

/**
 * database 가 아닌 모든 isolation boundary 에 사용할 tenant-prefixed key 를 만듭니다.
 */
@Component
class TenantKeyFactory {

    /**
     * tenant scope 를 포함하는 cache key 입니다.
     */
    fun invoiceCacheKey(tenantId: TenantId, invoiceId: Long): String =
        "${tenantId.keyPrefix()}:invoice:$invoiceId"

    /**
     * tenant scope 를 의도적으로 생략하는 baseline cache key 입니다.
     */
    fun unsafeInvoiceCacheKey(invoiceId: Long): String = "invoice:$invoiceId"

    /**
     * tenant scope 를 포함하는 lock key 입니다.
     */
    fun invoiceLockKey(tenantId: TenantId, invoiceId: Long): String =
        "${tenantId.keyPrefix()}:lock:invoice:$invoiceId"

    /**
     * tenant scope 와 caller principal 을 포함하는 rate-limit key 입니다.
     */
    fun rateLimitKey(tenantId: TenantId, principal: String): String =
        "${tenantId.keyPrefix()}:rate-limit:$principal"
}

package io.bluetape4k.workshop.multitenant.domain

import io.bluetape4k.support.requireNotBlank
import java.io.Serializable

/**
 * 모든 isolation boundary 에서 사용하는 normalized tenant identifier 입니다.
 */
@JvmInline
value class TenantId(val value: String) : Serializable {

    init {
        value.requireNotBlank("tenantId")
        require(value.matches(TENANT_ID_PATTERN)) {
            "tenantId must use lowercase letters, digits, or hyphens"
        }
    }

    /**
     * tenant-scoped key 에 사용하는 안정적인 prefix 를 반환합니다.
     */
    fun keyPrefix(): String = "tenant:$value"

    override fun toString(): String = value

    companion object {
        private const val serialVersionUID: Long = 104L
        private val TENANT_ID_PATTERN = Regex("[a-z0-9][a-z0-9-]{1,62}")

        val ALPHA: TenantId = TenantId("tenant-alpha")
        val BETA: TenantId = TenantId("tenant-beta")
    }
}

package io.bluetape4k.workshop.multitenant.domain

import io.bluetape4k.support.requireNotBlank
import java.io.Serializable

/**
 * Normalized tenant identifier used in every isolation boundary.
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
     * Returns the stable prefix used for tenant-scoped keys.
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

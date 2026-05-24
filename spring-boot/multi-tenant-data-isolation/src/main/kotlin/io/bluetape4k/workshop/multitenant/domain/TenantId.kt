package io.bluetape4k.workshop.multitenant.domain

import java.io.Serializable

/**
 * Normalized tenant identifier used in every isolation boundary.
 */
@JvmInline
value class TenantId(val value: String) : Serializable {

    init {
        require(value.isNotBlank()) { "tenantId must not be blank" }
        require(value.matches(Regex("[a-z0-9][a-z0-9-]{1,62}"))) {
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

        val ALPHA: TenantId = TenantId("tenant-alpha")
        val BETA: TenantId = TenantId("tenant-beta")
    }
}

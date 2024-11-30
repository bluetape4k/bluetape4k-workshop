package io.bluetape4k.workshop.cassandra.reactive.multitenant

import java.io.Serializable

data class Tenant(
    val tenantId: String,
): Serializable

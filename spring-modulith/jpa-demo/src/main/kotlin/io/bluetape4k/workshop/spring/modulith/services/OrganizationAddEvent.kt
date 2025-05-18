package io.bluetape4k.workshop.spring.modulith.services

import java.io.Serializable

data class OrganizationAddEvent(
    val id: Long,
    val source: Any? = null,
): Serializable

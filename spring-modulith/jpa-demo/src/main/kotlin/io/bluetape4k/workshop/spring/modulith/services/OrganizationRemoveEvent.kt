package io.bluetape4k.workshop.spring.modulith.services

import java.io.Serializable

data class OrganizationRemoveEvent(
    val id: Long,
    var source: Any? = null,
): Serializable 

package io.bluetape4k.workshop.cassandra.optimisticlocking

import org.springframework.data.annotation.Id
import org.springframework.data.cassandra.core.mapping.Table
import java.io.Serializable

@Table
data class SimplePerson(
    @Id var id: Long? = null,
    var name: String? = null,
): Serializable

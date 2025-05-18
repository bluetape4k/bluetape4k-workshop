package io.bluetape4k.workshop.cassandra.optimisticlocking

import org.springframework.data.annotation.Id
import org.springframework.data.annotation.Version
import org.springframework.data.cassandra.core.mapping.Table
import java.io.Serializable

@Table
data class OptimisticPerson(
    @field:Id
    var id: Long = 0L,

    var name: String? = null,

    @field:Version
    val version: Long = 0L,
): Serializable {
    fun withName(name: String): OptimisticPerson = copy(name = name)
}

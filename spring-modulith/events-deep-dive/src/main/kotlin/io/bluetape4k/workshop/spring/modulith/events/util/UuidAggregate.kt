package io.bluetape4k.workshop.spring.modulith.events.util

import io.bluetape4k.hibernate.model.JpaEntity
import io.bluetape4k.idgenerators.uuid.TimebasedUuid
import jakarta.persistence.Id
import jakarta.persistence.MappedSuperclass
import org.springframework.data.domain.AbstractAggregateRoot
import java.io.Serializable
import java.util.*

@MappedSuperclass
class UuidAggregate<T: AbstractAggregateRoot<T>>: AbstractAggregateRoot<T>(), JpaEntity<UUID>, Serializable {

    @Id
    override var id: UUID? = TimebasedUuid.Reordered.nextId()

    override val isPersisted: Boolean = id != null

}

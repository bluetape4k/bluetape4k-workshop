package io.bluetape4k.workshop.spring.modulith.events.util

import io.bluetape4k.hibernate.model.JpaEntity
import io.bluetape4k.idgenerators.uuid.TimebasedUuid
import jakarta.persistence.Id
import jakarta.persistence.MappedSuperclass
import org.springframework.data.domain.AbstractAggregateRoot
import java.io.Serializable

@MappedSuperclass
class StringAggregate<T: AbstractAggregateRoot<T>>: AbstractAggregateRoot<T>(), JpaEntity<String>, Serializable {

    @Id
    override var id: String? = TimebasedUuid.Reordered.nextIdAsString()

    @Transient
    override val isPersisted: Boolean = id != null

}

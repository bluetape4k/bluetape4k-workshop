package io.bluetape4k.workshop.spring.modulith.events.util

import io.bluetape4k.ToStringBuilder
import io.bluetape4k.hibernate.model.JpaEntity
import io.bluetape4k.idgenerators.uuid.TimebasedUuid
import jakarta.persistence.Id
import jakarta.persistence.MappedSuperclass
import org.springframework.data.domain.AbstractAggregateRoot
import java.io.Serializable
import java.util.*

@MappedSuperclass
abstract class UuidAggregate<T: AbstractAggregateRoot<T>>
    : AbstractAggregateRoot<T>(), JpaEntity<UUID>, Serializable {

    @Id
    override var id: UUID? = TimebasedUuid.Reordered.nextId()

    // TODO: 이 방식은 Auto Generated Identifier에서만 유용한 방식이다. Entity Listener를 통한 @OnPersist 등에서 처리해야 한다 
    @Transient
    override val isPersisted: Boolean = id != null

    override fun toString(): String = buildStringHelper().toString()

    protected fun buildStringHelper(): ToStringBuilder =
        ToStringBuilder(this)
            .add("id", id)
            .add("isPersisted", isPersisted)
}

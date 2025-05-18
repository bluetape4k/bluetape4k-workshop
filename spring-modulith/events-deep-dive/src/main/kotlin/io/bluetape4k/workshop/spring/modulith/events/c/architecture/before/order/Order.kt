package io.bluetape4k.workshop.spring.modulith.events.c.architecture.before.order

import io.bluetape4k.ToStringBuilder
import io.bluetape4k.hibernate.model.AbstractJpaEntity
import io.bluetape4k.idgenerators.uuid.TimebasedUuid
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.DynamicInsert
import org.hibernate.annotations.DynamicUpdate

@Entity
@Table(name = "MyOrder")
@DynamicInsert
@DynamicUpdate
class Order: AbstractJpaEntity<String>() {

    @Id
    override var id: String? = TimebasedUuid.Reordered.nextIdAsString()
    var status: OrderStatus = OrderStatus.OPEN

    fun complete() {
        this.status = OrderStatus.COMPLETED
    }

    override fun buildStringHelper(): ToStringBuilder {
        return super.buildStringHelper()
            .add("status", status)
    }

    enum class OrderStatus {
        OPEN,
        COMPLETED;
    }
}

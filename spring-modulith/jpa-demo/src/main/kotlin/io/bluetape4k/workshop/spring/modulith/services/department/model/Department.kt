package io.bluetape4k.workshop.spring.modulith.services.department.model

import io.bluetape4k.ToStringBuilder
import io.bluetape4k.hibernate.model.LongJpaEntity
import jakarta.persistence.Entity

@Entity
class Department: LongJpaEntity() {

    var organizationId: Long? = null
    var name: String? = null

    override fun equalProperties(other: Any): Boolean {
        return other is Department &&
                this.organizationId == other.organizationId &&
                this.name == other.name
    }

    override fun buildStringHelper(): ToStringBuilder {
        return super.buildStringHelper()
            .add("organizationId", organizationId)
            .add("name", name)
    }
}

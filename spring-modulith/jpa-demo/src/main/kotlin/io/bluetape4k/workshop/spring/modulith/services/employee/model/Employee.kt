package io.bluetape4k.workshop.spring.modulith.services.employee.model

import io.bluetape4k.ToStringBuilder
import io.bluetape4k.hibernate.model.LongJpaEntity
import jakarta.persistence.Entity

@Entity
class Employee: LongJpaEntity() {

    var organizationId: Long? = null
    var departmentId: Long? = null

    var name: String? = null
    var age: Int = 0
    var position: String? = null

    override fun equalProperties(other: Any): Boolean {
        return other is Employee &&
                name == other.name
    }

    override fun buildStringHelper(): ToStringBuilder {
        return super.buildStringHelper()
            .add("organizationId", organizationId)
            .add("departmentId", departmentId)
            .add("name", name)
            .add("age", age)
            .add("position", position)
    }
}

package io.bluetape4k.workshop.spring.modulith.services.employee.model

import io.bluetape4k.hibernate.model.LongJpaEntity
import jakarta.persistence.Entity

@Entity
class Employee: LongJpaEntity() {

    var organizationId: Long? = null
    var departmentId: Long? = null

    var name: String? = null
    var age: Int = 0
    var position: String? = null
}

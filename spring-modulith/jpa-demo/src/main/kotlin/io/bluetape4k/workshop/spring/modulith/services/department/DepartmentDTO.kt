package io.bluetape4k.workshop.spring.modulith.services.department

import io.bluetape4k.workshop.spring.modulith.services.employee.EmployeeDTO
import java.io.Serializable

data class DepartmentDTO(
    val id: Long? = null,
    val organizationId: Long,
    val name: String,
): Serializable {
    val employees: MutableList<EmployeeDTO> = mutableListOf()
}

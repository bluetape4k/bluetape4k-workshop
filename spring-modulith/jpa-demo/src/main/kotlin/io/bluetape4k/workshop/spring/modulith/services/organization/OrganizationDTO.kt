package io.bluetape4k.workshop.spring.modulith.services.organization

import io.bluetape4k.workshop.spring.modulith.services.department.DepartmentDTO
import io.bluetape4k.workshop.spring.modulith.services.employee.EmployeeDTO
import java.io.Serializable

data class OrganizationDTO(
    val id: Long? = null,
    val name: String,
    val address: String,
    val departments: MutableList<DepartmentDTO> = mutableListOf(),
    val employees: MutableList<EmployeeDTO> = mutableListOf(),
): Serializable

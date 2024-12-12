package io.bluetape4k.workshop.spring.modulith.services.employee.repository

import io.bluetape4k.workshop.spring.modulith.services.employee.EmployeeDTO
import io.bluetape4k.workshop.spring.modulith.services.employee.model.Employee
import org.springframework.data.repository.CrudRepository

interface EmployeeRepository: CrudRepository<Employee, Long> {

    fun findByDepartmentId(departmentId: Long): MutableList<EmployeeDTO>
    fun findByOrganizationId(organizationId: Long): MutableList<EmployeeDTO>

    fun deleteByOrganizationId(organizationId: Long)
}

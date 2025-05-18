package io.bluetape4k.workshop.spring.modulith.services.employee.repository

import io.bluetape4k.workshop.spring.modulith.services.employee.EmployeeDTO
import io.bluetape4k.workshop.spring.modulith.services.employee.model.Employee
import org.springframework.data.jpa.repository.JpaRepository

interface EmployeeRepository: JpaRepository<Employee, Long> {

    fun findByDepartmentId(departmentId: Long): List<EmployeeDTO>
    fun findByOrganizationId(organizationId: Long): List<EmployeeDTO>

    fun deleteByOrganizationId(organizationId: Long)
}

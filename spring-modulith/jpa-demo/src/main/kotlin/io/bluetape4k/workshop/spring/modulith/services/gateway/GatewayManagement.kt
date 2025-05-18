package io.bluetape4k.workshop.spring.modulith.services.gateway

import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.spring.modulith.services.department.DepartmentDTO
import io.bluetape4k.workshop.spring.modulith.services.department.DepartmentExternalAPI
import io.bluetape4k.workshop.spring.modulith.services.employee.EmployeeDTO
import io.bluetape4k.workshop.spring.modulith.services.employee.EmployeeExternalAPI
import io.bluetape4k.workshop.spring.modulith.services.organization.OrganizationDTO
import io.bluetape4k.workshop.spring.modulith.services.organization.OrganizationExternalAPI
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api")
class GatewayManagement(
    private val organizationExternalAPI: OrganizationExternalAPI,
    private val departmentExternalAPI: DepartmentExternalAPI,
    private val employeeExternalAPI: EmployeeExternalAPI,
) {
    companion object: KLogging()

    @GetMapping("/organizations/{id}/with-departments")
    fun getOrganizationWithDepartments(@PathVariable("id") id: Long): OrganizationDTO? {
        return organizationExternalAPI.findByIdWithDepartments(id)
    }

    @GetMapping("/organizations/{id}/with-departments-and-employees")
    fun getOrganizationWithDepartmentsAndEmployees(@PathVariable("id") id: Long): OrganizationDTO? {
        return organizationExternalAPI.findByIdWithDepartmentsAndEmployees(id)
    }

    @GetMapping("/departments/{id}/with-employees")
    fun apiDepartmentWithEmployees(@PathVariable("id") id: Long): DepartmentDTO? {
        return departmentExternalAPI.getDepartmentByIdWithEmployees(id)
    }

    @PostMapping("/organizations")
    fun apiAddOrganization(@RequestBody organizationDTO: OrganizationDTO): OrganizationDTO {
        return organizationExternalAPI.add(organizationDTO)
    }

    @PostMapping("/departments")
    fun apiAddDepartment(@RequestBody departmentDTO: DepartmentDTO): DepartmentDTO {
        return departmentExternalAPI.add(departmentDTO)
    }

    @RequestMapping("/employees")
    fun apiAddEmployee(@RequestBody employeeDTO: EmployeeDTO): EmployeeDTO {
        return employeeExternalAPI.add(employeeDTO)
    }

}

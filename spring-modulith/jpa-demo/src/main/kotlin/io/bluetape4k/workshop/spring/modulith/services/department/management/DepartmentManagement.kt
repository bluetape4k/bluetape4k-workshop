package io.bluetape4k.workshop.spring.modulith.services.department.management

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.info
import io.bluetape4k.workshop.spring.modulith.services.OrganizationAddEvent
import io.bluetape4k.workshop.spring.modulith.services.OrganizationRemoveEvent
import io.bluetape4k.workshop.spring.modulith.services.department.DepartmentDTO
import io.bluetape4k.workshop.spring.modulith.services.department.DepartmentExternalAPI
import io.bluetape4k.workshop.spring.modulith.services.department.DepartmentInternalAPI
import io.bluetape4k.workshop.spring.modulith.services.department.mapper.DepartmentMapper
import io.bluetape4k.workshop.spring.modulith.services.department.repository.DepartmentRepository
import io.bluetape4k.workshop.spring.modulith.services.employee.EmployeeInternalAPI
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Service

@Service
class DepartmentManagement(
    private val repository: DepartmentRepository,
    private val employeeInternalAPI: EmployeeInternalAPI,
    private val mapper: DepartmentMapper,
): DepartmentInternalAPI, DepartmentExternalAPI {

    companion object: KLogging()

    override fun getDepartmentByIdWithEmployees(departmentId: Long): DepartmentDTO {
        log.debug { "Get department by id with employees: $departmentId" }
        val department = repository.findDTOById(departmentId) ?: throw IllegalArgumentException("Department not found")
        val employees = employeeInternalAPI.getEmployeesByDepartmentId(departmentId)

        department.employees.addAll(employees)
        return department
    }

    override fun add(department: DepartmentDTO): DepartmentDTO {
        val saved = repository.save(mapper.toEntity(department))
        return mapper.toDTO(saved)
    }

    override fun getDepartmentsByOrganizationId(organizationId: Long): List<DepartmentDTO> {
        return repository.findDTOByOrganizationId(organizationId)
    }

    override fun getDepartmentsByOrganizationIdWithEmployees(organizationId: Long): List<DepartmentDTO> {
        return repository.findDTOByOrganizationId(organizationId)
            .map { dept ->
                val employees = employeeInternalAPI.getEmployeesByDepartmentId(dept.id!!)
                dept.employees.addAll(employees)
                dept
            }
    }

    @ApplicationModuleListener
    fun onNewOrganizationEvent(event: OrganizationAddEvent) {
        log.info { "조직[${event.id}]에 2개의 부서를 생성합니다..." }
        add(DepartmentDTO(organizationId = event.id, name = "HR"))
        add(DepartmentDTO(organizationId = event.id, name = "Management"))
        log.info { "조직[${event.id}]에 2개의 부서를 생성했습니다." }
    }

    @ApplicationModuleListener
    fun onRemoveOrganizationEvent(event: OrganizationRemoveEvent) {
        log.info { "조직[${event.id}]에 속한 모두 부서를 삭제합니다..." }
        repository.deleteByOrganizationId(event.id)
        log.info { "조직[${event.id}]에 속한 모두 부서를 삭제했습니다." }
    }
}

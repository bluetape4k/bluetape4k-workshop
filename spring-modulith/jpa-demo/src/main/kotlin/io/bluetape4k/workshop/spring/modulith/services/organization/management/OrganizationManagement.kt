package io.bluetape4k.workshop.spring.modulith.services.organization.management

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.workshop.spring.modulith.services.OrganizationAddEvent
import io.bluetape4k.workshop.spring.modulith.services.OrganizationRemoveEvent
import io.bluetape4k.workshop.spring.modulith.services.department.DepartmentInternalAPI
import io.bluetape4k.workshop.spring.modulith.services.employee.EmployeeInternalAPI
import io.bluetape4k.workshop.spring.modulith.services.organization.OrganizationDTO
import io.bluetape4k.workshop.spring.modulith.services.organization.OrganizationExternalAPI
import io.bluetape4k.workshop.spring.modulith.services.organization.mapper.OrganizationMapper
import io.bluetape4k.workshop.spring.modulith.services.organization.repository.OrganizationRepository
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class OrganizationManagement(
    private val events: ApplicationEventPublisher,
    private val repository: OrganizationRepository,
    private val departmentInternalAPI: DepartmentInternalAPI,
    private val employeeInternalAPI: EmployeeInternalAPI,
    private val mapper: OrganizationMapper,
): OrganizationExternalAPI {

    companion object: KLogging()

    override fun findByIdWithEmployees(organizationId: Long): OrganizationDTO? {
        return repository.findDTOById(organizationId)
            ?.apply {
                val emps = employeeInternalAPI.getEmployeesByOrganizationId(organizationId)
                this.employees.addAll(emps)
            }
    }

    override fun findByIdWithDepartments(organizationId: Long): OrganizationDTO? {
        return repository.findDTOById(organizationId)
            ?.apply {
                val deps = departmentInternalAPI.getDepartmentsByOrganizationId(organizationId)
                this.departments.addAll(deps)
            }
    }

    override fun findByIdWithDepartmentsAndEmployees(organizationId: Long): OrganizationDTO? {
        return findByIdWithDepartments(organizationId)
            ?.apply {
                val deps = departmentInternalAPI.getDepartmentsByOrganizationId(organizationId)
                this.departments.addAll(deps)
            }
    }

    @Transactional
    override fun add(organizationDTO: OrganizationDTO): OrganizationDTO {
        log.info { "Adding organization: $organizationDTO" }

        val organization = mapper.toEntity(organizationDTO)
        val dto = mapper.toDTO(repository.save(organization))
        events.publishEvent(OrganizationAddEvent(dto.id!!, dto))
        return dto
    }

    @Transactional
    override fun remove(organizationId: Long) {
        log.info { "Removing organization: $organizationId" }

        repository.deleteById(organizationId)
        events.publishEvent(OrganizationRemoveEvent(organizationId))
    }

}

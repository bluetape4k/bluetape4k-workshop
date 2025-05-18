package io.bluetape4k.workshop.spring.modulith.services.employee.management

import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.spring.modulith.services.employee.EmployeeDTO
import io.bluetape4k.workshop.spring.modulith.services.employee.EmployeeExternalAPI
import io.bluetape4k.workshop.spring.modulith.services.employee.EmployeeInternalAPI
import io.bluetape4k.workshop.spring.modulith.services.employee.mapper.EmployeeMapper
import io.bluetape4k.workshop.spring.modulith.services.employee.repository.EmployeeRepository
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class EmployeeManagement(
    private val repository: EmployeeRepository,
    private val mapper: EmployeeMapper,
): EmployeeExternalAPI, EmployeeInternalAPI {

    companion object: KLogging()

    @Transactional
    override fun add(employee: EmployeeDTO): EmployeeDTO {
        val emp = mapper.toEntity(employee)
        return mapper.toDTO(repository.save(emp))
    }

    override fun getEmployeesByDepartmentId(departmentId: Long): List<EmployeeDTO> {
        return repository.findByDepartmentId(departmentId)
    }

    override fun getEmployeesByOrganizationId(organizationId: Long): List<EmployeeDTO> {
        return repository.findByOrganizationId(organizationId)
    }

    @ApplicationModuleListener
    fun onRemovedOrganization(organizationId: Long) {
        repository.deleteByOrganizationId(organizationId)
    }
}

package io.bluetape4k.workshop.spring.modulith.services.department.repository

import io.bluetape4k.workshop.spring.modulith.services.department.DepartmentDTO
import io.bluetape4k.workshop.spring.modulith.services.department.model.Department
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface DepartmentRepository: JpaRepository<Department, Long> {

    @Query(
        """
        SELECT new io.bluetape4k.workshop.spring.modulith.services.department.DepartmentDTO(        
            d.id,
            d.organizationId,
            d.name
        ) 
        FROM Department d
        WHERE d.id = :id
        """
    )
    fun findDTOById(id: Long): DepartmentDTO?

    @Query(
        """
        SELECT new io.bluetape4k.workshop.spring.modulith.services.department.DepartmentDTO(        
            d.id,
            d.organizationId,
            d.name
        ) 
        FROM Department d
        WHERE d.organizationId = :organizationId
        """
    )
    fun findByOrganizationId(organizationId: Long): List<DepartmentDTO>

    fun deleteByOrganizationId(organizationId: Long)
}

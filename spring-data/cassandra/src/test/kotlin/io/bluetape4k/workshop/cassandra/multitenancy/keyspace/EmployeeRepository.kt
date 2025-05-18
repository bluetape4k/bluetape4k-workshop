package io.bluetape4k.workshop.cassandra.multitenancy.keyspace

import kotlinx.coroutines.flow.Flow
import org.springframework.data.cassandra.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

interface EmployeeRepository: CoroutineCrudRepository<Employee, String> {

    @Query("SELECT * FROM #{getTenantId()}.ks_mt_emp WHERE name = :name")
    fun findAllByName(name: String): Flow<Employee>

}

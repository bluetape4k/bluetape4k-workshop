package io.bluetape4k.workshop.cassandra.multitenancy.row

import org.springframework.data.repository.kotlin.CoroutineCrudRepository

interface EmployeeRepository: CoroutineCrudRepository<Employee, String>, RowAwareEmployeeRepository

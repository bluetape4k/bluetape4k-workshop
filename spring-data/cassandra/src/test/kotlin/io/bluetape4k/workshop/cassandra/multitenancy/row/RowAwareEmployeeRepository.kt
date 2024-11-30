package io.bluetape4k.workshop.cassandra.multitenancy.row

import kotlinx.coroutines.flow.Flow

interface RowAwareEmployeeRepository {

    fun findAllByName(name: String): Flow<Employee>
}

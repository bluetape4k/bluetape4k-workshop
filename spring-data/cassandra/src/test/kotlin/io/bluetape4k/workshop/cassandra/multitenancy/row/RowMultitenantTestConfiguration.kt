package io.bluetape4k.workshop.cassandra.multitenancy.row

import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.cassandra.AbstractReactiveCassandraTestConfiguration
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.data.cassandra.repository.config.EnableReactiveCassandraRepositories

@EntityScan(basePackageClasses = [Employee::class])
@EnableReactiveCassandraRepositories(basePackageClasses = [EmployeeRepository::class])
class RowMultitenantTestConfiguration: AbstractReactiveCassandraTestConfiguration() {

    companion object: KLogging()

}

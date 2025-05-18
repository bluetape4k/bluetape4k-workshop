package io.bluetape4k.workshop.cassandra.projection

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.workshop.cassandra.AbstractReactiveCassandraTestConfiguration
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.data.cassandra.repository.config.EnableCassandraRepositories

@EntityScan(basePackageClasses = [Customer::class])
@EnableCassandraRepositories(basePackageClasses = [CustomerRepository::class])
class ProjectionTestConfiguration: AbstractReactiveCassandraTestConfiguration() {

    companion object: KLoggingChannel()
}

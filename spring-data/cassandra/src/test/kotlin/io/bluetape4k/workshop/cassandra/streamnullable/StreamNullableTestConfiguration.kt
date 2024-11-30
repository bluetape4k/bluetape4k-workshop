package io.bluetape4k.workshop.cassandra.streamnullable

import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.cassandra.AbstractReactiveCassandraTestConfiguration
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.data.cassandra.repository.config.EnableCassandraRepositories

@EntityScan(basePackageClasses = [Person::class])
@EnableCassandraRepositories(basePackageClasses = [PersonRepository::class])
class StreamNullableTestConfiguration: AbstractReactiveCassandraTestConfiguration() {

    companion object: KLogging()
}

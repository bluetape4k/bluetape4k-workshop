package io.bluetape4k.workshop.cassandra.optimisticlocking

import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.cassandra.AbstractReactiveCassandraTestConfiguration
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.data.cassandra.repository.config.EnableReactiveCassandraRepositories

@EntityScan(basePackageClasses = [OptimisticPerson::class])
@EnableReactiveCassandraRepositories(basePackageClasses = [OptimisticPersonRepository::class])
class OptimisticLockTestConfiguration: AbstractReactiveCassandraTestConfiguration() {

    companion object: KLogging()

}

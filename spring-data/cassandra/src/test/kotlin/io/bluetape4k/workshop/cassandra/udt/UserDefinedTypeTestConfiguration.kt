package io.bluetape4k.workshop.cassandra.udt

import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.cassandra.AbstractReactiveCassandraTestConfiguration
import org.springframework.boot.autoconfigure.domain.EntityScan

@EntityScan(basePackageClasses = [Person::class])
class UserDefinedTypeTestConfiguration: AbstractReactiveCassandraTestConfiguration() {

    companion object: KLogging()
}

package io.bluetape4k.workshop.cassandra.optimisticlocking

import org.springframework.data.repository.kotlin.CoroutineCrudRepository

interface OptimisticPersonRepository: CoroutineCrudRepository<OptimisticPerson, Long> 

package io.bluetape4k.workshop.cassandra.auditing

import org.springframework.data.repository.kotlin.CoroutineCrudRepository

interface AuditedPersonRepository: CoroutineCrudRepository<AuditedPerson, Long>

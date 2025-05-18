package io.bluetape4k.workshop.cassandra.reactive.auditing

import org.springframework.data.repository.kotlin.CoroutineCrudRepository

interface CustomAuditingRepository: CoroutineCrudRepository<CustomAuditableOrder, String> 

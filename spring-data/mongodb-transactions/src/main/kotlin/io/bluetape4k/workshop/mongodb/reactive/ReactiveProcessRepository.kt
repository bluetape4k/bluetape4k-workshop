package io.bluetape4k.workshop.mongodbdb.reactive

import io.bluetape4k.workshop.mongodbdb.Process
import org.springframework.data.repository.reactive.ReactiveCrudRepository

interface ReactiveProcessRepository: ReactiveCrudRepository<Process, Int>

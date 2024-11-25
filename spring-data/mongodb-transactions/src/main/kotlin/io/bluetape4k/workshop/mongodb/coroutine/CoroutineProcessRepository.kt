package io.bluetape4k.workshop.mongodbdb.coroutine

import io.bluetape4k.workshop.mongodbdb.Process
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

interface CoroutineProcessRepository: CoroutineCrudRepository<Process, Int>

package io.bluetape4k.workshop.mongodbdb.imperative

import io.bluetape4k.workshop.mongodbdb.Process
import org.springframework.data.repository.CrudRepository

interface ProcessRepository: CrudRepository<Process, Int>

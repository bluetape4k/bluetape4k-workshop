package io.bluetape4k.workshop.leader.backendcomparison

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/**
 * Spring Boot entry point for the leader backend comparison lab.
 */
@SpringBootApplication
class BackendComparisonLabApp

fun main(args: Array<String>) {
    runApplication<BackendComparisonLabApp>(*args)
}

package io.bluetape4k.workshop.multitenant

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/**
 * Spring Boot entrypoint for the multi-tenant data isolation workshop.
 */
@SpringBootApplication
class MultiTenantDataIsolationApplication

fun main(args: Array<String>) {
    runApplication<MultiTenantDataIsolationApplication>(*args)
}

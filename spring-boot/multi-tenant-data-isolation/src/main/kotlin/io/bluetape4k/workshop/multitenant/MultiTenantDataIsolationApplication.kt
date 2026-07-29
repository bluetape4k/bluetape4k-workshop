package io.bluetape4k.workshop.multitenant

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/**
 * multi-tenant data isolation workshop 의 Spring Boot entrypoint 입니다.
 */
@SpringBootApplication
class MultiTenantDataIsolationApplication

fun main(args: Array<String>) {
    runApplication<MultiTenantDataIsolationApplication>(*args)
}

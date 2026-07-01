package io.bluetape4k.workshop.spring.modulith.ddd.audit

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class DddOrderAuditApplication

fun main(args: Array<String>) {
    runApplication<DddOrderAuditApplication>(*args)
}

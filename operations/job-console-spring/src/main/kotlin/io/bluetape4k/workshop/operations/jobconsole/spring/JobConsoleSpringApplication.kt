package io.bluetape4k.workshop.operations.jobconsole.spring

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@EnableScheduling
@SpringBootApplication
class JobConsoleSpringApplication

fun main(args: Array<String>) {
    runApplication<JobConsoleSpringApplication>(*args)
}

package io.bluetape4k.workshop.operations.jobconsole.spring

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@EnableScheduling
@SpringBootApplication
/**
 * Spring Boot entry point for the job operations console demo.
 *
 * The `demo` profile is an explicit safety boundary: without it the
 * controller, scheduler, and UI routes are not registered. Start it with
 * `SPRING_PROFILES_ACTIVE=demo` after PostgreSQL is available.
 * ```bash
 * ./gradlew :operations-job-console-spring:bootRun
 * curl -s http://localhost:8080/readyz
 * ```
 */
class JobConsoleSpringApplication

/** Starts the Spring MVC adapter with the caller-provided JVM arguments. */
/** Starts the Spring MVC adapter with the caller-provided JVM arguments. */
fun main(args: Array<String>) {
    runApplication<JobConsoleSpringApplication>(*args)
}

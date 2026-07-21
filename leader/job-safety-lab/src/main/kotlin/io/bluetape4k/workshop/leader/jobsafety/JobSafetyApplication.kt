package io.bluetape4k.workshop.leader.jobsafety

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

/** Boots the Java 25 Spring MVC leader job-safety lab. */
@SpringBootApplication
@ConfigurationPropertiesScan
class JobSafetyApplication {
    companion object : KLogging()
}

/** Starts the leader job-safety lab. */
fun main(args: Array<String>) {
    runApplication<JobSafetyApplication>(*args)
    JobSafetyApplication.log.info { "leader_job_safety_lab_started" }
}

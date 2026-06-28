package io.bluetape4k.workshop.leader.k8slease

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/**
 * Spring Boot entry point for the Kubernetes Lease and Micrometer workshop.
 */
@SpringBootApplication
class K8sLeaseMicrometerApp

fun main(args: Array<String>) {
    runApplication<K8sLeaseMicrometerApp>(*args)
}

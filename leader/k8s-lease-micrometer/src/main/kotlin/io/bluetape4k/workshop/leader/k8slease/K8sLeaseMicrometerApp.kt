package io.bluetape4k.workshop.leader.k8slease

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/**
 * Kubernetes Lease와 Micrometer 워크숍의 Spring Boot 진입점입니다.
 */
@SpringBootApplication
class K8sLeaseMicrometerApp

fun main(args: Array<String>) {
    runApplication<K8sLeaseMicrometerApp>(*args)
}

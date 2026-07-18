package io.bluetape4k.workshop.commerce.reservation

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

/** Boots the Java 25 MVC reference application and enables the bounded expiry scheduler. */
@EnableScheduling
@SpringBootApplication
internal class ReservationControlPlaneApplication {
    companion object : KLogging()
}

/** Starts the reservation control plane with Spring Boot's virtual-thread-aware runtime. */
fun main(args: Array<String>) {
    runApplication<ReservationControlPlaneApplication>(*args)
    ReservationControlPlaneApplication.log.info { "reservation_control_plane_started" }
}

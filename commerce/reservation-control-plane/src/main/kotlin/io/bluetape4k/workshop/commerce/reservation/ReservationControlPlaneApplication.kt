package io.bluetape4k.workshop.commerce.reservation

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@EnableScheduling
@SpringBootApplication
internal class ReservationControlPlaneApplication {
    companion object : KLogging()
}

fun main(args: Array<String>) {
    runApplication<ReservationControlPlaneApplication>(*args)
    ReservationControlPlaneApplication.log.info { "reservation_control_plane_started" }
}

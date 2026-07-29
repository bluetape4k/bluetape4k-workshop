package io.bluetape4k.workshop.commerce.reservation

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

/** Java 25 MVC reference application을 기동하고 bounded expiry scheduler를 활성화합니다. */
@EnableScheduling
@SpringBootApplication
internal class ReservationControlPlaneApplication {
    companion object : KLogging()
}

/** Spring Boot의 virtual-thread-aware runtime으로 reservation control plane을 시작합니다. */
fun main(args: Array<String>) {
    runApplication<ReservationControlPlaneApplication>(*args)
    ReservationControlPlaneApplication.log.info { "reservation_control_plane_started" }
}

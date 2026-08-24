package io.bluetape4k.workshop.optimization.shiftcoverage.config

import io.bluetape4k.support.requirePositiveNumber
import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration
import java.time.ZoneOffset

@ConfigurationProperties("shift-coverage")
data class ShiftCoverageProperties(
    val profile: String = "demo",
    val bindAddress: String = "127.0.0.1",
    val maxBodyBytes: Int = 256 * 1024,
    val pageSize: Int = 100,
    val mutationAdmission: Int = 8,
    val plannerWorkers: Int = 4,
    val plannerQueue: Int = 8,
    val outboxWorkers: Int = 4,
    val outboxBatchSize: Int = 10,
    val outboxLease: Duration = Duration.ofSeconds(30),
    val ioTimeout: Duration = Duration.ofSeconds(5),
    val shutdownDrain: Duration = Duration.ofSeconds(30),
    val zoneOffset: String = ZoneOffset.UTC.id,
) {
    init {
        maxBodyBytes.requirePositiveNumber("shift-coverage.maxBodyBytes")
        pageSize.requirePositiveNumber("shift-coverage.pageSize")
        mutationAdmission.requirePositiveNumber("shift-coverage.mutationAdmission")
        plannerWorkers.requirePositiveNumber("shift-coverage.plannerWorkers")
        plannerQueue.requirePositiveNumber("shift-coverage.plannerQueue")
        outboxWorkers.requirePositiveNumber("shift-coverage.outboxWorkers")
        outboxBatchSize.requirePositiveNumber("shift-coverage.outboxBatchSize")
        require(!outboxLease.isZero && !outboxLease.isNegative) {
            "shift-coverage.outboxLease must be positive"
        }
        require(!ioTimeout.isZero && !ioTimeout.isNegative) {
            "shift-coverage.ioTimeout must be positive"
        }
        require(!shutdownDrain.isZero && !shutdownDrain.isNegative) {
            "shift-coverage.shutdownDrain must be positive"
        }
    }
}

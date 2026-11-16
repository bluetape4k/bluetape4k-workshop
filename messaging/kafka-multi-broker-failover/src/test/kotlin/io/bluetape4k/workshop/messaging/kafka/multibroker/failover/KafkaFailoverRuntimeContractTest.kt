package io.bluetape4k.workshop.messaging.kafka.multibroker.failover

import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.SpringBootApplication

/**
 * reference 애플리케이션의 실행 경계를 검증합니다.
 *
 * 이 테스트는 broker를 시작하지 않고도 애플리케이션이 shell로만
 * 등록되며, 외부 bootstrap 설정을 암묵적으로 읽지 않는지 확인합니다.
 */
class KafkaFailoverRuntimeContractTest {

    private companion object: KLogging() {
        const val APPLICATION_CLASS_NAME =
            "io.bluetape4k.workshop.messaging.kafka.multibroker.failover.KafkaMultiBrokerFailoverApplication"
        const val MAIN_CLASS_NAME =
            "io.bluetape4k.workshop.messaging.kafka.multibroker.failover.KafkaMultiBrokerFailoverApplicationKt"
    }

    @Test
    fun `runtime is a headless self-contained spring shell`() {
        val applicationType = runCatching {
            Class.forName(APPLICATION_CLASS_NAME)
        }.getOrNull()

        val mainType = runCatching {
            Class.forName(MAIN_CLASS_NAME)
        }.getOrNull()

        applicationType.shouldNotBeNull()
        mainType.shouldNotBeNull()

        applicationType?.isAnnotationPresent(SpringBootApplication::class.java).shouldBeTrue()

        val applicationYaml = javaClass.classLoader
            .getResourceAsStream("application.yml")
            ?.bufferedReader()
            ?.use { it.readText() }
            .orEmpty()

        log.debug { "applicationYaml=$applicationYaml" }
        applicationYaml shouldContain "web-application-type: none"
        applicationYaml shouldNotContain "management"
        applicationYaml shouldNotContain "spring.kafka.bootstrap"
        applicationYaml shouldNotContain "KAFKA"
    }
}

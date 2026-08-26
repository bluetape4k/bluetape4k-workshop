package io.bluetape4k.workshop.messaging.kafka.multibroker.failover

import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.SpringBootApplication

/**
 * reference 애플리케이션의 실행 경계를 검증합니다.
 *
 * 이 테스트는 broker를 시작하지 않고도 애플리케이션이 shell로만
 * 등록되며, 외부 bootstrap 설정을 암묵적으로 읽지 않는지 확인합니다.
 */
class KafkaFailoverRuntimeContractTest {

    @Test
    fun `runtime is a headless self-contained spring shell`() {
        val applicationType = runCatching {
            Class.forName(APPLICATION_CLASS_NAME)
        }.getOrNull()
        val mainType = runCatching {
            Class.forName(MAIN_CLASS_NAME)
        }.getOrNull()

        (applicationType != null).shouldBeTrue()
        (mainType != null).shouldBeTrue()
        applicationType?.isAnnotationPresent(SpringBootApplication::class.java).shouldBeTrue()

        val applicationYaml = javaClass.classLoader
            .getResourceAsStream("application.yml")
            ?.bufferedReader()
            ?.use { it.readText() }
            .orEmpty()

        applicationYaml.contains("web-application-type: none").shouldBeTrue()
        applicationYaml.contains("management").shouldBeFalse()
        applicationYaml.contains("spring.kafka.bootstrap").shouldBeFalse()
        applicationYaml.contains("KAFKA").shouldBeFalse()
    }

    private companion object {
        const val APPLICATION_CLASS_NAME =
            "io.bluetape4k.workshop.messaging.kafka.multibroker.failover.KafkaMultiBrokerFailoverApplication"
        const val MAIN_CLASS_NAME =
            "io.bluetape4k.workshop.messaging.kafka.multibroker.failover.KafkaMultiBrokerFailoverApplicationKt"
    }
}

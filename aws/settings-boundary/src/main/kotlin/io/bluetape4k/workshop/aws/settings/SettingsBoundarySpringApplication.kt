package io.bluetape4k.workshop.aws.settings

import io.bluetape4k.aws.spring.AwsSyncClientCustomizer
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.bootstrap.BootstrapRegistry
import org.springframework.boot.bootstrap.BootstrapRegistryInitializer
import java.time.Duration

/** Spring Boot 소비자 예제의 ConfigData bootstrap 경계를 보여줍니다. */
@SpringBootApplication
class SettingsBoundarySpringApplication

/**
 * AppConfig Data ConfigData client에는 bootstrap 단계에서도 운영 timeout을
 * 적용합니다. 자격 증명은 실제 실행 환경의 기본 AWS provider chain을 따릅니다.
 */
fun main(args: Array<String>) {
    val application = SpringApplication(SettingsBoundarySpringApplication::class.java)
    application.addBootstrapRegistryInitializer(
        BootstrapRegistryInitializer { registry ->
            registry.register(
                AwsSyncClientCustomizer::class.java,
                BootstrapRegistry.InstanceSupplier.of(
                    appConfigTimeoutCustomizer(
                        apiCallTimeout = Duration.ofSeconds(10),
                        apiCallAttemptTimeout = Duration.ofSeconds(5),
                    ),
                ),
            )
        },
    )
    application.run(*args)
}

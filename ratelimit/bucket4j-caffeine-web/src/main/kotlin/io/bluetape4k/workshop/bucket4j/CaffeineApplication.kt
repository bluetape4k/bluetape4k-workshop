package io.bluetape4k.workshop.bucket4j

import io.bluetape4k.logging.KLogging
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.cache.annotation.EnableCaching

/**
 * local Caffeine 기반 Bucket4j WebMVC 예제의 Spring Boot entry point입니다.
 *
 * Bucket4j starter가 servlet rate-limit bucket을 설정된 Caffeine JCache cache에 저장할 수 있도록
 * Spring Cache를 활성화합니다.
 */
@SpringBootApplication(proxyBeanMethods = false)
@EnableCaching
class CaffeineApplication {
    companion object : KLogging()
}

fun main(vararg args: String) {
    runApplication<CaffeineApplication>(*args)
}

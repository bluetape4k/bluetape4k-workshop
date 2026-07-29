package io.bluetape4k.workshop.imageprocessing.profile

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
/**
 * 프로필 이미지 검수 워크숍 예제의 Spring Boot 진입점입니다.
 */
class ProfileImageModerationApplication

/**
 * 프로필 이미지 검수 예제 애플리케이션을 실행합니다.
 */
fun main(args: Array<String>) {
    runApplication<ProfileImageModerationApplication>(*args)
}

plugins {
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.graalvm.native)
}

springBoot {
    mainClass.set("io.bluetape4k.workshop.bucket4j.RateLimiterWebfluxApplicationKt")
    buildInfo {
        properties {
            additional.put("name", "Rate Limiter Application")
            additional.put("description", "Rate Limit per User")
            version = "1.0.0"
            additional.put("java.version", JavaVersion.current())
        }
    }
}


configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    implementation(libs.bluetape4k.core)
    implementation(libs.bluetape4k.jackson3)
    // implementation(libs.bluetape4k.spring.boot3) 는 필요 시 Spring Boot 3 호환성 확인에 사용합니다.

    // Bucket4j 의존성입니다.
    implementation(libs.bluetape4k.bucket4j)
    implementation(libs.bucket4j.core)
    implementation(libs.bucket4j.lettuce)
    implementation(libs.bucket4j.redisson)
    implementation(libs.commons.pool2)

    // Redis 의존성입니다.
    implementation(libs.bluetape4k.redis)
    implementation(libs.lettuce.core)
    implementation(libs.redisson.lib)
    implementation(libs.bluetape4k.testcontainers)

    // Spring Boot 의존성입니다.
    implementation(libs.spring.boot.autoconfigure.lib)
    annotationProcessor(libs.spring.boot.autoconfigure.processor)
    annotationProcessor(libs.spring.boot.configuration.processor)
    runtimeOnly(libs.spring.boot.devtools)

    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.aspectj)
    implementation(libs.spring.boot.starter.cache.lib)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.webflux.lib)
    testImplementation(libs.spring.boot.starter.webflux.test)
    testImplementation(libs.spring.boot.starter.test) {
        exclude(group = "junit", module = "junit")
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
        exclude(module = "mockito-core")
    }

    // coroutine 의존성입니다.
    implementation(libs.bluetape4k.coroutines)
    implementation(libs.kotlinx.coroutines.core.lib)
    implementation(libs.kotlinx.coroutines.reactor)
    testImplementation(libs.kotlinx.coroutines.test.lib)

    // Reactor 의존성입니다.
    implementation(libs.reactor.netty)
    implementation(libs.reactor.kotlin.extensions)
    testImplementation(libs.reactor.test)

    // observability 의존성입니다.
    implementation(libs.micrometer.core)
    implementation(libs.micrometer.observation.lib)
    testImplementation(libs.micrometer.observation.test)

    // SpringDoc - OpenAPI 3.0 문서화 의존성입니다.
    implementation(libs.springdoc.openapi.starter.webflux.ui)
}

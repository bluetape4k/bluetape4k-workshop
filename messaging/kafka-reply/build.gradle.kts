plugins {
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
}

springBoot {
    mainClass.set("io.bluetape4k.workshop.kafka.KafkaApplicationKt")
}

configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    // Kafka 의존성입니다.
    api(libs.kafka.clients)
    compileOnly(libs.kafka.metadata)
    compileOnly(libs.kafka.streams.lib)

    implementation(libs.spring.kafka.lib)
    implementation(libs.spring.kafka.test)
    implementation(libs.spring.data.commons)

    implementation(libs.bluetape4k.kafka4)
    implementation(libs.bluetape4k.core)
    implementation(libs.bluetape4k.testcontainers)
    implementation(libs.testcontainers.kafka)

    // Jackson 직렬화 의존성입니다.
    api(libs.bluetape4k.jackson3)
    api(libs.jackson3.databind)
    api(libs.jackson3.module.kotlin)
    api(libs.jackson3.module.blackbird)

    // coroutine 의존성입니다.
    implementation(libs.bluetape4k.coroutines)
    implementation(libs.kotlinx.coroutines.core.lib)
    implementation(libs.kotlinx.coroutines.reactor)
    testImplementation(libs.kotlinx.coroutines.test.lib)

    // Reactor 의존성입니다.
    compileOnly(libs.reactor.kafka)
    compileOnly(libs.reactor.kotlin.extensions)
    testImplementation(libs.reactor.test)

    implementation(libs.bluetape4k.spring.boot4.core)

    // Spring Boot 의존성입니다.
    implementation(libs.spring.boot.autoconfigure.lib)
    annotationProcessor(libs.spring.boot.autoconfigure.processor)
    annotationProcessor(libs.spring.boot.configuration.processor)
    // runtimeOnly(libs.spring.boot.devtools) 는 개발 중 필요할 때만 사용합니다.

    implementation(libs.spring.boot.starter.webflux.lib)

    testImplementation(libs.spring.boot.starter.test) {
        exclude(group = "junit", module = "junit")
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
        exclude(module = "mockito-core")
    }
}

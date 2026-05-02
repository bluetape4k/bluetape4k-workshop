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
    // Kafka
    api(libs.kafka.clients)
    compileOnly(libs.kafka.metadata)
    compileOnly(libs.kafka.streams.lib)

    implementation(libs.spring.kafka.lib)
    implementation(libs.spring.kafka.test)
    implementation(libs.spring.data.commons)

    // implementation(libs.bluetape4k.kafka)
    implementation(libs.bluetape4k.testcontainers)
    implementation(libs.testcontainers.kafka)

    // Jackson
    api(libs.bluetape4k.jackson2)
    api(libs.jackson.databind)
    api(libs.jackson.module.kotlin)
    api(libs.jackson.module.blackbird)

    // Coroutines
    implementation(libs.bluetape4k.coroutines)
    implementation(libs.kotlinx.coroutines.core.lib)
    implementation(libs.kotlinx.coroutines.reactor)
    testImplementation(libs.kotlinx.coroutines.test.lib)

    // Reactor
    compileOnly(libs.reactor.kafka)
    compileOnly(libs.reactor.kotlin.extensions)
    testImplementation(libs.reactor.test)

    implementation(libs.bluetape4k.spring.boot4.core)

    // Spring Boot
    implementation(libs.spring.boot.autoconfigure.lib)
    annotationProcessor(libs.spring.boot.autoconfigure.processor)
    annotationProcessor(libs.spring.boot.configuration.processor)
    // runtimeOnly(libs.spring.boot.devtools)

    implementation(libs.spring.boot.starter.webflux.lib)

    testImplementation(libs.spring.boot.starter.test) {
        exclude(group = "junit", module = "junit")
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
        exclude(module = "mockito-core")
    }
}

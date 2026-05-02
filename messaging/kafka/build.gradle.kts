plugins {
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.graalvm.native)
}

springBoot {
    mainClass.set("io.bluetape4k.workshop.messaging.kafka.KafkaApplicationKt")
}


configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    implementation(platform(libs.spring.boot4.dependencies))
    
    // Kafka
    // implementation(libs.bluetape4k.kafka)
    implementation(libs.kafka.clients)
    implementation(libs.spring.kafka.lib)
    testImplementation(libs.spring.kafka.test)

    // Testcontainers
    implementation(libs.bluetape4k.testcontainers)
    implementation(libs.testcontainers.kafka)

    implementation(libs.bluetape4k.jackson3)
    testImplementation(libs.bluetape4k.junit5)

    runtimeOnly(libs.spring.boot.devtools)
    annotationProcessor(libs.spring.boot.configuration.processor)

    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.aspectj)
    implementation(libs.spring.boot.starter.webflux.lib)

    testImplementation(libs.bluetape4k.spring.boot4.core)
    testImplementation(libs.spring.boot.starter.test) {
        exclude(group = "junit", module = "junit")
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
        exclude(module = "mockito-core")
    }

    // Observability
    implementation(libs.micrometer.core)
    implementation(libs.micrometer.registry.prometheus)
    implementation(libs.micrometer.observation.lib)
    testImplementation(libs.micrometer.observation.test)

    // Coroutines
    implementation(libs.bluetape4k.coroutines)
    implementation(libs.kotlinx.coroutines.core.lib)
    implementation(libs.kotlinx.coroutines.reactor)
    testImplementation(libs.kotlinx.coroutines.test.lib)

    implementation(libs.reactor.kotlin.extensions)
    testImplementation(libs.reactor.test)

    // SpringDoc - OpenAPI 3.0
    implementation(libs.springdoc.openapi.starter.webflux.ui)
}

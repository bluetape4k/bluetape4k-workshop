plugins {
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.graalvm.native)
}

springBoot {
    mainClass.set("io.bluetape4k.workshop.cache.resilience.CacheResilienceApplicationKt")
}

configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    testImplementation(project(":shared"))
    testImplementation(libs.bluetape4k.junit5)
    testImplementation(libs.bluetape4k.testcontainers)
    // Toxiproxy: network failure injection for circuit breaker tests (testcontainers 2.x module name)
    testImplementation("org.testcontainers:testcontainers-toxiproxy") {
        version { require(libs.versions.testcontainers.get()) }
    }

    // Resilience4j
    implementation(libs.bluetape4k.resilience4j)
    implementation(libs.resilience4j.all)
    implementation(libs.resilience4j.kotlin)
    implementation(libs.resilience4j.spring.boot4)

    // Caffeine (local cache fallback)
    implementation(libs.caffeine.lib)
    implementation(libs.caffeine.jcache)

    // Spring Boot
    implementation(libs.spring.boot.autoconfigure.lib)
    annotationProcessor(libs.spring.boot.autoconfigure.processor)
    annotationProcessor(libs.spring.boot.configuration.processor)
    runtimeOnly(libs.spring.boot.devtools)

    implementation(libs.spring.boot.starter.aspectj)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.cache.lib)
    testImplementation(libs.spring.boot.starter.cache.test)
    implementation(libs.spring.boot.starter.webflux.lib)
    testImplementation(libs.spring.boot.starter.webflux.test)
    testImplementation(libs.spring.boot.starter.test) {
        exclude(group = "junit", module = "junit")
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
        exclude(module = "mockito-core")
    }

    // Redis (Lettuce)
    implementation(libs.bluetape4k.spring.boot4.redis)
    implementation(libs.spring.boot.starter.data.redis.lib)
    testImplementation(libs.spring.boot.starter.data.redis.test)
    implementation(libs.lettuce.core)
    implementation(libs.commons.pool2)

    // Coroutines
    implementation(libs.bluetape4k.coroutines)
    implementation(libs.kotlinx.coroutines.core.lib)
    implementation(libs.kotlinx.coroutines.reactor)
    testImplementation(libs.kotlinx.coroutines.test.lib)

    // Reactor
    implementation(libs.reactor.kotlin.extensions)
    testImplementation(libs.reactor.test)

    // Observability
    implementation(libs.micrometer.core)
    implementation(libs.micrometer.registry.prometheus)
    implementation(libs.micrometer.observation.lib)

    // SpringDoc - OpenAPI 3.0
    implementation(libs.springdoc.openapi.starter.webflux.ui)
}

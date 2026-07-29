plugins {
    alias(libs.plugins.exposed)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
}

exposed {
    migrations {
        tablesPackage = "io.bluetape4k.workshop.observability.advanced"
        databaseUrl = "jdbc:h2:mem:observability-observability-advanced-migrations;DB_CLOSE_DELAY=-1;MODE=PostgreSQL"
        databaseUser = "sa"
        databasePassword = ""
    }
}

springBoot {
    mainClass.set("io.bluetape4k.workshop.observability.advanced.AdvancedObservabilityAppKt")
}

configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    implementation(platform(libs.micrometer.bom))
    implementation(platform(libs.micrometer.tracing.bom))

    // bluetape4k 공통 의존성입니다.
    implementation(libs.bluetape4k.core)
    implementation(libs.bluetape4k.logging)
    implementation(libs.bluetape4k.coroutines)
    implementation(libs.bluetape4k.micrometer)
    implementation(libs.bluetape4k.jackson3)
    implementation(libs.bluetape4k.redis)
    implementation(libs.bluetape4k.redisson)
    testImplementation(libs.bluetape4k.junit5)
    testImplementation(libs.bluetape4k.assertions)
    testImplementation(libs.bluetape4k.testcontainers)
    testImplementation(project(":shared"))

    // Micrometer Observation 의존성입니다.
    implementation(libs.micrometer.observation.lib)
    testImplementation(libs.micrometer.observation.test)

    // Micrometer Tracing 의존성입니다.
    implementation(libs.micrometer.tracing.lib)
    testImplementation(libs.micrometer.tracing.test)
    implementation(libs.micrometer.tracing.bridge.otel)
    implementation(libs.micrometer.context.propagation)

    // Spring Boot 의존성입니다.
    implementation(libs.spring.boot.autoconfigure.lib)
    annotationProcessor(libs.spring.boot.autoconfigure.processor)
    annotationProcessor(libs.spring.boot.configuration.processor)
    runtimeOnly(libs.spring.boot.devtools)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.opentelemetry.lib)
    testImplementation(libs.spring.boot.starter.opentelemetry.test)
    implementation(libs.spring.boot.starter.webflux.lib)
    testImplementation(libs.spring.boot.starter.webflux.test)
    testImplementation(libs.spring.boot.starter.test) {
        exclude(group = "junit", module = "junit")
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
        exclude(module = "mockito-core")
    }

    // coroutine 과 Reactor 의존성입니다.
    implementation(libs.kotlinx.coroutines.core.lib)
    implementation(libs.kotlinx.coroutines.reactor)
    testImplementation(libs.kotlinx.coroutines.test.lib)
    implementation(libs.reactor.netty)
    implementation(libs.reactor.kotlin.extensions)
    testImplementation(libs.reactor.test)

    // Exposed(Kotlin SQL DSL) 의존성입니다.
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.jetbrains.exposed.core)
    implementation(libs.jetbrains.exposed.jdbc)
    implementation(libs.jetbrains.exposed.spring.boot4.starter)
    implementation(libs.jetbrains.exposed.spring7.transaction)
    implementation(libs.hikaricp)
    runtimeOnly(libs.h2.v2)

    // Redisson(Redis client) 의존성입니다.
    implementation(libs.redisson.lib)
}

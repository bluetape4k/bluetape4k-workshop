plugins {
    alias(libs.plugins.exposed)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
}

exposed {
    migrations {
        tablesPackage = "io.bluetape4k.workshop.messaging.outbox"
        databaseUrl = "jdbc:h2:mem:messaging-transactional-outbox-migrations;DB_CLOSE_DELAY=-1;MODE=PostgreSQL"
        databaseUser = "sa"
        databasePassword = ""
    }
}

springBoot {
    mainClass.set("io.bluetape4k.workshop.messaging.outbox.OutboxApplicationKt")
}

configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    testImplementation(project(":shared"))
    testImplementation(libs.bluetape4k.junit5)
    testImplementation(libs.bluetape4k.assertions)
    testImplementation(libs.mockk)
    testImplementation(libs.springmockk)

    // logging 의존성입니다.
    implementation(libs.bluetape4k.core)
    implementation(libs.bluetape4k.logging)

    // Exposed — transactional DB access 의존성입니다.
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.jetbrains.exposed.java.time)
    implementation(libs.jetbrains.exposed.spring.boot4.starter)
    implementation(libs.jetbrains.exposed.spring7.transaction)

    // DB 의존성입니다.
    implementation(libs.hikaricp)
    runtimeOnly(libs.postgresql.driver)

    // Kafka 의존성입니다.
    implementation(libs.kafka.clients)
    implementation(libs.spring.kafka.lib)
    testImplementation(libs.spring.kafka.test)

    // Testcontainers 의존성입니다.
    testImplementation(libs.bluetape4k.testcontainers)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.kafka)

    // serialization 의존성입니다.
    implementation(libs.bluetape4k.jackson3)
    implementation(libs.jackson3.module.kotlin)
    implementation(libs.jackson3.module.blackbird)

    // Spring Boot 의존성입니다.
    implementation(libs.spring.boot.autoconfigure.lib)
    annotationProcessor(libs.spring.boot.autoconfigure.processor)
    annotationProcessor(libs.spring.boot.configuration.processor)
    developmentOnly(libs.spring.boot.devtools)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.webmvc.lib)
    testImplementation(libs.spring.boot.starter.webmvc.test)
    testImplementation(libs.spring.boot.starter.webflux.lib)
    testImplementation(libs.spring.boot.starter.test) {
        exclude(group = "junit", module = "junit")
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
        exclude(module = "mockito-core")
    }

    // coroutine 의존성입니다.
    implementation(libs.bluetape4k.coroutines)
    implementation(libs.kotlinx.coroutines.core.lib)
    testImplementation(libs.kotlinx.coroutines.test.lib)

    // Observability
    implementation(libs.micrometer.core)
    implementation(libs.micrometer.registry.prometheus)

    // SpringDoc
    implementation(libs.springdoc.openapi.starter.webmvc.ui)
}

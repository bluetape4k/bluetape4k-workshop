plugins {
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
}

springBoot {
    mainClass.set("io.bluetape4k.workshop.exposed.webflux.r2dbc.ExposedWebfluxR2dbcAppKt")
}

configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    testImplementation(project(":shared"))
    testImplementation(libs.bluetape4k.junit5)
    testImplementation(libs.bluetape4k.assertions)

    // Logging
    implementation(libs.bluetape4k.logging)
    implementation(libs.bluetape4k.coroutines)

    // Exposed R2DBC
    implementation(libs.exposed.core)
    implementation(libs.exposed.r2dbc)
    implementation(libs.jetbrains.exposed.r2dbc)

    // R2DBC
    implementation(libs.r2dbc.pool)
    runtimeOnly(libs.r2dbc.postgresql)

    // JDBC (for DatabaseInitializer schema creation)
    implementation(libs.exposed.jdbc)
    implementation(libs.jetbrains.exposed.jdbc)
    implementation(libs.hikaricp)
    runtimeOnly(libs.postgresql.driver)

    // Coroutines
    implementation(libs.kotlinx.coroutines.core.lib)
    implementation(libs.kotlinx.coroutines.reactor)
    implementation(libs.reactor.netty)
    implementation(libs.reactor.kotlin.extensions)

    // Jackson
    implementation(libs.jackson3.module.kotlin)
    implementation(libs.jackson3.module.blackbird)

    // Spring Boot
    implementation(libs.spring.boot.autoconfigure.lib)
    annotationProcessor(libs.spring.boot.autoconfigure.processor)
    annotationProcessor(libs.spring.boot.configuration.processor)
    developmentOnly(libs.spring.boot.devtools)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.webflux.lib)
    implementation(libs.spring.boot.starter.data.r2dbc.lib)

    testImplementation(libs.spring.boot.starter.webflux.test)
    testImplementation(libs.spring.boot.starter.test) {
        exclude(group = "junit", module = "junit")
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
        exclude(module = "mockito-core")
    }
    testImplementation(libs.kotlinx.coroutines.test.lib)
    testImplementation(libs.bluetape4k.testcontainers)
    testImplementation(libs.testcontainers.postgresql)

    // SpringDoc
    implementation(libs.springdoc.openapi.starter.webflux.ui)
}

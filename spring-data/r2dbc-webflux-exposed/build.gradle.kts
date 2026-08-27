plugins {
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
    // alias(libs.plugins.graalvm.native)
    alias(libs.plugins.exposed)
}

exposed {
    migrations {
        tablesPackage = "io.bluetape4k.workshop.exposed.r2dbc.domain"
        databaseUrl = "jdbc:h2:mem:workshop-r2dbc-webflux-exposed-migrations;DB_CLOSE_DELAY=-1;MODE=PostgreSQL"
        databaseUser = "sa"
        databasePassword = ""
    }
}

springBoot {
    mainClass.set("io.bluetape4k.workshop.r2dbc.WebfluxR2dbcExposedApplicationKt")
}

configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {

    implementation(platform(libs.spring.boot4.dependencies))

    testImplementation(project(":shared"))
    testImplementation(libs.bluetape4k.spring.boot4.core)
    testImplementation(libs.bluetape4k.junit5)
    testImplementation(libs.exposed.r2dbc.tests)
    testRuntimeOnly(libs.mysql.connector.j)
    testRuntimeOnly(libs.postgresql.driver)

    // R2DBC 의존성
    implementation(libs.r2dbc.h2)
    implementation(libs.r2dbc.pool)

    implementation(libs.h2.v2)

    // Exposed R2DBC 의존성
    implementation(libs.exposed.r2dbc)
    implementation(libs.jetbrains.exposed.r2dbc)

    // Spring Boot 의존성
    implementation(libs.spring.boot.autoconfigure.lib)
    annotationProcessor(libs.spring.boot.autoconfigure.processor)
    annotationProcessor(libs.spring.boot.configuration.processor)
    runtimeOnly(libs.spring.boot.devtools)

    // Spring Boot 의존성
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.aspectj)
    implementation(libs.spring.boot.starter.data.r2dbc.lib)
    implementation(libs.spring.boot.starter.webflux.lib)
    testImplementation(libs.spring.boot.starter.webflux.test)

    testImplementation(libs.spring.boot.starter.test) {
        exclude(group = "junit", module = "junit")
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
        exclude(module = "mockito-core")
    }

    // 코루틴
    implementation(libs.bluetape4k.coroutines)
    implementation(libs.kotlinx.coroutines.core.lib)
    implementation(libs.kotlinx.coroutines.reactive)
    implementation(libs.kotlinx.coroutines.reactor)
    testImplementation(libs.kotlinx.coroutines.test.lib)

    // Reactor 의존성
    implementation(libs.reactor.core)
    implementation(libs.reactor.kotlin.extensions)
    testImplementation(libs.reactor.test)
}

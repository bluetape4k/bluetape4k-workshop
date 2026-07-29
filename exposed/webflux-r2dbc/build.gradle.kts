plugins {
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.exposed)
}

exposed {
    migrations {
        tablesPackage = "io.bluetape4k.workshop.exposed.webflux.r2dbc"
        databaseUrl = "jdbc:h2:mem:workshop-exposed-webflux-r2dbc-migrations;DB_CLOSE_DELAY=-1;MODE=PostgreSQL"
        databaseUser = "sa"
        databasePassword = ""
    }
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
    testImplementation(libs.exposed.r2dbc.tests)

    // 로깅 의존성
    implementation(libs.bluetape4k.logging)
    implementation(libs.bluetape4k.coroutines)

    // Exposed R2DBC 의존성
    implementation(libs.exposed.core)
    implementation(libs.exposed.r2dbc)
    implementation(libs.jetbrains.exposed.r2dbc)

    // R2DBC 드라이버/풀 의존성
    implementation(libs.r2dbc.pool)
    runtimeOnly(libs.r2dbc.postgresql)

    // JDBC 의존성: DatabaseInitializer의 schema 생성에 사용한다.
    implementation(libs.exposed.jdbc)
    implementation(libs.jetbrains.exposed.jdbc)
    implementation(libs.hikaricp)
    runtimeOnly(libs.postgresql.driver)

    // 코루틴 및 Reactor 의존성
    implementation(libs.kotlinx.coroutines.core.lib)
    implementation(libs.kotlinx.coroutines.reactor)
    implementation(libs.reactor.netty)
    implementation(libs.reactor.kotlin.extensions)

    // Jackson 직렬화 의존성
    implementation(libs.jackson3.module.kotlin)
    implementation(libs.jackson3.module.blackbird)

    // Spring Boot 구성
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

    // SpringDoc 문서화 의존성
    implementation(libs.springdoc.openapi.starter.webflux.ui)
}

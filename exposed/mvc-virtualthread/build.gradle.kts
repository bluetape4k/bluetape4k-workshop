plugins {
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.exposed)
}

exposed {
    migrations {
        tablesPackage = "io.bluetape4k.workshop.exposed.mvc.vt"
        databaseUrl = "jdbc:h2:mem:workshop-exposed-mvc-virtualthread-migrations;DB_CLOSE_DELAY=-1;MODE=PostgreSQL"
        databaseUser = "sa"
        databasePassword = ""
    }
}

springBoot {
    mainClass.set("io.bluetape4k.workshop.exposed.mvc.vt.ExposedMvcVirtualThreadAppKt")
}

configurations {
    testImplementation.get().extendsFrom(compileOnly.get())
}

dependencies {
    testImplementation(project(":shared"))
    testImplementation(libs.bluetape4k.junit5)
    testImplementation(libs.bluetape4k.assertions)
    testImplementation(libs.exposed.jdbc.tests) {
        exclude(group = "org.jetbrains.exposed", module = "exposed-spring-boot4-starter")
    }

    // bluetape4k core: virtualFuture, ShutdownQueue, KLogging을 제공한다.
    implementation(libs.bluetape4k.core)
    implementation(libs.bluetape4k.logging)

    // Virtual Thread runtime: JVM 21 테스트 환경에서는 jdk21 구현을 사용한다.
    implementation(libs.bluetape4k.virtualthread.api)
    runtimeOnly(libs.bluetape4k.virtualthread.jdk25)

    // Exposed JDBC: Spring TX starter 없이 수동 TX를 사용한다.
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)

    // 데이터베이스 의존성
    implementation(libs.hikaricp)
    runtimeOnly(libs.postgresql.driver)

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
    implementation(libs.spring.boot.starter.webmvc.lib)
    testImplementation(libs.spring.boot.starter.webmvc.test)
    testImplementation(libs.spring.boot.starter.webflux.lib)
    testImplementation(libs.spring.boot.starter.test) {
        exclude(group = "junit", module = "junit")
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
        exclude(module = "mockito-core")
    }

    testImplementation(libs.bluetape4k.testcontainers)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.kotlinx.coroutines.reactor)

    // SpringDoc 문서화 의존성
    implementation(libs.springdoc.openapi.starter.webmvc.ui)
}

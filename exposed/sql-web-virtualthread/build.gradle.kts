plugins {
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.gatling.plugin)
}


springBoot {
    mainClass.set("io.bluetape4k.workshop.exposed.virtualthread.ExposedSqlVirtualThreadMvcApp")

    buildInfo {
        properties {
            additional.put("name", "Webflux + Exposed SQL Application")
            additional.put("description", "Webflux + Exposed SQL Application")
            version = "1.0.0"
            additional.put("java.version", JavaVersion.current())
        }
    }
}


configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {

    testImplementation(project(":shared"))

    // JDK 21
    runtimeOnly(libs.bluetape4k.virtualthread.jdk21)

    // bluetape4k
    implementation(libs.bluetape4k.io)
    implementation(libs.bluetape4k.jdbc)

    // Exposed
    implementation(libs.exposed.core)
    implementation(libs.exposed.dao)
    implementation(libs.jetbrains.exposed.core)
    implementation(libs.jetbrains.exposed.jdbc)
    implementation(libs.jetbrains.exposed.dao)
    implementation(libs.jetbrains.exposed.java.time)
    implementation(libs.jetbrains.exposed.migration.jdbc)
    // implementation(libs.jetbrains.exposed.spring.boot.starter) // 직접 DatabaseConfig 에서 Database를 설정해서, 중복됨

    // Database Drivers
    implementation(libs.hikaricp)

    // H2
    implementation(libs.h2.v2)

    // MySQL
    implementation(libs.bluetape4k.testcontainers)
    implementation(libs.testcontainers.mysql)
    implementation(libs.mysql.connector.j)

    // Jackson for Kotlin
    implementation(libs.jackson3.module.kotlin)
    implementation(libs.jackson3.module.blackbird)

    // Spring Boot
    implementation(libs.spring.boot.autoconfigure.lib)
    annotationProcessor(libs.spring.boot.autoconfigure.processor)
    annotationProcessor(libs.spring.boot.configuration.processor)
    runtimeOnly(libs.spring.boot.devtools)

    implementation(libs.spring.boot.starter.aspectj)
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


    testImplementation(libs.bluetape4k.coroutines)
    testImplementation(libs.kotlinx.coroutines.reactor)
    testImplementation(libs.kotlinx.coroutines.test.lib)
    testImplementation(libs.reactor.kotlin.extensions)

    // Monitoring
    implementation(libs.micrometer.core)
    implementation(libs.micrometer.registry.prometheus)

    // SpringDoc - OpenAPI 3.0
    implementation(libs.springdoc.openapi.starter.webflux.ui)

    // Gatling
    implementation(libs.gatling.app)
    implementation(libs.gatling.core.java)
    implementation(libs.gatling.http.java)
    implementation(libs.gatling.recorder)
    implementation(libs.gatling.charts.highcharts)
    testImplementation(libs.gatling.test.framework)
}

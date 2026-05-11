plugins {
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.gatling.plugin)
}


springBoot {
    mainClass.set("io.bluetape4k.workshop.exposed.ExposedApplicationKt")

    buildInfo {
        properties {
            additional.put("name", "Spring Web Application with Exposed DAO")
            additional.put("description", "Spring Web + Exposed DAO Application")
            version = "1.0.0"
            additional.put("java.version", JavaVersion.current())
        }
    }
}


configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {

    implementation(platform(libs.spring.boot4.dependencies))

    testImplementation(project(":shared"))

    // bluetape4k
    implementation(libs.bluetape4k.io)
    implementation(libs.bluetape4k.jdbc)

    // Exposed
    implementation(libs.bluetape4k.exposed.core)
    implementation(libs.bluetape4k.exposed.dao)
    implementation(libs.exposed.core)
    implementation(libs.exposed.dao)
    implementation(libs.exposed.kotlin.datetime)
    implementation(libs.exposed.spring.boot4.starter)
    implementation(libs.exposed.spring7.transaction)

    // Jackson for Kotlin
    implementation(libs.jackson3.module.kotlin)
    implementation(libs.jackson3.module.blackbird)

    // Database Drivers
    implementation(libs.hikaricp)

    // H2
    implementation(libs.h2.v2)

    // Spring Boot 4
    implementation(libs.spring.boot.autoconfigure.lib)
    annotationProcessor(libs.spring.boot.autoconfigure.processor)
    annotationProcessor(libs.spring.boot.configuration.processor)
    runtimeOnly(libs.spring.boot.devtools)

    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.aspectj)
    implementation(libs.spring.boot.starter.jdbc.lib)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.webmvc.lib)

    testImplementation(libs.spring.boot.starter.test) {
        exclude(group = "junit", module = "junit")
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
        exclude(module = "mockito-core")
    }

    // Coroutines
    implementation(libs.bluetape4k.coroutines)
    implementation(libs.kotlinx.coroutines.core.lib)
    testImplementation(libs.kotlinx.coroutines.reactor)
    testImplementation(libs.kotlinx.coroutines.test.lib)

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

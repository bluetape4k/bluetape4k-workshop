plugins {
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.graalvm.native)
}

springBoot {
    mainClass.set("io.bluetape4k.workshop.gateway.orders.OrderApplicationKt")

    buildInfo {
        properties {
            additional.put("name", "Order Service")
            additional.put("description", "Spring Cloud API Gateway Demo - Order Service")
            version = "1.0.0"
            additional.put("java.version", JavaVersion.current())
        }
    }
}


configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    implementation(platform(libs.micrometer.bom))

    testImplementation(project(":shared"))

    implementation(libs.bluetape4k.jackson3)
    implementation(libs.bluetape4k.idgenerators)
    testImplementation(libs.bluetape4k.junit5)

    api(libs.jakarta.servlet.api)

    // Spring Boot
    implementation(libs.spring.boot.autoconfigure.lib)
    annotationProcessor(libs.spring.boot.autoconfigure.processor)
    annotationProcessor(libs.spring.boot.configuration.processor)
    runtimeOnly(libs.spring.boot.devtools)

    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.aspectj)
    implementation(libs.spring.boot.starter.cache.lib)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.webflux.lib)
    
    testImplementation(libs.spring.boot.starter.test) {
        exclude(group = "junit", module = "junit")
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
        exclude(module = "mockito-core")
    }

    // Coroutines
    implementation(libs.bluetape4k.coroutines)
    implementation(libs.kotlinx.coroutines.core.lib)
    implementation(libs.kotlinx.coroutines.reactor)
    testImplementation(libs.kotlinx.coroutines.test.lib)

    // Reactor
    implementation(libs.reactor.netty)
    implementation(libs.reactor.kotlin.extensions)
    testImplementation(libs.reactor.test)

    // Observability
    implementation(libs.micrometer.core)
    implementation(libs.micrometer.observation.lib)
    testImplementation(libs.micrometer.observation.test)

    // SpringDoc - OpenAPI 3.0
    implementation(libs.springdoc.openapi.starter.webflux.ui)
}

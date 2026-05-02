plugins {
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
//    alias(libs.plugins.graalvm.native)   // spring-cloud 는 아직 aot 를 지원하지 않는다
}

springBoot {
    mainClass.set("io.bluetape4k.workshop.gateway.ApiGatewayDemoApplicationKt")

    buildInfo {
        properties {
            additional.put("name", "Gateway Application")
            additional.put("description", "Spring Cloud API Gateway Demo")
            version = "1.0.0"
            additional.put("java.version", JavaVersion.current())
        }
    }
}

dependencyManagement {
    imports {
        mavenBom(libs.micrometer.bom.get().toString())
    }
}


configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {

    testImplementation(project(":shared"))

    implementation(libs.bluetape4k.jackson3)
    implementation(libs.bluetape4k.netty)
    testImplementation(libs.bluetape4k.junit5)

    // Bucket4j
    implementation(libs.bluetape4k.bucket4j)
    implementation(libs.bucket4j.core)
    implementation(libs.bucket4j.lettuce)
    implementation(libs.bucket4j.redisson)

    // Redis Cache
    implementation(libs.bluetape4k.cache.core)
    implementation(libs.lettuce.core)

    implementation(libs.bluetape4k.testcontainers)

    implementation(libs.jakarta.servlet.api)

    implementation(libs.bluetape4k.resilience4j)
    implementation(libs.resilience4j.spring.boot4)

    // Spring Cloud
    implementation(libs.spring.cloud.starter.gateway.server.webflux)
    testImplementation(libs.spring.cloud.starter.loadbalancer)
    testImplementation(libs.spring.cloud.test.support)
    testImplementation("org.springframework.cloud:spring-cloud-gateway-server-webflux::tests")
    testImplementation(libs.jmh.core)

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
    implementation(libs.spring.boot.starter.webflux.test)
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

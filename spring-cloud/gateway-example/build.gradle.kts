plugins {
    kotlin("plugin.spring")
    id(Plugins.spring_boot)
    id(Plugins.graalvm_native)
}

springBoot {
    mainClass.set("io.bluetape4k.workshop.gateway.GatewayApplicationKt")

    buildInfo {
        properties {
            additional.put("name", "Gateway Application")
            additional.put("description", "Spring Cloud API Gateway Demo")
            version = "1.0.0"
            additional.put("java.version", JavaVersion.current())
        }
    }
}


configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    implementation(platform(Libs.spring_boot_dependencies))
    implementation(platform(Libs.spring_cloud_dependencies))
    implementation(platform(Libs.micrometer_bom))

    implementation(Libs.bluetape4k_jackson3)
    testImplementation(Libs.bluetape4k_junit5)
    implementation(Libs.bluetape4k_testcontainers)

    // Redis Cache
    implementation(Libs.bluetape4k_cache)

    api(Libs.jakarta_servlet_api)

    implementation(Libs.bluetape4k_resilience4j)
    implementation(Libs.resilience4j_spring_boot3) // TODO: resilience4j-spring-boot4 가 개발 중입니다.

    // Spring Cloud
    implementation(Libs.springCloudStarter("gateway-server-webflux"))
    implementation(Libs.springCloudStarter("circuitbreaker-reactor-resilience4j"))

    // Spring Data Redis
    implementation(Libs.bluetape4k_redis)
    implementation(Libs.springBootStarter("data-redis"))
    testImplementation(Libs.springBootStarter("data-redis-test"))
    implementation(Libs.lettuce_core)
    implementation(Libs.commons_pool2)

    // Spring Boot Security
    // implementation(Libs.springBootStarter("security"))

    // Spring Boot
    implementation(Libs.springBoot("autoconfigure"))
    annotationProcessor(Libs.springBoot("autoconfigure-processor"))
    annotationProcessor(Libs.springBoot("configuration-processor"))
    runtimeOnly(Libs.springBoot("devtools"))


    implementation(Libs.springBootStarter("aspectj"))
    implementation(Libs.springBootStarter("actuator"))
    implementation(Libs.springBootStarter("cache"))
    implementation(Libs.springBootStarter("validation"))
    implementation(Libs.springBootStarter("webflux"))
    testImplementation(Libs.springBootStarter("cache-test"))
    testImplementation(Libs.springBootStarter("webflux-test"))

    testImplementation(Libs.springBootStarter("test")) {
        exclude(group = "junit", module = "junit")
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
        exclude(module = "mockito-core")
    }

    // Coroutines
    implementation(Libs.bluetape4k_coroutines)
    implementation(Libs.kotlinx_coroutines_core)
    implementation(Libs.kotlinx_coroutines_reactive)
    implementation(Libs.kotlinx_coroutines_reactor)
    testImplementation(Libs.kotlinx_coroutines_test)

    // Reactor
    implementation(Libs.reactor_netty)
    implementation(Libs.reactor_kotlin_extensions)
    testImplementation(Libs.reactor_test)

    // Observability
    implementation(Libs.micrometer_core)
    implementation(Libs.micrometer_observation)
    testImplementation(Libs.micrometer_observation_test)

    // SpringDoc - OpenAPI 3.0
    implementation(Libs.springdoc_openapi_starter_webflux_ui)
}

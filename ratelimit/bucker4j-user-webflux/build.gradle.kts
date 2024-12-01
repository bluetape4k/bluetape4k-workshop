plugins {
    kotlin("plugin.spring")
    id(Plugins.spring_boot)
}

springBoot {
    mainClass.set("io.bluetape4k.workshop.bucket4j.RateLimiterWebfluxApplicationKt")
    buildInfo {
        properties {
            additional.put("name", "Rate Limiter Application")
            additional.put("description", "Rate Limit per User")
            version = "1.0.0"
            additional.put("java.version", JavaVersion.current())
        }
    }
}

@Suppress("UnstableApiUsage")
configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    implementation(Libs.bluetape4k_jackson)
    implementation(Libs.bluetape4k_spring_webflux)
    testImplementation(Libs.bluetape4k_spring_tests)

    // Bucket4j
    implementation(Libs.bluetape4k_bucket4j)
    implementation(Libs.bucket4j_core)
    implementation(Libs.bucket4j_redis)

    // Redis
    implementation(Libs.bluetape4k_redis)
    implementation(Libs.lettuce_core)
    implementation(Libs.redisson)
    implementation(Libs.bluetape4k_testcontainers)

    api(Libs.javax_cache_api)
    api(Libs.jakarta_servlet_api)

    // Spring Boot
    implementation(Libs.springBoot("autoconfigure"))
    annotationProcessor(Libs.springBoot("autoconfigure-processor"))
    annotationProcessor(Libs.springBoot("configuration-processor"))
    runtimeOnly(Libs.springBoot("devtools"))

    implementation(Libs.springBootStarter("webflux"))
    implementation(Libs.springBootStarter("aop"))
    implementation(Libs.springBootStarter("actuator"))
    testImplementation(Libs.springBootStarter("test")) {
        exclude(group = "junit", module = "junit")
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
        exclude(module = "mockito-core")
    }

    // Coroutines
    implementation(Libs.bluetape4k_coroutines)
    implementation(Libs.kotlinx_coroutines_core)
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
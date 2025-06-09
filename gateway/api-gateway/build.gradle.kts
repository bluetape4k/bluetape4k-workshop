plugins {
    kotlin("plugin.spring")
    id(Plugins.spring_boot)
    id(Plugins.graalvm_native)
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
        mavenBom(Libs.micrometer_bom)
    }
}

@Suppress("UnstableApiUsage")
configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    implementation(Libs.bluetape4k_spring_core)
    implementation(Libs.bluetape4k_jackson)
    implementation(Libs.bluetape4k_netty)
    testImplementation(Libs.bluetape4k_junit5)

    // Bucket4j
    implementation(Libs.bluetape4k_bucket4j)
    implementation(Libs.bucket4j_core)
    implementation(Libs.bucket4j_lettuce)
    implementation(Libs.bucket4j_redisson)

    // Redis Cache
    implementation(Libs.bluetape4k_cache)
    implementation(Libs.lettuce_core)

    implementation(Libs.bluetape4k_testcontainers)

    implementation(Libs.jakarta_servlet_api)

    implementation(Libs.bluetape4k_resilience4j)
    implementation(Libs.resilience4j_spring_boot3)

    // Spring Cloud
    implementation(Libs.springCloudStarter("gateway"))
    testImplementation(Libs.springCloudStarter("loadbalancer"))
    testImplementation(Libs.springCloud("test-support"))
    testImplementation(Libs.springCloud("gateway-server") + "::tests")

    // Spring Boot
    implementation(Libs.springBoot("autoconfigure"))
    annotationProcessor(Libs.springBoot("autoconfigure-processor"))
    annotationProcessor(Libs.springBoot("configuration-processor"))
    runtimeOnly(Libs.springBoot("devtools"))


    implementation(Libs.springBootStarter("webflux"))
    implementation(Libs.springBootStarter("aop"))
    implementation(Libs.springBootStarter("cache"))
    implementation(Libs.springBootStarter("validation"))
    implementation(Libs.springBootStarter("actuator"))
    testImplementation(Libs.springBootStarter("test")) {
        exclude(group = "junit", module = "junit")
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
        exclude(module = "mockito-core")
    }
    testImplementation(Libs.bluetape4k_spring_tests)

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

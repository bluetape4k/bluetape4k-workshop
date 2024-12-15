plugins {
    kotlin("plugin.spring")
    id(Plugins.spring_boot)
    id(Plugins.gatling) version Plugins.Versions.gatling
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

@Suppress("UnstableApiUsage")
configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {

    implementation(project(":shared"))

    // bluetape4k
    implementation(Libs.bluetape4k_io)
    implementation(Libs.bluetape4k_jdbc)
    implementation(Libs.bluetape4k_spring_core)
    testImplementation(Libs.bluetape4k_spring_tests)

    // Exposed
    implementation(Libs.exposed_core)
    implementation(Libs.exposed_dao)
    implementation(Libs.exposed_kotlin_datetime)
    implementation(Libs.exposed_spring_boot_starter)
    testImplementation(Libs.bluetape4k_spring_tests)

    // Database Drivers
    implementation(Libs.hikaricp)

    // H2
    implementation(Libs.h2_v2)

    // Spring Boot
    implementation(Libs.springBoot("autoconfigure"))
    annotationProcessor(Libs.springBoot("autoconfigure-processor"))
    annotationProcessor(Libs.springBoot("configuration-processor"))
    runtimeOnly(Libs.springBoot("devtools"))

    implementation(Libs.springBootStarter("web"))
    implementation(Libs.springBootStarter("aop"))
    implementation(Libs.springBootStarter("actuator"))
    implementation(Libs.springBootStarter("validation"))

    testImplementation(Libs.springBootStarter("test")) {
        exclude(group = "junit", module = "junit")
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
        exclude(module = "mockito-core")
    }

    // Coroutines
    implementation(Libs.bluetape4k_coroutines)
    testImplementation(Libs.kotlinx_coroutines_core)
    testImplementation(Libs.kotlinx_coroutines_reactor)
    testImplementation(Libs.kotlinx_coroutines_test)

    // Monitoring
    implementation(Libs.micrometer_core)
    implementation(Libs.micrometer_registry_prometheus)

    // SpringDoc - OpenAPI 3.0
    implementation(Libs.springdoc_openapi_starter_webflux_ui)

    // Gatling
    implementation(Libs.gatling_app)
    implementation(Libs.gatling_core_java)
    implementation(Libs.gatling_http_java)
    implementation(Libs.gatling_recorder)
    implementation(Libs.gatling_charts_highcharts)
    testImplementation(Libs.gatling_test_framework)
}

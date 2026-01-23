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


configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {

    implementation(platform(Libs.spring_boot4_dependencies))

    implementation(project(":shared"))

    // bluetape4k
    implementation(Libs.bluetape4k_io)
    implementation(Libs.bluetape4k_jdbc)

    // Exposed
    implementation(Libs.bluetape4k_exposed)
    implementation(Libs.exposed_core)
    implementation(Libs.exposed_dao)
    implementation(Libs.exposed_kotlin_datetime)
    implementation(Libs.exposed_spring_boot4_starter)
    implementation(Libs.exposed_spring7_transaction)

    // Jackson for Kotlin
    implementation(Libs.jackson3_module_kotlin)
    implementation(Libs.jackson3_module_blackbird)

    // Database Drivers
    implementation(Libs.hikaricp)

    // H2
    implementation(Libs.h2_v2)

    // Spring Boot 4
    implementation(Libs.springBoot("autoconfigure"))
    annotationProcessor(Libs.springBoot("autoconfigure-processor"))
    annotationProcessor(Libs.springBoot("configuration-processor"))
    runtimeOnly(Libs.springBoot("devtools"))

    implementation(Libs.springBootStarter("actuator"))
    implementation(Libs.springBootStarter("aspectj"))
    implementation(Libs.springBootStarter("jdbc"))
    implementation(Libs.springBootStarter("validation"))
    implementation(Libs.springBootStarter("webmvc"))

    testImplementation(Libs.springBootStarter("test")) {
        exclude(group = "junit", module = "junit")
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
        exclude(module = "mockito-core")
    }

    // Coroutines
    implementation(Libs.bluetape4k_coroutines)
    implementation(Libs.kotlinx_coroutines_core)
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

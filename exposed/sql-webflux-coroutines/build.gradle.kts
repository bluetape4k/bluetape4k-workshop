plugins {
    kotlin("plugin.spring")
    id(Plugins.spring_boot)
    id(Plugins.gatling) version Plugins.Versions.gatling
}


springBoot {
    mainClass.set("io.bluetape4k.workshop.exposed.ExposedSqlApplicationKt")

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

    implementation(project(":shared"))

    // bluetape4k
    implementation(Libs.bluetape4k_jdbc)
    testImplementation(Libs.bluetape4k_spring_tests)

    // Exposed
    implementation(Libs.bluetape4k_exposed)
    implementation(Libs.exposed_core)
    implementation(Libs.exposed_jdbc)
    implementation(Libs.exposed_kotlin_datetime)
    implementation(Libs.exposed_migration)
    implementation(Libs.exposed_spring_boot_starter)
    implementation(Libs.exposed_spring_transaction)

    // Database Drivers
    implementation(Libs.hikaricp)

    // H2
    implementation(Libs.h2_v2)

    // MySQL
    implementation(Libs.bluetape4k_testcontainers)
    implementation(Libs.testcontainers_mysql)
    implementation(Libs.mysql_connector_j)

    // Spring Boot
    implementation(Libs.springBoot("autoconfigure"))
    annotationProcessor(Libs.springBoot("autoconfigure-processor"))
    annotationProcessor(Libs.springBoot("configuration-processor"))
    runtimeOnly(Libs.springBoot("devtools"))

    implementation(Libs.springBootStarter("webflux"))
    implementation(Libs.springBootStarter("aop"))
    implementation(Libs.springBootStarter("actuator"))
    implementation(Libs.springBootStarter("validation"))

    testImplementation(Libs.bluetape4k_spring_tests)
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

plugins {
    kotlin("plugin.spring")
    id(Plugins.spring_boot)
    id(Plugins.gatling) version Plugins.Versions.gatling
}

springBoot {
    mainClass.set("io.bluetape4k.workshop.gatling.KotlinGatlingApplicationKt")
}

@Suppress("UnstableApiUsage")
configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {

    implementation(Libs.bluetape4k_io)
    implementation(Libs.bluetape4k_spring_core)
    implementation(Libs.bluetape4k_jackson)
    testImplementation(Libs.bluetape4k_junit5)

    // Spring Boot
    implementation(Libs.springBoot("autoconfigure"))
    annotationProcessor(Libs.springBoot("autoconfigure-processor"))
    annotationProcessor(Libs.springBoot("configuration-processor"))
    runtimeOnly(Libs.springBoot("devtools"))

    implementation(Libs.springBootStarter("web"))
    implementation(Libs.springBootStarter("aop"))
    implementation(Libs.springBootStarter("actuator"))
    testImplementation(Libs.springBootStarter("test")) {
        exclude(group = "junit", module = "junit")
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
        exclude(module = "mockito-core")
    }

    // SpringDoc - OpenAPI 3.0
    implementation(Libs.springdoc_openapi_starter_webmvc_ui)

    // Sample Data
    implementation(Libs.datafaker)

    // MongoDB
//    implementation(Libs.mongodb_driver_sync)
//    implementation(Libs.testcontainers_mongodb)
//    implementation(Libs.bluetape4k_testcontainers)

    // Gatling
    implementation(Libs.gatling_app)
    implementation(Libs.gatling_core_java)
    implementation(Libs.gatling_http_java)
    implementation(Libs.gatling_recorder)
    implementation(Libs.gatling_charts_highcharts)
    testImplementation(Libs.gatling_test_framework)

    // Gatling Scenario에서 bluetape4k-io 를 사용하려고 추가
    gatling(Libs.bluetape4k_io)

}
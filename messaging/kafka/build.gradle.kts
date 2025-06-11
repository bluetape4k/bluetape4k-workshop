plugins {
    kotlin("plugin.spring")
    id(Plugins.spring_boot)
    id(Plugins.graalvm_native)
}

springBoot {
    mainClass.set("io.bluetape4k.workshop.messaging.kafka.KafkaApplicationKt")
}


configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    // Kafka
    implementation(Libs.bluetape4k_kafka)
    // implementation(Libs.kafka_clients)
    implementation(Libs.spring_kafka)
    testImplementation(Libs.spring_kafka_test)

    // Testcontainers
    implementation(Libs.bluetape4k_testcontainers)
    implementation(Libs.testcontainers_kafka)

    implementation(Libs.bluetape4k_spring_core)
    implementation(Libs.bluetape4k_jackson)
    testImplementation(Libs.bluetape4k_junit5)

    implementation(Libs.springBootStarter("webflux"))
    implementation(Libs.springBootStarter("actuator"))
    implementation(Libs.springBootStarter("aop"))

    runtimeOnly(Libs.springBoot("devtools"))
    annotationProcessor(Libs.springBoot("configuration-processor"))

    testImplementation(Libs.bluetape4k_spring_tests)
    testImplementation(Libs.springBootStarter("test")) {
        exclude(group = "junit", module = "junit")
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
        exclude(module = "mockito-core")
    }

    // Observability
    implementation(Libs.micrometer_core)
    implementation(Libs.micrometer_registry_prometheus)
    implementation(Libs.micrometer_observation)
    testImplementation(Libs.micrometer_observation_test)

    // Coroutines
    implementation(Libs.bluetape4k_coroutines)
    implementation(Libs.kotlinx_coroutines_core)
    implementation(Libs.kotlinx_coroutines_reactor)
    testImplementation(Libs.kotlinx_coroutines_test)

    implementation(Libs.reactor_kotlin_extensions)
    testImplementation(Libs.reactor_test)

    // SpringDoc - OpenAPI 3.0
    implementation(Libs.springdoc_openapi_starter_webflux_ui)
}

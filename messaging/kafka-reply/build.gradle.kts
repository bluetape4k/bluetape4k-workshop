plugins {
    kotlin("plugin.spring")
    id(Plugins.spring_boot)
}

springBoot {
    mainClass.set("io.bluetape4k.workshop.kafka.KafkaApplicationKt")
}

configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    // Kafka
    api(Libs.kafka_clients)
    compileOnly(Libs.kafka_metadata)
    compileOnly(Libs.kafka_streams)

    implementation(Libs.spring_kafka)
    implementation(Libs.spring_kafka_test)
    implementation(Libs.springData("commons"))

    // implementation(Libs.bluetape4k_kafka)
    implementation(Libs.bluetape4k_testcontainers)
    implementation(Libs.testcontainers_kafka)

    // Jackson
    api(Libs.bluetape4k_jackson)
    api(Libs.jackson_databind)
    api(Libs.jackson_module_kotlin)
    api(Libs.jackson_module_blackbird)

    // Coroutines
    implementation(Libs.bluetape4k_coroutines)
    implementation(Libs.kotlinx_coroutines_core)
    implementation(Libs.kotlinx_coroutines_reactor)
    testImplementation(Libs.kotlinx_coroutines_test)

    // Reactor
    compileOnly(Libs.reactor_kafka)
    compileOnly(Libs.reactor_kotlin_extensions)
    testImplementation(Libs.reactor_test)

    api(Libs.bluetape4k_spring_webflux)
    testImplementation(Libs.bluetape4k_spring_tests)

    // Spring Boot
    implementation(Libs.springBoot("autoconfigure"))
    annotationProcessor(Libs.springBoot("autoconfigure-processor"))
    annotationProcessor(Libs.springBoot("configuration-processor"))
    // runtimeOnly(Libs.springBoot("devtools"))

    implementation(Libs.springBootStarter("webflux"))

    testImplementation(Libs.springBootStarter("test")) {
        exclude(group = "junit", module = "junit")
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
        exclude(module = "mockito-core")
    }
}

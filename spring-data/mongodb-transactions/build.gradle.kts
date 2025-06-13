plugins {
    kotlin("plugin.spring")
}

configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    implementation(Libs.springBootStarter("actuator"))
    implementation(Libs.springBootStarter("aop"))
    implementation(Libs.springBootStarter("webflux"))
    implementation(Libs.springBootStarter("validation"))

    implementation(Libs.springBootStarter("data-mongodb"))
    implementation(Libs.springBootStarter("data-mongodb-reactive"))

    runtimeOnly(Libs.springBoot("devtools"))
    annotationProcessor(Libs.springBoot("configuration-processor"))

    testImplementation(Libs.bluetape4k_spring_tests)
    testImplementation(Libs.springBootStarter("test")) {
        exclude(group = "junit", module = "junit")
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
        exclude(module = "mockito-core")
    }

    // Mongo Driver
    implementation(Libs.mongodb_driver_sync)
    implementation(Libs.mongodb_driver_reactivestreams)

    // MongoDB Testcontainers
    implementation(Libs.bluetape4k_testcontainers)
    implementation(Libs.testcontainers)
    implementation(Libs.testcontainers_mongodb)

    // Coroutines
    implementation(Libs.bluetape4k_coroutines)
    implementation(Libs.kotlinx_coroutines_core)
    implementation(Libs.kotlinx_coroutines_reactive)
    implementation(Libs.kotlinx_coroutines_reactor)
    testImplementation(Libs.kotlinx_coroutines_test)

    implementation(Libs.bluetape4k_jackson)
    implementation(Libs.bluetape4k_idgenerators)
    testImplementation(Libs.bluetape4k_junit5)

    testImplementation(Libs.reactor_test)
    testImplementation(Libs.turbine)
}

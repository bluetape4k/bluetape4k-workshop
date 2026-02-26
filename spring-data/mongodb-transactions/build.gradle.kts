plugins {
    kotlin("plugin.spring")
}

configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    runtimeOnly(Libs.springBoot("devtools"))
    annotationProcessor(Libs.springBoot("configuration-processor"))

    implementation(Libs.springBootStarter("actuator"))
    implementation(Libs.springBootStarter("aspectj"))
    implementation(Libs.springBootStarter("validation"))
    implementation(Libs.springBootStarter("webflux"))
    testImplementation(Libs.springBootStarter("webflux-test"))

    implementation(Libs.springBootStarter("data-mongodb"))
    implementation(Libs.springBootStarter("data-mongodb-reactive"))
    testImplementation(Libs.springBootStarter("data-mongodb-test"))

    testImplementation(Libs.springBootStarter("test")) {
        exclude(group = "junit", module = "junit")
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
        exclude(module = "mockito-core")
    }

    // Mongo Driver
    implementation(Libs.mongodb_driver_kotlin_sync)
    implementation(Libs.mongodb_driver_kotlin_coroutine)
    implementation(Libs.mongodb_driver_kotlin_extensions)

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

    implementation(Libs.bluetape4k_idgenerators)
    testImplementation(Libs.bluetape4k_junit5)

    testImplementation(Libs.reactor_test)
    testImplementation(Libs.turbine)
}

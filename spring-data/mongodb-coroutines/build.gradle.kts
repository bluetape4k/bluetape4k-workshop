plugins {
    kotlin("plugin.spring")
    id(Plugins.spring_boot)
    id(Plugins.graalvm_native)
}

springBoot {
    mainClass.set("io.bluetape4k.workshop.mongodb.MongodbApplicationKt")
}

configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    implementation(Libs.springBootStarter("actuator"))
    implementation(Libs.springBootStarter("aspectj"))
    implementation(Libs.springBootStarter("webflux"))
    implementation(Libs.springBootStarter("validation"))

    implementation(Libs.springBootStarter("data-mongodb"))
    testImplementation(Libs.springBootStarter("data-mongodb-test"))
    implementation(Libs.springBootStarter("data-mongodb-reactive"))
    testImplementation(Libs.springBootStarter("data-mongodb-reactive-test"))

    runtimeOnly(Libs.springBoot("devtools"))
    annotationProcessor(Libs.springBoot("configuration-processor"))

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

    implementation(Libs.reactor_kotlin_extensions)
    testImplementation(Libs.reactor_test)
    testImplementation(Libs.turbine)

    implementation(Libs.bluetape4k_idgenerators)
    implementation(Libs.bluetape4k_netty)

    implementation(Libs.jackson3_module_kotlin)
}

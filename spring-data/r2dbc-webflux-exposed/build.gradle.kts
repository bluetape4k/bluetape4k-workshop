plugins {
    kotlin("plugin.spring")
    id(Plugins.spring_boot)
    // id(Plugins.graalvm_native)
}

springBoot {
    mainClass.set("io.bluetape4k.workshop.r2dbc.WebfluxR2dbcExposedApplicationKt")
}

configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {

    implementation(platform(Libs.spring_boot4_dependencies))

    testImplementation(project(":shared"))
    testImplementation(Libs.bluetape4k_junit5)

    // R2DBC
    implementation(Libs.r2dbc_h2)
    implementation(Libs.r2dbc_pool)

    implementation(Libs.h2_v2)

    // Exposed R2dbc
    implementation(Libs.bluetape4k_exposed_r2dbc)
    implementation(Libs.exposed_r2dbc)

    // Spring Boot
    implementation(Libs.springBoot("autoconfigure"))
    annotationProcessor(Libs.springBoot("autoconfigure-processor"))
    annotationProcessor(Libs.springBoot("configuration-processor"))
    runtimeOnly(Libs.springBoot("devtools"))

    // Spring Boot
    implementation(Libs.springBootStarter("actuator"))
    implementation(Libs.springBootStarter("aspectj"))
    implementation(Libs.springBootStarter("data-r2dbc"))
    implementation(Libs.springBootStarter("webflux"))
    testImplementation(Libs.springBootStarter("webflux-test"))

    testImplementation(Libs.springBootStarter("test")) {
        exclude(group = "junit", module = "junit")
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
        exclude(module = "mockito-core")
    }

    // Coroutines
    implementation(Libs.bluetape4k_coroutines)
    implementation(Libs.kotlinx_coroutines_core)
    implementation(Libs.kotlinx_coroutines_reactive)
    implementation(Libs.kotlinx_coroutines_reactor)
    testImplementation(Libs.kotlinx_coroutines_test)

    // Reactor
    implementation(Libs.reactor_core)
    implementation(Libs.reactor_kotlin_extensions)
    testImplementation(Libs.reactor_test)
}

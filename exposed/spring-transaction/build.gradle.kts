plugins {
    kotlin("plugin.spring")
    id(Plugins.gatling) version Plugins.Versions.gatling
}

@Suppress("UnstableApiUsage")
configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    implementation(platform(Libs.exposed_bom))
    implementation(project(":exposed-domain"))

    // Exposed
    implementation(Libs.bluetape4k_exposed)
    implementation(Libs.exposed_core)
    implementation(Libs.exposed_dao)
    implementation(Libs.exposed_kotlin_datetime)
    implementation(Libs.exposed_spring_boot_starter)

    // Database Drivers
    implementation(Libs.hikaricp)

    // H2
    implementation(Libs.h2_v2)

    // Bluetape4k
    implementation(Libs.bluetape4k_io)
    implementation(Libs.bluetape4k_jdbc)
    implementation(Libs.bluetape4k_spring_core)
    testImplementation(Libs.bluetape4k_spring_tests)

    // Spring Boot
    implementation(Libs.springBootStarter("jdbc"))
    testImplementation(Libs.springBootStarter("test")) {
        exclude(group = "junit", module = "junit")
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
        exclude(module = "mockito-core")
    }

    // Coroutines
    implementation(Libs.bluetape4k_coroutines)
    testImplementation(Libs.kotlinx_coroutines_core)
    testImplementation(Libs.kotlinx_coroutines_reactor)
    testImplementation(Libs.kotlinx_coroutines_debug)
    testImplementation(Libs.kotlinx_coroutines_test)
}

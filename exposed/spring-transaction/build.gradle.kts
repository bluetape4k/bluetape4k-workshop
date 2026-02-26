plugins {
    kotlin("plugin.spring")
    id(Plugins.gatling) version Plugins.Versions.gatling
}


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
    implementation(Libs.exposed_jdbc)
    implementation(Libs.exposed_kotlin_datetime)
    implementation(Libs.exposed_spring_boot4_starter)

    // Database Drivers
    implementation(Libs.hikaricp)

    // H2
    implementation(Libs.h2_v2)

    // Bluetape4k
    implementation(Libs.bluetape4k_idgenerators)
    implementation(Libs.bluetape4k_io)

    // Jackson for Kotlin
    implementation(Libs.jackson3_module_kotlin)
    implementation(Libs.jackson3_module_blackbird)

    // Spring Boot 4
    implementation(Libs.springBoot("autoconfigure"))
    annotationProcessor(Libs.springBoot("autoconfigure-processor"))
    annotationProcessor(Libs.springBoot("configuration-processor"))
    runtimeOnly(Libs.springBoot("devtools"))
    
    implementation(Libs.springBootStarter("jdbc"))
    implementation(Libs.springBootStarter("jdbc-test"))
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

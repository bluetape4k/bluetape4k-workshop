plugins {
    kotlin("plugin.spring")
    id(Plugins.spring_boot)
    id(Plugins.graalvm_native)
}

springBoot {
    mainClass.set("io.bluetape4k.workshop.r2dbc.R2dbcApplicationKt")
}

configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    implementation(platform(Libs.spring_boot4_dependencies))
    
    implementation(Libs.bluetape4k_io)
    testImplementation(Libs.bluetape4k_junit5)

    // PostgreSql Server
    implementation(Libs.bluetape4k_testcontainers)
    implementation(Libs.testcontainers_postgresql)

    // Coroutines
    implementation(Libs.bluetape4k_coroutines)
    implementation(Libs.kotlinx_coroutines_core)
    implementation(Libs.kotlinx_coroutines_reactor)
    testImplementation(Libs.kotlinx_coroutines_test)

    // Reactor
    implementation(Libs.reactor_core)
    implementation(Libs.reactor_kotlin_extensions)
    testImplementation(Libs.reactor_test)

    // R2DBC
    implementation(Libs.bluetape4k_r2dbc)
    implementation(Libs.bluetape4k_spring_r2dbc)
    implementation(Libs.springBootStarter("data-r2dbc"))
    testImplementation(Libs.springBootStarter("data-r2dbc-test"))

    runtimeOnly(Libs.h2_v2)
    implementation(Libs.r2dbc_h2)
    implementation(Libs.r2dbc_pool)
    implementation(Libs.r2dbc_postgresql)

    implementation(Libs.springBootStarter("webflux"))
    testImplementation(Libs.springBootStarter("webflux-test"))

    testImplementation(Libs.bluetape4k_spring_tests)
    testImplementation(Libs.springBootStarter("test")) {
        exclude(group = "junit", module = "junit")
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
        exclude(module = "mockito-core")
    }

}

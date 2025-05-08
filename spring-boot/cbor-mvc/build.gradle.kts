plugins {
    kotlin("plugin.spring")
    id(Plugins.spring_boot)
}

springBoot {
    mainClass.set("io.bluetape4k.workshop.cbor.CborApplicationKt")
    buildInfo()
}

dependencies {

    // Jackson CBOR
    implementation(Libs.bluetape4k_jackson_binary)
    implementation(Libs.jackson_dataformat_cbor)    // smile 도 가능

    implementation(Libs.bluetape4k_spring_core)
    testImplementation(Libs.bluetape4k_spring_tests)

    implementation(Libs.springBootStarter("web"))
    testImplementation(Libs.springBootStarter("test")) {
        exclude(group = "junit", module = "junit")
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
        exclude(module = "mockito-core")
    }

    // Spring Webflux
    testImplementation(Libs.springBootStarter("webflux"))

    // Coroutines & Reactor
    testImplementation(Libs.bluetape4k_coroutines)
    testImplementation(Libs.kotlinx_coroutines_reactor)
    testImplementation(Libs.kotlinx_coroutines_test)
    testImplementation(Libs.reactor_kotlin_extensions)
    testImplementation(Libs.reactor_test)
}

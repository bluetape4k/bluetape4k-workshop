plugins {
    kotlin("plugin.spring")
    id(Plugins.spring_boot)
    id(Plugins.graalvm_native)
}

springBoot {
    mainClass.set("io.bluetape4k.workshop.cbor.CborApplicationKt")
    buildInfo()
}

dependencies {

    testImplementation(project(":shared"))

    // Jackson CBOR
    implementation(Libs.bluetape4k_jackson3_binary)
    implementation(Libs.jackson3_dataformat_cbor)    // smile 도 가능

    implementation(Libs.springBootStarter("actuator"))
    implementation(Libs.springBootStarter("web"))
    testImplementation(Libs.springBootStarter("webmvc-test"))
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

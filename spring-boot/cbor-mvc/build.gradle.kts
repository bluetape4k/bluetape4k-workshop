plugins {
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.graalvm.native)
}

springBoot {
    mainClass.set("io.bluetape4k.workshop.cbor.CborApplicationKt")
    buildInfo()
}

dependencies {

    testImplementation(project(":shared"))

    // Jackson CBOR
    implementation(libs.bluetape4k.jackson3)
    implementation(libs.jackson3.dataformat.cbor)    // smile 도 가능

    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.web)
    testImplementation(libs.spring.boot.starter.webmvc.test)
    testImplementation(libs.spring.boot.starter.test) {
        exclude(group = "junit", module = "junit")
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
        exclude(module = "mockito-core")
    }

    // Spring Webflux
    testImplementation(libs.spring.boot.starter.webflux.lib)

    // Coroutines & Reactor
    testImplementation(libs.bluetape4k.coroutines)
    testImplementation(libs.kotlinx.coroutines.reactor)
    testImplementation(libs.kotlinx.coroutines.test.lib)
    testImplementation(libs.reactor.kotlin.extensions)
    testImplementation(libs.reactor.test)
}

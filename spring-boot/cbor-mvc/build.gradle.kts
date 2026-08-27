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
    testImplementation(libs.bluetape4k.spring.boot4.core)

    implementation(libs.bluetape4k.core)

    // Jackson CBOR 의존성
    implementation(libs.bluetape4k.jackson3)
    implementation(libs.jackson3.dataformat.cbor)

    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.web)
    testImplementation(libs.spring.boot.starter.webmvc.test)
    testImplementation(libs.spring.boot.starter.test) {
        exclude(group = "junit", module = "junit")
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
        exclude(module = "mockito-core")
    }

    // Spring WebFlux 의존성
    testImplementation(libs.spring.boot.starter.webflux.lib)

    // 코루틴 및 Reactor 의존성
    testImplementation(libs.bluetape4k.coroutines)
    testImplementation(libs.kotlinx.coroutines.reactor)
    testImplementation(libs.kotlinx.coroutines.test.lib)
    testImplementation(libs.reactor.kotlin.extensions)
    testImplementation(libs.reactor.test)
}

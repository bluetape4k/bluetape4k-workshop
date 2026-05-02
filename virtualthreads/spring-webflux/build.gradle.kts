plugins {
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.graalvm.native)
    alias(libs.plugins.gatling.plugin)
}

springBoot {
    mainClass.set("io.bluetape4k.workshop.webflux.virtualthread.WebfluxVirtualThreadAppKt")
}


configurations {
    compileOnly.get().extendsFrom(annotationProcessor.get())
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {

    // bluetape4k
    implementation(libs.bluetape4k.core)
    // VirtualThread of JDK 25
    implementation(libs.bluetape4k.virtualthread.api)
    runtimeOnly(libs.bluetape4k.virtualthread.jdk25)

    implementation(libs.bluetape4k.io)
    implementation(libs.bluetape4k.jackson3)
    implementation(libs.bluetape4k.testcontainers)

    // Spring Boot
    implementation(libs.spring.boot.autoconfigure.lib)
    annotationProcessor(libs.spring.boot.autoconfigure.processor)
    annotationProcessor(libs.spring.boot.configuration.processor)
    runtimeOnly(libs.spring.boot.devtools)

    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.aspectj)
    implementation(libs.spring.boot.starter.webflux.lib)
    testImplementation(libs.spring.boot.starter.webflux.test)

    testImplementation(libs.spring.boot.starter.test) {
        exclude(group = "junit", module = "junit")
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
        exclude(module = "mockito-core")
    }

    // SpringDoc - OpenAPI 3.0
    implementation(libs.springdoc.openapi.starter.webmvc.ui)

    // Coroutines
    implementation(libs.bluetape4k.coroutines)
    implementation(libs.kotlinx.coroutines.core.lib)
    implementation(libs.kotlinx.coroutines.reactor)
    testImplementation(libs.kotlinx.coroutines.test.lib)

    // Reactor
    implementation(libs.reactor.core)
    implementation(libs.reactor.kotlin.extensions)
    testImplementation(libs.reactor.test)

    // Gatling
    implementation(libs.gatling.app)
    implementation(libs.gatling.core.java)
    implementation(libs.gatling.http.java)
    implementation(libs.gatling.recorder)
    implementation(libs.gatling.charts.highcharts)
    testImplementation(libs.gatling.test.framework)

    implementation(libs.datafaker)
}

plugins {
    alias(libs.plugins.kotlin.spring)
}

configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    runtimeOnly(libs.spring.boot.devtools)
    annotationProcessor(libs.spring.boot.configuration.processor)

    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.aspectj)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.webflux.lib)
    testImplementation(libs.spring.boot.starter.webflux.test)

    implementation(libs.spring.boot.starter.data.mongodb.lib)
    implementation(libs.spring.boot.starter.data.mongodb.reactive)
    testImplementation(libs.spring.boot.starter.data.mongodb.test)

    testImplementation(libs.spring.boot.starter.test) {
        exclude(group = "junit", module = "junit")
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
        exclude(module = "mockito-core")
    }

    // Mongo Driver
    implementation(libs.mongodb.driver.kotlin.sync)
    implementation(libs.mongodb.driver.kotlin.coroutine)
    implementation(libs.mongodb.driver.kotlin.extensions)

    // MongoDB Testcontainers
    implementation(libs.bluetape4k.testcontainers)
    implementation(libs.testcontainers.lib)
    implementation(libs.testcontainers.mongodb)

    // Coroutines
    implementation(libs.bluetape4k.coroutines)
    implementation(libs.kotlinx.coroutines.core.lib)
    implementation(libs.kotlinx.coroutines.reactive)
    implementation(libs.kotlinx.coroutines.reactor)
    testImplementation(libs.kotlinx.coroutines.test.lib)

    implementation(libs.bluetape4k.idgenerators)
    testImplementation(libs.bluetape4k.junit5)

    testImplementation(libs.reactor.test)
    testImplementation(libs.turbine)
}

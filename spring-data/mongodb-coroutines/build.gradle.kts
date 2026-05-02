plugins {
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.graalvm.native)
}

springBoot {
    mainClass.set("io.bluetape4k.workshop.mongodb.MongodbApplicationKt")
}

configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.aspectj)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.webflux.lib)
    testImplementation(libs.spring.boot.starter.webflux.test)

    implementation(libs.spring.boot.starter.data.mongodb.lib)
    implementation(libs.spring.boot.starter.data.mongodb.reactive)
    testImplementation(libs.spring.boot.starter.data.mongodb.test)

    runtimeOnly(libs.spring.boot.devtools)
    annotationProcessor(libs.spring.boot.configuration.processor)

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

    implementation(libs.reactor.kotlin.extensions)
    testImplementation(libs.reactor.test)
    testImplementation(libs.turbine)

    implementation(libs.bluetape4k.idgenerators)
    implementation(libs.bluetape4k.netty)

    implementation(libs.jackson3.module.kotlin)
    implementation(libs.jackson3.module.blackbird)
}

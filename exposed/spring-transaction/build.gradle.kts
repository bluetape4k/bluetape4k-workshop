plugins {
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.gatling.plugin)
}


configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    implementation(platform(libs.jetbrains.exposed.bom))
    implementation(project(":exposed-domain"))

    // Exposed
    implementation(libs.exposed.core)
    implementation(libs.exposed.dao)
    implementation(libs.jetbrains.exposed.core)
    implementation(libs.jetbrains.exposed.dao)
    implementation(libs.jetbrains.exposed.jdbc)
    implementation(libs.jetbrains.exposed.kotlin.datetime)
    implementation(libs.jetbrains.exposed.spring.boot4.starter)

    // Database Drivers
    implementation(libs.hikaricp)

    // H2
    implementation(libs.h2.v2)

    // Bluetape4k
    implementation(libs.bluetape4k.idgenerators)
    implementation(libs.bluetape4k.io)

    // Jackson for Kotlin
    implementation(libs.jackson3.module.kotlin)
    implementation(libs.jackson3.module.blackbird)

    // Spring Boot 4
    implementation(libs.spring.boot.autoconfigure.lib)
    annotationProcessor(libs.spring.boot.autoconfigure.processor)
    annotationProcessor(libs.spring.boot.configuration.processor)
    runtimeOnly(libs.spring.boot.devtools)
    
    implementation(libs.spring.boot.starter.jdbc.lib)
    implementation(libs.spring.boot.starter.jdbc.test)
    testImplementation(libs.spring.boot.starter.test) {
        exclude(group = "junit", module = "junit")
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
        exclude(module = "mockito-core")
    }

    // Coroutines
    implementation(libs.bluetape4k.coroutines)
    testImplementation(libs.kotlinx.coroutines.core.lib)
    testImplementation(libs.kotlinx.coroutines.reactor)
    testImplementation(libs.kotlinx.coroutines.debug)
    testImplementation(libs.kotlinx.coroutines.test.lib)
}

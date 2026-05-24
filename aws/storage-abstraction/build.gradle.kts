plugins {
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.kotlin.noarg)
    alias(libs.plugins.spring.boot)
}

springBoot {
    mainClass.set("io.bluetape4k.workshop.storage.StorageApplicationKt")
}

configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    // bluetape4k
    implementation(libs.bluetape4k.core)
    implementation(libs.bluetape4k.coroutines)
    implementation(libs.bluetape4k.jackson3)
    implementation(libs.bluetape4k.aws)

    // AWS SDK v2
    implementation(libs.aws2.s3.lib)

    // Testcontainers for LocalStack (also needed at runtime for embedded test config)
    implementation(libs.bluetape4k.testcontainers)
    implementation(libs.testcontainers.localstack)

    // Spring Boot
    implementation(libs.spring.boot.autoconfigure.lib)
    annotationProcessor(libs.spring.boot.autoconfigure.processor)
    annotationProcessor(libs.spring.boot.configuration.processor)
    runtimeOnly(libs.spring.boot.devtools)

    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.actuator)

    testImplementation(libs.spring.boot.starter.test) {
        exclude(group = "junit", module = "junit")
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
        exclude(module = "mockito-core")
    }

    // Coroutines
    implementation(libs.kotlinx.coroutines.core.lib)
    testImplementation(libs.kotlinx.coroutines.test.lib)

    // Jackson
    implementation(libs.jackson3.databind)
    implementation(libs.jackson3.module.kotlin)
}

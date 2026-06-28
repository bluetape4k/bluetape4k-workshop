plugins {
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
}

atomicfu {
    transformJvm = false
}

springBoot {
    mainClass.set("io.bluetape4k.workshop.imageprocessing.ocr.ImageOcrApiApplicationKt")
}

configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

tasks.withType<Test>().configureEach {
    System.getProperty("ocr.enabled")?.let { systemProperty("ocr.enabled", it) }
}

dependencies {
    implementation(libs.bluetape4k.core)
    implementation(libs.bluetape4k.logging)
    implementation(libs.bluetape4k.jackson3)
    implementation(libs.bluetape4k.images)
    implementation(libs.bluetape4k.images.ocr)

    implementation(libs.kotlinx.coroutines.core.lib)
    implementation(libs.kotlinx.coroutines.reactor)
    implementation(libs.reactor.core)

    implementation(libs.spring.boot.autoconfigure.lib)
    annotationProcessor(libs.spring.boot.autoconfigure.processor)
    annotationProcessor(libs.spring.boot.configuration.processor)
    runtimeOnly(libs.spring.boot.devtools)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.webmvc.lib)

    testImplementation(project(":shared"))
    testImplementation(libs.bluetape4k.junit5)
    testImplementation(libs.bluetape4k.assertions)
    testImplementation(libs.mockk)
    testImplementation(libs.springmockk)
    testImplementation(libs.kotlinx.coroutines.test.lib)
    testImplementation(libs.spring.boot.starter.webmvc.test)
    testImplementation(libs.spring.boot.starter.test) {
        exclude(group = "junit", module = "junit")
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
        exclude(module = "mockito-core")
    }
}

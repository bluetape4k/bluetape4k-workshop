plugins {
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
}

atomicfu {
    transformJvm = false
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

kotlin {
    jvmToolchain(25)
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(25)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    jvmTargetValidationMode.set(org.jetbrains.kotlin.gradle.dsl.jvm.JvmTargetValidationMode.WARNING)
}

springBoot {
    mainClass.set("io.bluetape4k.workshop.imageprocessing.advanced.ImageProcessingAdvancedApplicationKt")
}

configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

tasks.withType<Test>().configureEach {
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(25))
    })
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    // FFM libvips runtime initialization is process-wide; isolate tests that opt into native execution.
    forkEvery = 1
    maxParallelForks = 1
    System.getProperty("vips.enabled")?.let { systemProperty("vips.enabled", it) }
    val homebrewLib = "/opt/homebrew/lib"
    if (file(homebrewLib).exists()) {
        environment("DYLD_LIBRARY_PATH", homebrewLib)
    }
}

dependencies {
    implementation(libs.bluetape4k.core)
    implementation(libs.bluetape4k.coroutines)
    implementation(libs.bluetape4k.logging)
    implementation(libs.bluetape4k.micrometer)
    implementation(libs.bluetape4k.jackson3)

    // Exposed ORM (bluetape4k wrappers)
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.jackson3)

    // JetBrains Exposed
    implementation(libs.jetbrains.exposed.java.time)
    implementation(libs.jetbrains.exposed.spring.boot4.starter)
    implementation(libs.jetbrains.exposed.spring7.transaction)

    // Database
    implementation(libs.hikaricp)
    runtimeOnly(libs.postgresql.driver)

    implementation(libs.bluetape4k.images)
    implementation(libs.bluetape4k.images.spring.boot)
    implementation(libs.bluetape4k.images.vips.api)
    implementation(libs.bluetape4k.images.vips.java25)

    implementation(libs.kotlinx.coroutines.core.lib)

    implementation(libs.micrometer.core)

    implementation(libs.spring.boot.autoconfigure.lib)
    annotationProcessor(libs.spring.boot.autoconfigure.processor)
    annotationProcessor(libs.spring.boot.configuration.processor)
    runtimeOnly(libs.spring.boot.devtools)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.webmvc.lib)

    testImplementation(project(":shared"))
    testImplementation(libs.bluetape4k.testcontainers)
    testImplementation(libs.testcontainers.postgresql)

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

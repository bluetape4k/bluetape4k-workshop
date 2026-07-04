plugins {
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
}

atomicfu {
    transformJvm = false
}

springBoot {
    mainClass.set("io.bluetape4k.workshop.imageprocessing.profile.ProfileImageModerationApplicationKt")
}

configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    implementation(libs.bluetape4k.core)
    implementation(libs.bluetape4k.coroutines)
    implementation(libs.bluetape4k.idgenerators)
    implementation(libs.bluetape4k.logging)
    implementation(libs.bluetape4k.micrometer)
    implementation(libs.bluetape4k.images)
    implementation(libs.bluetape4k.images.spring.boot)

    implementation(libs.kotlinx.coroutines.core.lib)
    implementation(libs.micrometer.core)

    implementation(libs.spring.boot.autoconfigure.lib)
    annotationProcessor(libs.spring.boot.autoconfigure.processor)
    annotationProcessor(libs.spring.boot.configuration.processor)
    runtimeOnly(libs.spring.boot.devtools)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.webmvc.lib)

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

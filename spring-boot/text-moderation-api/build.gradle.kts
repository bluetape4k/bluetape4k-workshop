plugins {
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
}

springBoot {
    mainClass.set("io.bluetape4k.workshop.textmoderation.TextModerationApplicationKt")
    buildInfo()
}

dependencies {
    implementation(libs.bluetape4k.core)
    implementation(libs.bluetape4k.logging)
    implementation(libs.bluetape4k.text.core)
    implementation(libs.bluetape4k.text.search)
    implementation(libs.bluetape4k.text.lingua)

    implementation(libs.spring.boot.autoconfigure.lib)
    annotationProcessor(libs.spring.boot.autoconfigure.processor)
    annotationProcessor(libs.spring.boot.configuration.processor)
    runtimeOnly(libs.spring.boot.devtools)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.webmvc.lib)

    testImplementation(libs.bluetape4k.assertions)
    testImplementation(libs.bluetape4k.junit5)
    testImplementation(libs.spring.boot.starter.webmvc.test)
    testImplementation(libs.spring.boot.starter.test) {
        exclude(group = "junit", module = "junit")
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
        exclude(module = "mockito-core")
    }
}

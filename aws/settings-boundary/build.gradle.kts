plugins {
    application
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
}

application {
    mainClass.set("io.bluetape4k.workshop.aws.settings.SettingsBoundaryApplicationKt")
}

springBoot {
    mainClass.set("io.bluetape4k.workshop.aws.settings.SettingsBoundarySpringApplicationKt")
    buildInfo()
}

configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    implementation(libs.aws.kotlin.secretsmanager)
    implementation(libs.aws.kotlin.ssm)
    implementation(libs.aws2.appconfigdata.lib)
    implementation(libs.bluetape4k.aws)
    implementation(libs.bluetape4k.aws.kotlin)
    implementation(libs.bluetape4k.core)
    implementation(libs.bluetape4k.coroutines)
    implementation(libs.bluetape4k.logging)
    implementation(libs.kotlinx.coroutines.core.lib)

    implementation(libs.spring.boot.autoconfigure.lib)
    annotationProcessor(libs.spring.boot.autoconfigure.processor)
    annotationProcessor(libs.spring.boot.configuration.processor)

    testImplementation(libs.bluetape4k.assertions)
    testImplementation(libs.bluetape4k.junit5)
    testImplementation(libs.kotlinx.coroutines.test.lib)
    testImplementation(libs.mockk)
    testImplementation(libs.awaitility.kotlin)
    testImplementation(libs.spring.boot.starter.test) {
        exclude(group = "junit", module = "junit")
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
        exclude(module = "mockito-core")
    }
}

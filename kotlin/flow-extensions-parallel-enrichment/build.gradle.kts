plugins {
    alias(libs.plugins.kotlin.jvm)
}

configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    // Core coroutine extensions and shared test utilities
    implementation(libs.bluetape4k.coroutines)
    implementation(libs.kotlinx.coroutines.core.lib)
    testImplementation(libs.bluetape4k.junit5)
    testImplementation(libs.bluetape4k.assertions)
    testImplementation(libs.kotlinx.coroutines.test.lib)

    testImplementation(libs.logback.lib)
}

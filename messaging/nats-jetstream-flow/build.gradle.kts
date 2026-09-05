plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    implementation(libs.bluetape4k.nats)
    implementation(libs.kotlinx.coroutines.core.lib)

    testImplementation(libs.bluetape4k.assertions)
    testImplementation(libs.bluetape4k.junit5)
    testImplementation(libs.bluetape4k.testcontainers)
    testImplementation(libs.kotlinx.coroutines.test.lib)
    testImplementation(libs.mockk)
}

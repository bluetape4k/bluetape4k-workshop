plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    // bluetape4k JaVers integration
    implementation(libs.bluetape4k.javers.core)

    // JetBrains Exposed
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)

    // H2 in-memory database
    runtimeOnly(libs.h2.v2)

    // Test
    testImplementation(libs.bluetape4k.assertions)
}

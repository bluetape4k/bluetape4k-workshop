plugins {
    alias(libs.plugins.kotlin.jvm)
}

tasks.test {
    useJUnitPlatform()
}

dependencies {
    // Graph core + TinkerGraph (in-memory, no Docker needed)
    implementation(libs.bluetape4k.graph.core)
    implementation(libs.bluetape4k.graph.tinkerpop)

    // Graph IO format adapters
    implementation(libs.bluetape4k.graph.io.core)
    implementation(libs.bluetape4k.graph.io.csv)
    implementation(libs.bluetape4k.graph.io.graphml)
    implementation(libs.bluetape4k.graph.io.jackson3)

    // Bluetape4k
    implementation(libs.bluetape4k.logging)

    // Test
    testImplementation(project(":shared"))
    testImplementation(libs.bluetape4k.junit5)
    testImplementation(libs.bluetape4k.assertions)
}

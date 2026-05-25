plugins {
    alias(libs.plugins.kotlin.jvm)
}

configurations {
    // Expose compileOnly and runtimeOnly dependencies to testImplementation via extendsFrom.
    // This avoids duplicating neo4j/memgraph on both compileOnly and testImplementation.
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

tasks.test {
    useJUnitPlatform {
        // Integration tests require running Docker containers — exclude from the default test task.
        // Run them with: ./gradlew :graph-abuser-detection:integrationTest
        excludeTags("integration")
    }
}

tasks.register<Test>("integrationTest") {
    description = "Runs Neo4j and Memgraph integration tests (requires Docker)."
    group = "verification"
    useJUnitPlatform {
        includeTags("integration")
    }
    // Inherit the same JVM args as the standard test task.
    jvmArgs = tasks.test.get().jvmArgs
}

dependencies {
    // Graph core + TinkerGraph (in-memory, no Docker needed for default tests)
    // Version managed by bluetape4k-dependencies BOM (currently 0.4.1)
    implementation(libs.bluetape4k.graph.core)
    implementation(libs.bluetape4k.graph.tinkerpop)

    // Neo4j and Memgraph backends — compileOnly so tests see them via extendsFrom
    compileOnly(libs.bluetape4k.graph.neo4j)
    compileOnly(libs.bluetape4k.graph.memgraph)

    // Bluetape4k
    implementation(libs.bluetape4k.logging)
    implementation(libs.bluetape4k.coroutines)

    // Coroutines
    implementation(libs.kotlinx.coroutines.core.lib)
    testImplementation(libs.kotlinx.coroutines.test.lib)

    // Test
    testImplementation(project(":shared"))
    testImplementation(libs.bluetape4k.junit5)
    testImplementation(libs.bluetape4k.testcontainers)
    // Required for Neo4jServer: Neo4jContainer supertype must be on the classpath
    testImplementation(libs.testcontainers.neo4j)
    testImplementation(libs.bluetape4k.assertions)
    testImplementation(libs.mockk)
}

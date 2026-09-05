plugins {
    alias(libs.plugins.kotlin.jvm)
}

configurations {
    // extendsFrom으로 compileOnly/runtimeOnly 의존성을 testImplementation에 노출한다.
    // neo4j/memgraph를 compileOnly와 testImplementation 양쪽에 중복 선언하지 않기 위한 설정이다.
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

tasks.test {
    useJUnitPlatform {
        // integration 테스트는 Docker 컨테이너가 필요하므로 기본 test task에서 제외한다.
        // 실행 명령: ./gradlew :graph-abuser-detection:integrationTest
        excludeTags("integration")
    }
}

tasks.register<Test>("integrationTest") {
    description = "Runs Neo4j and Memgraph integration tests (requires Docker)."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform {
        includeTags("integration")
    }
    shouldRunAfter(tasks.test)
    usesService(gradle.sharedServices.registrations.named("test-mutex").get().service)
    // 표준 test task와 같은 JVM 인자를 상속한다.
    jvmArgs = tasks.test.get().jvmArgs
}

dependencies {
    // Graph core + TinkerGraph: in-memory 백엔드라 기본 테스트에 Docker가 필요하지 않다.
    // 버전은 bluetape4k-dependencies BOM이 관리한다. 현재 기준은 2.0.0이다.
    implementation(libs.bluetape4k.graph.core)
    implementation(libs.bluetape4k.graph.tinkerpop)

    // Neo4j와 Memgraph backend는 compileOnly로 두고, 테스트에서는 extendsFrom을 통해 보이게 한다.
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
    // Neo4jServer에는 Neo4jContainer 상위 타입이 classpath에 있어야 한다.
    testImplementation(libs.testcontainers.neo4j)
    testImplementation(libs.bluetape4k.assertions)
    testImplementation(libs.mockk)
}

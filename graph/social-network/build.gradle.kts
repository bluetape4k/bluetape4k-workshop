plugins {
    alias(libs.plugins.kotlin.jvm)
}

configurations {
    // compileOnly와 runtimeOnly 의존성을 extendsFrom으로 testImplementation에 노출합니다.
    // neo4j/memgraph를 compileOnly와 testImplementation 양쪽에 중복 선언하지 않게 합니다.
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

tasks.test {
    useJUnitPlatform {
        // 통합 테스트는 Docker 컨테이너가 필요하므로 기본 test task에서 제외합니다.
        // 실행 명령: ./gradlew :graph-social-network:integrationTest
        excludeTags("integration")
    }
}

tasks.register<Test>("integrationTest") {
    description = "Runs Neo4j and Memgraph integration tests (requires Docker)."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    shouldRunAfter(tasks.test)
    usesService(gradle.sharedServices.registrations.named("test-mutex").get().service)
    useJUnitPlatform {
        includeTags("integration")
    }
    // 표준 test task와 같은 JVM 인자를 상속합니다.
    jvmArgs = tasks.test.get().jvmArgs
}

dependencies {
    // Graph core와 TinkerGraph(인메모리, 기본 테스트에는 Docker 불필요)
    // 버전은 bluetape4k-dependencies BOM이 관리합니다(현재 0.4.1).
    implementation(libs.bluetape4k.graph.core)
    implementation(libs.bluetape4k.graph.tinkerpop)

    // Neo4j와 Memgraph backend는 compileOnly로 두고, 테스트는 extendsFrom으로 참조합니다.
    compileOnly(libs.bluetape4k.graph.neo4j)
    compileOnly(libs.bluetape4k.graph.memgraph)

    // Bluetape4k 공통 기능
    implementation(libs.bluetape4k.logging)
    implementation(libs.bluetape4k.coroutines)

    // 코루틴
    implementation(libs.kotlinx.coroutines.core.lib)
    testImplementation(libs.kotlinx.coroutines.test.lib)

    // 테스트
    testImplementation(project(":shared"))
    testImplementation(libs.bluetape4k.junit5)
    testImplementation(libs.bluetape4k.testcontainers)
    // Neo4jServer에 필요합니다. Neo4jContainer 상위 타입이 classpath에 있어야 합니다.
    testImplementation(libs.testcontainers.neo4j)
    testImplementation(libs.bluetape4k.assertions)
    testImplementation(libs.mockk)
}

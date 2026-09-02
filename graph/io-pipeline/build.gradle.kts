plugins {
    alias(libs.plugins.kotlin.jvm)
}

tasks.test {
    useJUnitPlatform()
}

dependencies {
    // Graph core와 TinkerGraph(인메모리, Docker 불필요)
    implementation(libs.bluetape4k.graph.core)
    implementation(libs.bluetape4k.graph.tinkerpop)

    // Graph IO 형식 어댑터
    implementation(libs.bluetape4k.graph.io.core)
    implementation(libs.bluetape4k.graph.io.csv)
    implementation(libs.bluetape4k.graph.io.graphml)
    implementation(libs.bluetape4k.graph.io.jackson3)
    implementation(libs.bluetape4k.graph.io.micrometer)

    // Bluetape4k 공통 기능
    implementation(libs.bluetape4k.logging)

    // 테스트
    testImplementation(project(":shared"))
    testImplementation(libs.bluetape4k.junit5)
    testImplementation(libs.bluetape4k.assertions)
}

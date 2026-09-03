plugins {
    `java-test-fixtures`
}

tasks.withType<Test>().configureEach {
    systemProperty("bluetape4k.workshop.shared.projectDir", projectDir.absolutePath)
}

configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    implementation(libs.bluetape4k.io)
    testImplementation(libs.bluetape4k.spring.boot4.core)

    // Web MVC 의존성
    compileOnly(libs.spring.boot.starter.webmvc.lib)

    // WebFlux 의존성
    compileOnly(libs.spring.boot.starter.webflux.lib)

    compileOnly(libs.spring.boot.starter.test) {
        exclude(group = "junit", module = "junit")
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
        exclude(module = "mockito-core")
    }

    // 코루틴 의존성
    compileOnly(libs.bluetape4k.coroutines)
    compileOnly(libs.kotlinx.coroutines.core.lib)
    compileOnly(libs.kotlinx.coroutines.reactor)
    testImplementation(libs.kotlinx.coroutines.test.lib)

    // Netty 테스트 의존성
    testImplementation(libs.bluetape4k.netty)

    compileOnly(libs.bluetape4k.testcontainers)
    compileOnly(libs.bluetape4k.testcontainers.spring)
    testImplementation(libs.bluetape4k.testcontainers)
    testImplementation(libs.bluetape4k.testcontainers.spring)
    testImplementation(libs.testcontainers.lib)
}

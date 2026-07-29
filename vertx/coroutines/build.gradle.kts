configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    implementation(libs.bluetape4k.io)
    implementation(libs.bluetape4k.jdbc)

    // Vert.x 핵심 의존성입니다.
    implementation(libs.bluetape4k.vertx)
    testImplementation(libs.vertx.junit5)

    // Vert.x Kotlin 확장 의존성입니다.
    implementation(libs.vertx.core)
    implementation(libs.vertx.lang.kotlin.lib)
    implementation(libs.vertx.lang.kotlin.coroutines)

    // Vert.x JDBC 의존성입니다.
    implementation(libs.vertx.jdbc.client)
    implementation(libs.agroal.pool)
    implementation(libs.h2.lib)

    // Vert.x Web 및 WebClient 의존성입니다.
    implementation(libs.vertx.web.lib)
    implementation(libs.vertx.web.client)

    // JSON 처리 의존성입니다.
    implementation(libs.bluetape4k.jackson3)
    implementation(libs.jackson3.module.kotlin)
    implementation(libs.jackson3.module.blackbird)

    // coroutine 연동 의존성입니다.
    implementation(libs.bluetape4k.coroutines)
    implementation(libs.kotlinx.coroutines.core.lib)
    implementation(libs.kotlinx.coroutines.reactor)
    testImplementation(libs.kotlinx.coroutines.test.lib)

    implementation(libs.logback.lib)
}

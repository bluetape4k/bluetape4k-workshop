configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    implementation(libs.bluetape4k.io)
    implementation(libs.bluetape4k.jdbc)

    // Vertx
    implementation(libs.bluetape4k.vertx)
    testImplementation(libs.vertx.junit5)

    // Vertx Kotlin
    implementation(libs.vertx.core)
    implementation(libs.vertx.lang.kotlin.lib)
    implementation(libs.vertx.lang.kotlin.coroutines)

    // Vertx Jdbc
    implementation(libs.vertx.jdbc.client)
    implementation(libs.agroal.pool)
    implementation(libs.h2.lib)

    // Vertx Web & WebClient
    implementation(libs.vertx.web.lib)
    implementation(libs.vertx.web.client)

    // Json
    implementation(libs.bluetape4k.jackson3)
    implementation(libs.jackson3.module.kotlin)
    implementation(libs.jackson3.module.blackbird)

    // Coroutines
    implementation(libs.bluetape4k.coroutines)
    implementation(libs.kotlinx.coroutines.core.lib)
    implementation(libs.kotlinx.coroutines.reactor)
    testImplementation(libs.kotlinx.coroutines.test.lib)

    implementation(libs.logback.lib)
}

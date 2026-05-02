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
    implementation(libs.vertx.web.lib)
    implementation(libs.vertx.web.client)

    // Vetx SqlClient Templates 에서 Jackson Databind 를 이용한 매핑을 사용한다
    implementation(libs.bluetape4k.jackson3)
    implementation(libs.jackson3.module.kotlin)
    implementation(libs.jackson3.module.blackbird)

    // Coroutines
    implementation(libs.bluetape4k.coroutines)
    implementation(libs.kotlinx.coroutines.core.lib)
    implementation(libs.kotlinx.coroutines.reactor)
    testImplementation(libs.kotlinx.coroutines.test.lib)
}

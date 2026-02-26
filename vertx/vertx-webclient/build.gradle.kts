configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    implementation(Libs.bluetape4k_io)
    implementation(Libs.bluetape4k_jdbc)

    // Vertx
    api(Libs.bluetape4k_vertx_core)
    testImplementation(Libs.vertx_junit5)

    // Vertx Kotlin
    implementation(Libs.vertx_core)
    implementation(Libs.vertx_lang_kotlin)
    implementation(Libs.vertx_lang_kotlin_coroutines)
    implementation(Libs.vertx_web)
    implementation(Libs.vertx_web_client)

    // Vetx SqlClient Templates 에서 Jackson Databind 를 이용한 매핑을 사용한다
    implementation(Libs.bluetape4k_jackson3)
    implementation(Libs.jackson3_module_kotlin)
    implementation(Libs.jackson3_module_blackbird)

    // Coroutines
    implementation(Libs.bluetape4k_coroutines)
    implementation(Libs.kotlinx_coroutines_core)
    implementation(Libs.kotlinx_coroutines_reactor)
    testImplementation(Libs.kotlinx_coroutines_test)
}

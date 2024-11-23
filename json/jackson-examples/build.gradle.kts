configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    // Jackson
    implementation(Libs.bluetape4k_jackson)
    implementation(Libs.jackson_databind)
    implementation(Libs.jackson_datatype_jdk8)
    implementation(Libs.jackson_datatype_jsr310)
    implementation(Libs.jackson_module_kotlin)
    implementation(Libs.jackson_module_blackbird)

    compileOnly(Libs.jsonpath)
    testImplementation(Libs.jsonassert)

    // bluetape4k
    implementation(Libs.bluetape4k_core)
    implementation(Libs.bluetape4k_coroutines)
    testImplementation(Libs.bluetape4k_junit5)
}

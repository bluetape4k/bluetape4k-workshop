configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    // Jackson
    implementation(Libs.bluetape4k_jackson3)
    implementation(Libs.jackson3_databind)
    implementation(Libs.jackson3_module_kotlin)
    implementation(Libs.jackson3_module_blackbird)

    compileOnly(Libs.jsonpath)
    testImplementation(Libs.jsonassert)

    // bluetape4k
    implementation(Libs.bluetape4k_core)
    implementation(Libs.bluetape4k_coroutines)
    testImplementation(Libs.bluetape4k_junit5)
}

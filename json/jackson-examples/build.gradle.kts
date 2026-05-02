configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    // Jackson
    implementation(libs.bluetape4k.jackson3)
    implementation(libs.jackson3.databind)
    implementation(libs.jackson3.module.kotlin)
    implementation(libs.jackson3.module.blackbird)

    compileOnly(libs.jsonpath)
    testImplementation(libs.jsonassert)

    // bluetape4k
    implementation(libs.bluetape4k.core)
    implementation(libs.bluetape4k.coroutines)
    testImplementation(libs.bluetape4k.junit5)
}

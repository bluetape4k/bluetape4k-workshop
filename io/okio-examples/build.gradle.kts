configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    // OKIO
    implementation(Libs.okio)
    implementation(Libs.okio_fakefilesystem)

    // bluetape4k
    implementation(Libs.bluetape4k_io)
    implementation(Libs.bluetape4k_crypto)
    implementation(Libs.bluetape4k_coroutines)
    testImplementation(Libs.bluetape4k_junit5)

    // Coroutines
    implementation(Libs.kotlinx_coroutines_core)
    testImplementation(Libs.kotlinx_coroutines_test)
}

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

    implementation(Libs.commons_io)

    // Coroutines
    implementation(Libs.kotlinx_coroutines_core)
    testImplementation(Libs.kotlinx_coroutines_test)

    // Serialization Libraries
    compileOnly(Libs.kryo5)
    compileOnly(Libs.fory_kotlin)

    // Compression libraries
    compileOnly(Libs.commons_compress)
    compileOnly(Libs.lz4_java)
    compileOnly(Libs.snappy_java)
    compileOnly(Libs.zstd_jni)
}

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
    implementation(Libs.kryo5)
    implementation(Libs.fory_kotlin)

    // Compression libraries
    implementation(Libs.commons_compress)
    implementation(Libs.lz4_java)
    implementation(Libs.snappy_java)
    implementation(Libs.zstd_jni)
}

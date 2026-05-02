configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    // OKIO
    implementation(libs.okio.lib)
    implementation(libs.okio.fakefilesystem)

    // bluetape4k
    implementation(libs.bluetape4k.io)
    implementation(libs.bluetape4k.okio)
    implementation(libs.bluetape4k.coroutines)
    testImplementation(libs.bluetape4k.junit5)

    implementation(libs.commons.io)

    // Coroutines
    implementation(libs.kotlinx.coroutines.core.lib)
    testImplementation(libs.kotlinx.coroutines.test.lib)

    // Serialization Libraries
    implementation(libs.kryo5)
    implementation(libs.fory.kotlin)

    // Compression libraries
    implementation(libs.commons.compress)
    implementation(libs.lz4.java)
    implementation(libs.snappy.java)
    implementation(libs.zstd.jni)
}

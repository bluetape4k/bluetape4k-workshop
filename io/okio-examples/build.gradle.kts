configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    // OKIO 의존성입니다.
    implementation(libs.okio.lib)
    implementation(libs.okio.fakefilesystem)

    // bluetape4k 공통 의존성입니다.
    implementation(libs.bluetape4k.io)
    implementation(libs.bluetape4k.okio)
    implementation(libs.bluetape4k.coroutines)
    testImplementation(libs.bluetape4k.junit5)

    implementation(libs.commons.io)

    // coroutine 의존성입니다.
    implementation(libs.kotlinx.coroutines.core.lib)
    testImplementation(libs.kotlinx.coroutines.test.lib)

    // serialization library 의존성입니다.
    implementation(libs.kryo5)
    implementation(libs.fory.kotlin)

    // compression library 의존성입니다.
    implementation(libs.commons.compress)
    implementation(libs.lz4.java)
    implementation(libs.snappy.java)
    implementation(libs.zstd.jni)
}

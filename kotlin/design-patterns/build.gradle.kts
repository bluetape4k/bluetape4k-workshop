configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    implementation(libs.bluetape4k.io)
    testImplementation(libs.bluetape4k.junit5)

    implementation(libs.bluetape4k.coroutines)
    testImplementation(libs.kotlinx.coroutines.test.lib)
}

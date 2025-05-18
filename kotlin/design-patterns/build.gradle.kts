configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    implementation(Libs.bluetape4k_io)
    testImplementation(Libs.bluetape4k_junit5)

    implementation(Libs.bluetape4k_coroutines)
    testImplementation(Libs.kotlinx_coroutines_test)
}

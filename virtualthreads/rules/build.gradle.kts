
configurations {
    compileOnly.get().extendsFrom(annotationProcessor.get())
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {

    implementation(Libs.bluetape4k_core)
    // VirtualThread of JDK 25
    implementation(Libs.bluetape4k_virtualthread_api)
    runtimeOnly(Libs.bluetape4k_virtualthread_jdk25)

    // Coroutines
    implementation(Libs.bluetape4k_coroutines)
    implementation(Libs.kotlinx_coroutines_core)
    implementation(Libs.kotlinx_coroutines_reactor)
    testImplementation(Libs.kotlinx_coroutines_test)

}

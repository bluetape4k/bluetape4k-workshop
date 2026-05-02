
configurations {
    compileOnly.get().extendsFrom(annotationProcessor.get())
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {

    implementation(libs.bluetape4k.core)
    // VirtualThread of JDK 25
    implementation(libs.bluetape4k.virtualthread.api)
    // runtimeOnly(libs.bluetape4k.virtualthread.jdk21)
    runtimeOnly(libs.bluetape4k.virtualthread.jdk25)

    // Coroutines
    implementation(libs.bluetape4k.coroutines)
    implementation(libs.kotlinx.coroutines.core.lib)
    implementation(libs.kotlinx.coroutines.reactor)
    testImplementation(libs.kotlinx.coroutines.test.lib)

}

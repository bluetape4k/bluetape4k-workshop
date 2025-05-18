configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    implementation(Libs.bluetape4k_mutiny)

    implementation(Libs.kotlinx_atomicfu)

    // Smallrye Mutiny
    implementation(Libs.mutiny)
    implementation(Libs.mutiny_kotlin)

    // Coroutines
    implementation(Libs.bluetape4k_coroutines)
    implementation(Libs.kotlinx_coroutines_core)
    testImplementation(Libs.kotlinx_coroutines_test)
}

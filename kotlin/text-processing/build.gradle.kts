plugins {
    alias(libs.plugins.kotlin.jvm)
}

configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    // bluetape4k-text — AhoCorasick pattern search 예제입니다.
    implementation(libs.bluetape4k.text.search)
    // bluetape4k-text — Lingua language detection 예제입니다.
    implementation(libs.bluetape4k.text.lingua)
    // bluetape4k-text — Korean tokenizer 와 blockword processing 예제입니다.
    implementation(libs.bluetape4k.text.korean)
    // bluetape4k-text — Japanese tokenizer 와 blockword processing 예제입니다.
    implementation(libs.bluetape4k.text.japanese)

    implementation(libs.kotlinx.coroutines.core.lib)
    implementation(libs.bluetape4k.logging)

    testImplementation(libs.bluetape4k.junit5)
    testImplementation(libs.bluetape4k.assertions)
    testImplementation(libs.mockk)
    testImplementation(libs.logback.lib)
}

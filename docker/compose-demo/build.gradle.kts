configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {

    implementation(Libs.bluetape4k_core)

    testImplementation(Libs.redisson)

    testImplementation(Libs.testcontainers)
    testImplementation(Libs.testcontainers_junit_jupiter)

    // Apple Silicon M1 에서 linux/amd64 platform 용 Docker 이미지를 실행하기 위해서 필요한 라이브러리
    testImplementation(Libs.jna)
    testImplementation(Libs.jna_platform)
}

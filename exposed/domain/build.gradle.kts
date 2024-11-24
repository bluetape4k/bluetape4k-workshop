configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    implementation(platform(Libs.exposed_bom))

    api(Libs.exposed_core)
    api(Libs.exposed_dao)
    api(Libs.exposed_jdbc)
    api(Libs.exposed_kotlin_datetime)
    implementation(Libs.exposed_spring_boot_starter)

    implementation(Libs.bluetape4k_jdbc)
    testImplementation(Libs.bluetape4k_junit5)

    testImplementation(Libs.h2_v2)
    testImplementation(Libs.mysql_connector_j)
    testImplementation(Libs.postgresql_driver)

    testImplementation(Libs.bluetape4k_testcontainers)
    testImplementation(Libs.testcontainers)
    testImplementation(Libs.testcontainers_mysql)
    testImplementation(Libs.testcontainers_postgresql)

    // Identifier 자동 생성
    api(Libs.bluetape4k_idgenerators)
    api(Libs.java_uuid_generator)

    // Coroutines
    implementation(Libs.bluetape4k_coroutines)
    implementation(Libs.kotlinx_coroutines_core)
    testImplementation(Libs.kotlinx_coroutines_test)

}

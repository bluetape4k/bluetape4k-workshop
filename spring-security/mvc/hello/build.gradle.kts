plugins {
    kotlin("plugin.spring")
    id(Plugins.spring_boot)
    id(Plugins.graalvm_native)
}

springBoot {
    mainClass.set("io.bluetape4k.workshop.spring.security.mvc.KotlinApplicationKt")
}

// NOTE: implementation 나 runtimeOnly로 지정된 Dependency를 testimplementation 으로도 지정하도록 합니다.
configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    api(Libs.bluetape4k_spring_core)
    testImplementation(Libs.bluetape4k_jackson)
    testImplementation(Libs.bluetape4k_junit5)

    // Spring Security
    implementation(Libs.springBootStarter("security"))
    implementation(Libs.springBootStarter("web"))
    implementation(Libs.springBootStarter("thymeleaf"))

    // https://mvnrepository.com/artifact/org.thymeleaf.extras/thymeleaf-extras-springsecurity6
    implementation("org.thymeleaf.extras:thymeleaf-extras-springsecurity6:3.1.2.RELEASE")

    testImplementation(Libs.springSecurity("test"))
    testImplementation(Libs.springBootStarter("test")) {
        exclude(group = "junit", module = "junit")
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
        exclude(module = "mockito-core")
    }
}

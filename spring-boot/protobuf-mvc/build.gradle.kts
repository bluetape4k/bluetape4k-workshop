import com.google.protobuf.gradle.id

plugins {
    `java-library`
    idea
    alias(libs.plugins.protobuf.plugin)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.graalvm.native)
}

idea {
    module {
        sourceDirs.plus(file("${layout.buildDirectory.asFile.get()}/generated/source/proto/main"))
        testSources.plus(file("${layout.buildDirectory.asFile.get()}/generated/source/proto/test"))
    }
}

// 참고: https://github.com/grpc/grpc-kotlin/blob/master/compiler/README.md
protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:${libs.versions.protobuf.asProvider().get()}"
    }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                id("kotlin")
            }
            // DynamicMessage 사용을 위해
            task.generateDescriptorSet = true
            task.descriptorSetOptions.includeSourceInfo = true
            task.descriptorSetOptions.includeImports = true
        }
    }
}


springBoot {
    mainClass.set("io.bluetape4k.workshop.protobuf.ProtobufApplicationKt")
    buildInfo()
}

dependencies {

    testImplementation(project(":shared"))

    // Protobuf
    implementation(libs.bluetape4k.grpc)
    implementation(libs.protobuf.java.lib)
    implementation(libs.protobuf.java.util)
    implementation(libs.protobuf.kotlin)

    implementation(libs.spring.boot.starter.webmvc.lib)
    testImplementation(libs.spring.boot.starter.webmvc.test)
    testImplementation(libs.spring.boot.starter.test) {
        exclude(group = "junit", module = "junit")
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
        exclude(module = "mockito-core")
    }

    // WebClient
    testImplementation(libs.spring.boot.starter.webflux.lib)

    testImplementation(libs.bluetape4k.coroutines)
    testImplementation(libs.kotlinx.coroutines.reactor)
    testImplementation(libs.kotlinx.coroutines.test.lib)
    testImplementation(libs.reactor.test)
}

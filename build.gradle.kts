import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.report.ReportMergeTask
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    base
    // jacoco
    alias(libs.plugins.kotlin.jvm)

    // see: https://kotlinlang.org/docs/reference/compiler-plugins.html
    alias(libs.plugins.kotlin.spring) apply false
    alias(libs.plugins.kotlin.allopen) apply false
    alias(libs.plugins.kotlin.noarg) apply false
    alias(libs.plugins.kotlin.jpa) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlinx.atomicfu)
    alias(libs.plugins.kotlin.kapt) apply false

    alias(libs.plugins.detekt)

    alias(libs.plugins.dependency.management)
    alias(libs.plugins.spring.boot) apply false

    alias(libs.plugins.dokka)
    alias(libs.plugins.test.logger)
    alias(libs.plugins.shadow) apply false

    alias(libs.plugins.graalvm.native) apply false

    // for JMolecules
    id("net.bytebuddy.byte-buddy-gradle-plugin") version "1.15.10" apply false
}

val rootLibs = libs

allprojects {
    repositories {
        mavenCentral()
        maven {
            name = "central-snapshots"
            url = uri("https://central.sonatype.com/repository/maven-snapshots/")
        }
    }

    // bluetape4k snapshot 버전 사용 시만 사용하세요.
    configurations.all {
        resolutionStrategy.cacheChangingModulesFor(0, TimeUnit.SECONDS)
    }
}

subprojects {
    apply {
        plugin<JavaLibraryPlugin>()

        // Kotlin 1.9.20 부터는 pluginId 를 지정해줘야 합니다.
        plugin("org.jetbrains.kotlin.jvm")

        // Atomicfu
        plugin("org.jetbrains.kotlinx.atomicfu")

        // plugin("jacoco")

        plugin("io.spring.dependency-management")

        plugin("org.jetbrains.dokka")
        plugin("com.adarshr.test-logger")
    }

    java {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }

    kotlin {
        jvmToolchain(21)
        compilerOptions {
            languageVersion.set(KotlinVersion.KOTLIN_2_3)
            apiVersion.set(KotlinVersion.KOTLIN_2_3)
            freeCompilerArgs = listOf(
                "-Xjsr305=strict",
                "-jvm-default=enable",
                // "-Xinline-classes",          // Kotlin 2.2 부터는 불 필요
                "-Xstring-concat=indy",         // since Kotlin 1.4.20 for JVM 9+
                "-Xcontext-parameters",           // since Kotlin 1.6
                "-Xannotation-default-target=param-property",
            )
            val experimentalAnnotations = listOf(
                "kotlin.RequiresOptIn",
                "kotlin.ExperimentalStdlibApi",
                "kotlin.contracts.ExperimentalContracts",
                "kotlin.experimental.ExperimentalTypeInference",
                "kotlinx.coroutines.ExperimentalCoroutinesApi",
                "kotlinx.coroutines.InternalCoroutinesApi",
                "kotlinx.coroutines.FlowPreview",
                "kotlinx.coroutines.DelicateCoroutinesApi",
            )
            freeCompilerArgs.addAll(experimentalAnnotations.map { "-opt-in=$it" })
        }
    }

    atomicfu {
        transformJvm = true
        jvmVariant = "VH"     //  FU, VH, BOTH
    }

    tasks {
        compileJava {
            options.isIncremental = true
        }

        compileKotlin {
            compilerOptions {
                incremental = true
            }
        }

        // 멀티 모듈들을 테스트 시에만 동시에 실행되지 않게 하기 위해 Mutex 를 활용합니다.
        abstract class TestMutexService: BuildService<BuildServiceParameters.None>

        val testMutex = gradle.sharedServices.registerIfAbsent(
            "test-mutex",
            TestMutexService::class
        ) {
            maxParallelUsages.set(1)
        }

        test {
            // 멀티 모듈들을 테스트 시에만 동시에 실행되지 않게 하기 위해 Mutex 를 활용합니다.
            usesService(testMutex)

            useJUnitPlatform()

            // 테스트 시 아래와 같은 예외 메시지를 제거하기 위해서
            // OpenJDK 64-Bit Server VM warning: Sharing is only supported for boot loader classes because bootstrap classpath has been appended
            jvmArgs(
                "-Xshare:off",
                "-Xms2G",
                "-Xmx4G",
                "-XX:+UseZGC",
                "-XX:+UnlockExperimentalVMOptions",
                "-XX:+EnableDynamicAgentLoading",
                "--enable-preview",
                "-Didea.io.use.nio2=true"
            )

            if (project.name.contains("quarkus")) {
                // [Quarkus Logging](https://quarkus.io/guides/logging)
                systemProperty("java.util.logging.manager", "org.jboss.logmanager.LogManager")
            }

            testLogging {
                showExceptions = true
                showCauses = true
                showStackTraces = true

                events("failed")
            }
        }

        testlogger {
            theme = com.adarshr.gradle.testlogger.theme.ThemeType.MOCHA_PARALLEL
            showFullStackTraces = true
        }

        val reportMerge by registering(ReportMergeTask::class) {
            val file = rootProject.layout.buildDirectory.asFile.get().resolve("reports/detekt/exposed.xml")
            output.set(file)
            // output.set(rootProject.buildDir.resolve("reports/detekt/exposed.xml"))
        }
        withType<Detekt>().configureEach detekt@{
            enabled = this@subprojects.name !== "exposed-tests"
            finalizedBy(reportMerge)
            reportMerge.configure {
                input.from(this@detekt.xmlReportFile)
            }
        }

        // https://kotlin.github.io/dokka/1.6.0/user_guide/gradle/usage/
        dokka {
            configureEach {
//                val javadocDir = layout.buildDirectory.asFile.get().resolve("javadoc")
//                outputs.dir(javadocDir)

                dokkaSourceSets {
                    configureEach {
                        includes.from("README.md")
                    }
                }
                dokkaPublications.html {
                    outputDirectory.set(project.file("docs/api"))
                }
            }
        }

        clean {
            doLast {
                delete("./.project")
                delete("./out")
                delete("./bin")
            }
        }
    }

    dependencyManagement {
        // HINT: Gradle 빌드 시, detachedConfiguration 이 많이 발생하는데, setApplyMavenExclusions(false) 를 추가하면 속도가 개선됩니다.
        // https://discuss.gradle.org/t/what-is-detachedconfiguration-i-have-a-lots-of-them-for-each-subproject-and-resolving-them-takes-95-of-build-time/31595/6
        setApplyMavenExclusions(false)

        imports {
            mavenBom(rootLibs.bluetape4k.bom.get().toString())
            mavenBom(rootLibs.spring.integration.bom.get().toString())
            mavenBom(rootLibs.spring.cloud.dependencies.get().toString())
            mavenBom(rootLibs.spring.boot4.dependencies.get().toString())
            mavenBom(rootLibs.spring.modulith.bom.get().toString())

            mavenBom(rootLibs.feign.bom.get().toString())
            mavenBom(rootLibs.micrometer.bom.get().toString())
            mavenBom(rootLibs.micrometer.tracing.bom.get().toString())
            mavenBom(rootLibs.opentelemetry.bom.get().toString())
            mavenBom(rootLibs.opentelemetry.alpha.bom.get().toString())
            mavenBom(rootLibs.opentelemetry.instrumentation.bom.alpha.get().toString())
            mavenBom(rootLibs.log4j.logging.bom.get().toString())
            mavenBom(rootLibs.testcontainers.bom.get().toString())
            mavenBom(rootLibs.junit.bom.get().toString())
            mavenBom(rootLibs.aws2.bom.get().toString())
            mavenBom(rootLibs.okhttp3.bom.get().toString())
            mavenBom(rootLibs.grpc.bom.get().toString())
            mavenBom(rootLibs.protobuf.bom.get().toString())
            mavenBom(rootLibs.fabric8.kubernetes.client.bom.get().toString())
            mavenBom(rootLibs.resilience4j.bom.get().toString())
            mavenBom(rootLibs.netty.bom.get().toString())
            mavenBom(rootLibs.jackson.bom.get().toString())
            mavenBom(rootLibs.jackson3.bom.get().toString())

            mavenBom(rootLibs.kotlinx.coroutines.bom.get().toString())
            mavenBom(rootLibs.kotlin.bom.get().toString())
        }
        dependencies {
            // spring-boot BOM 의 logback 버전을 catalog 정의(1.5.32)로 override
            dependency(rootLibs.logback.lib.get().toString())
            dependency(rootLibs.logback.core.get().toString())

            val coroutinesVersion = rootLibs.versions.kotlinx.coroutines.get()
            dependency("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
            dependency("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:$coroutinesVersion")
            dependency("org.jetbrains.kotlinx:kotlinx-coroutines-debug:$coroutinesVersion")
            dependency("org.jetbrains.kotlinx:kotlinx-coroutines-reactive:$coroutinesVersion")
            dependency("org.jetbrains.kotlinx:kotlinx-coroutines-reactor:$coroutinesVersion")
            dependency("org.jetbrains.kotlinx:kotlinx-coroutines-slf4j:$coroutinesVersion")
            dependency("org.jetbrains.kotlinx:kotlinx-coroutines-test:$coroutinesVersion")
            dependency("org.jetbrains.kotlinx:kotlinx-coroutines-test-jvm:$coroutinesVersion")
        }
    }

    dependencies {
        val api by configurations
        val testApi by configurations
        val implementation by configurations
        val testImplementation by configurations

        val compileOnly by configurations
        val testCompileOnly by configurations
        val testRuntimeOnly by configurations

        implementation(platform(rootLibs.bluetape4k.dependencies))
        compileOnly(platform(rootLibs.spring.boot4.dependencies))
        compileOnly(platform(rootLibs.jackson.bom))
        compileOnly(platform(rootLibs.kotlinx.coroutines.bom))

        implementation(rootLibs.kotlin.stdlib)
        implementation(rootLibs.kotlin.reflect)
        testImplementation(rootLibs.kotlin.test.lib)
        testImplementation(rootLibs.kotlin.test.junit5)

        implementation(rootLibs.kotlinx.coroutines.core.lib)
        implementation(rootLibs.kotlinx.atomicfu)

        // 개발 시에는 logback 이 검증하기에 더 좋고, Production에서 비동기 로깅은 log4j2 가 성능이 좋다고 합니다.
        implementation(rootLibs.slf4j.api)
        implementation(rootLibs.bluetape4k.logging)
        implementation(rootLibs.logback.lib)
        testImplementation(rootLibs.jcl.over.slf4j)
        testImplementation(rootLibs.jul.to.slf4j)
        testImplementation(rootLibs.slf4j.log4j.over)

        // JUnit 5
        testImplementation(rootLibs.bluetape4k.junit5)
        testImplementation(rootLibs.junit.jupiter.lib)
        testRuntimeOnly(rootLibs.junit.platform.engine)

        testImplementation(rootLibs.mockk)
        testImplementation(rootLibs.awaitility.kotlin)

        // Property based test
        testImplementation(rootLibs.datafaker)
        testImplementation(rootLibs.random.beans)
    }
}

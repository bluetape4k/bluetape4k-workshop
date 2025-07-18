import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.report.ReportMergeTask
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    base
    // jacoco
    kotlin("jvm") version Versions.kotlin

    // see: https://kotlinlang.org/docs/reference/compiler-plugins.html
    kotlin("plugin.spring") version Versions.kotlin apply false
    kotlin("plugin.allopen") version Versions.kotlin apply false
    kotlin("plugin.noarg") version Versions.kotlin apply false
    kotlin("plugin.jpa") version Versions.kotlin apply false
    kotlin("plugin.serialization") version Versions.kotlin apply false
    kotlin("plugin.atomicfu") version Versions.kotlin
    kotlin("kapt") version Versions.kotlin apply false

    id(Plugins.detekt) version Plugins.Versions.detekt

    id(Plugins.dependency_management) version Plugins.Versions.dependency_management
    id(Plugins.spring_boot) version Plugins.Versions.spring_boot apply false
    id(Plugins.quarkus) version Plugins.Versions.quarkus apply false

    id(Plugins.dokka) version Plugins.Versions.dokka
    id(Plugins.testLogger) version Plugins.Versions.testLogger
    id(Plugins.shadow) version Plugins.Versions.shadow apply false

    id(Plugins.graalvm_native) version Plugins.Versions.graalvm_native apply false

    // for JMolecules
    id("net.bytebuddy.byte-buddy-gradle-plugin") version "1.15.10" apply false
}

// NOTE: Github 에 등록된 Package 를 다운받기 위해서 사용합니다.
// NOTE: ~/.gradle/gradle.properties gpr.user,gpr.key 를 정의하던가
// NOTE: ~/.zshrc 에 GITHUB_USERNAME, GITHUB_TOKEN 을 정의합니다.
fun getEnvOrProjectProperty(propertyKey: String, envKey: String): String {
    return project.findProperty(propertyKey) as? String ?: System.getenv(envKey)
}

val bluetape4kGprKey: String = getEnvOrProjectProperty("bluetape4k.gpr.key", "BLUETAPE4K_GITHUB_TOKEN")

allprojects {
    repositories {
        mavenCentral()
        google()
        maven {
            name = "bluetape4k"
            url = uri("https://maven.pkg.github.com/bluetape4k/bluetape4k-projects")

            credentials {
                username = "debop"
                password = bluetape4kGprKey
            }
        }
    }

    configurations.all {
        resolutionStrategy.cacheChangingModulesFor(5, TimeUnit.DAYS)
    }
}

subprojects {
    apply {
        plugin<JavaLibraryPlugin>()

        // Kotlin 1.9.20 부터는 pluginId 를 지정해줘야 합니다.
        plugin("org.jetbrains.kotlin.jvm")

        // plugin("jacoco")
        plugin("maven-publish")

        plugin(Plugins.dependency_management)

        plugin(Plugins.dokka)
        plugin(Plugins.testLogger)
    }

    java {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }

    kotlin {
        jvmToolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
        compilerOptions {
            languageVersion.set(KotlinVersion.KOTLIN_2_1)
            apiVersion.set(KotlinVersion.KOTLIN_2_1)
            freeCompilerArgs = listOf(
                "-Xjsr305=strict",
                "-Xjvm-default=all",
                "-Xinline-classes",
                "-Xstring-concat=indy",         // since Kotlin 1.4.20 for JVM 9+
                "-Xenable-builder-inference",   // since Kotlin 1.6
                "-Xcontext-receivers"           // since Kotlin 1.6
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

    tasks {
        compileJava {
            options.isIncremental = true
        }

        test {
            useJUnitPlatform()

            // 테스트 시 아래와 같은 예외 메시지를 제거하기 위해서 
            // OpenJDK 64-Bit Server VM warning: Sharing is only supported for boot loader classes because bootstrap classpath has been appended
            jvmArgs(
                "-XX:+UseZGC",
                "-Xshare:off",
                "-Xms4G",
                "-Xmx8G",
                "--enable-preview",
                "-XX:+EnableDynamicAgentLoading",
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
            mavenBom(Libs.bluetape4k_bom)
            mavenBom(Libs.spring_integration_bom)
            mavenBom(Libs.spring_cloud_dependencies)
            mavenBom(Libs.spring_boot_dependencies)
            mavenBom(Libs.spring_modulith_bom)

            mavenBom(Libs.feign_bom)
            mavenBom(Libs.micrometer_bom)
            mavenBom(Libs.micrometer_tracing_bom)
            mavenBom(Libs.opentelemetry_bom)
            mavenBom(Libs.opentelemetry_alpha_bom)
            mavenBom(Libs.opentelemetry_instrumentation_bom_alpha)
            mavenBom(Libs.log4j_bom)
            mavenBom(Libs.testcontainers_bom)
            mavenBom(Libs.junit_bom)
            mavenBom(Libs.aws_bom)
            mavenBom(Libs.aws2_bom)
            mavenBom(Libs.okhttp3_bom)
            mavenBom(Libs.grpc_bom)
            mavenBom(Libs.protobuf_bom)
            mavenBom(Libs.metrics_bom)
            mavenBom(Libs.fabric8_kubernetes_client_bom)
            mavenBom(Libs.resilience4j_bom)
            mavenBom(Libs.netty_bom)
            mavenBom(Libs.jackson_bom)

            mavenBom(Libs.kotlinx_coroutines_bom)
            mavenBom(Libs.kotlin_bom)
        }
        dependencies {
            dependency(Libs.jetbrains_annotations)

            // Kotlinx Coroutines (mavenBom 이 적용이 안되어서 추가로 명시했습니다)
            dependency(Libs.kotlinx_coroutines_bom)
            dependency(Libs.kotlinx_coroutines_core)
            dependency(Libs.kotlinx_coroutines_core_jvm)
            dependency(Libs.kotlinx_coroutines_jdk8)
            dependency(Libs.kotlinx_coroutines_jdk9)
            dependency(Libs.kotlinx_coroutines_jdk8)
            dependency(Libs.kotlinx_coroutines_reactive)
            dependency(Libs.kotlinx_coroutines_reactor)
            dependency(Libs.kotlinx_coroutines_rx2)
            dependency(Libs.kotlinx_coroutines_rx3)
            dependency(Libs.kotlinx_coroutines_slf4j)
            dependency(Libs.kotlinx_coroutines_debug)
            dependency(Libs.kotlinx_coroutines_test)
            dependency(Libs.kotlinx_coroutines_test_jvm)

            // Apache Commons
            dependency(Libs.commons_beanutils)
            dependency(Libs.commons_collections4)
            dependency(Libs.commons_compress)
            dependency(Libs.commons_codec)
            dependency(Libs.commons_csv)
            dependency(Libs.commons_lang3)
            dependency(Libs.commons_logging)
            dependency(Libs.commons_math3)
            dependency(Libs.commons_pool2)
            dependency(Libs.commons_text)
            dependency(Libs.commons_exec)
            dependency(Libs.commons_io)

            dependency(Libs.slf4j_api)
            dependency(Libs.jcl_over_slf4j)
            dependency(Libs.jul_to_slf4j)
            dependency(Libs.log4j_over_slf4j)
            dependency(Libs.logback)
            dependency(Libs.logback_core)

            // jakarta
            dependency(Libs.jakarta_activation_api)
            dependency(Libs.jakarta_annotation_api)
            dependency(Libs.jakarta_el_api)
            dependency(Libs.jakarta_inject_api)
            dependency(Libs.jakarta_interceptor_api)
            dependency(Libs.jakarta_jms_api)
            dependency(Libs.jakarta_json_api)
            dependency(Libs.jakarta_json)
            dependency(Libs.jakarta_persistence_api)
            dependency(Libs.jakarta_servlet_api)
            dependency(Libs.jakarta_transaction_api)
            dependency(Libs.jakarta_validation_api)
            dependency(Libs.jakarta_ws_rs_api)
            dependency(Libs.jakarta_xml_bind)

            // Compressor
            dependency(Libs.snappy_java)
            dependency(Libs.lz4_java)
            dependency(Libs.zstd_jni)

            // Java Money
            dependency(Libs.javax_money_api)
            dependency(Libs.javamoney_moneta)

            dependency(Libs.findbugs)
            dependency(Libs.guava)

            dependency(Libs.kryo)
            dependency(Libs.fury_kotlin)

            // Jackson (이상하게 mavenBom 에 적용이 안되어서 강제로 추가하였다)
            dependency(Libs.jackson_bom)
            dependency(Libs.jackson_annotations)
            dependency(Libs.jackson_core)
            dependency(Libs.jackson_databind)
            dependency(Libs.jackson_datatype_jdk8)
            dependency(Libs.jackson_datatype_jsr310)
            dependency(Libs.jackson_datatype_jsr353)
            dependency(Libs.jackson_module_kotlin)
            dependency(Libs.jackson_module_paranamer)
            dependency(Libs.jackson_module_parameter_names)
            dependency(Libs.jackson_module_blackbird)
            dependency(Libs.jackson_module_jsonSchema)

            // Retrofit
            dependency(Libs.retrofit2)
            dependency(Libs.retrofit2_adapter_java8)
            dependency(Libs.retrofit2_adapter_reactor)
            dependency(Libs.retrofit2_adapter_rxjava2)
            dependency(Libs.retrofit2_converter_jackson)
            dependency(Libs.retrofit2_converter_moshi)
            dependency(Libs.retrofit2_converter_protobuf)
            dependency(Libs.retrofit2_converter_scalars)
            dependency(Libs.retrofit2_mock)

            // Http
            dependency(Libs.async_http_client)
            dependency(Libs.async_http_client_extras_retrofit2)
            dependency(Libs.async_http_client_extras_rxjava2)

            dependency(Libs.httpclient5)
            dependency(Libs.httpcore5)
            dependency(Libs.httpcore5_h2)
            dependency(Libs.httpcore5_reactive)

            dependency(Libs.grpc_kotlin_stub)

            dependency(Libs.mongo_bson)
            dependency(Libs.mongodb_driver_core)
            dependency(Libs.mongodb_driver_reactivestreams)

            // Kafka
            dependency(Libs.kafka_clients)
            dependency(Libs.kafka_generator)
            dependency(Libs.kafka_metadata)
            dependency(Libs.kafka_raft)
            dependency(Libs.kafka_server_common)
            dependency(Libs.kafka_storage)
            dependency(Libs.kafka_storage_api)
            dependency(Libs.kafka_streams)
            dependency(Libs.kafka_streams_test_utils)
            dependency(Libs.kafka_2_13)

            // Hibernate
            dependency(Libs.hibernate_core)
            dependency(Libs.hibernate_jcache)
            dependency(Libs.javassist)

            dependency(Libs.antlr4_runtime)  // https://github.com/spring-projects/spring-data-jpa/issues/3262
            dependency(Libs.antlr4_tool)

            dependency(Libs.querydsl_apt)
            dependency(Libs.querydsl_core)
            dependency(Libs.querydsl_jpa)

            // Validators
            dependency(Libs.jakarta_validation_api)
            dependency(Libs.jakarta_el_api)
            dependency(Libs.hibernate_validator)
            dependency(Libs.hibernate_validator_annotation_processor)

            dependency(Libs.hikaricp)
            dependency(Libs.mysql_connector_j)
            dependency(Libs.mariadb_java_client)

            dependency(Libs.caffeine)
            dependency(Libs.caffeine_jcache)

            dependency(Libs.objenesis)
            dependency(Libs.ow2_asm)

            dependency(Libs.reflectasm)

            dependency(Libs.junit_bom)
            dependency(Libs.junit_jupiter)
            dependency(Libs.junit_jupiter_api)
            dependency(Libs.junit_jupiter_engine)
            dependency(Libs.junit_jupiter_migrationsupport)
            dependency(Libs.junit_jupiter_params)
            dependency(Libs.junit_platform_commons)
            dependency(Libs.junit_platform_engine)
            dependency(Libs.junit_platform_launcher)
            dependency(Libs.junit_platform_runner)

            dependency(Libs.kluent)
            dependency(Libs.assertj_core)

            dependency(Libs.mockk)
            dependency(Libs.datafaker)
            dependency(Libs.random_beans)

            dependency(Libs.jsonpath)
            dependency(Libs.jsonassert)

            dependency(Libs.bouncycastle_bcpkix)
            dependency(Libs.bouncycastle_bcprov)

            // Prometheus
            dependency(Libs.prometheus_simpleclient)
            dependency(Libs.prometheus_simpleclient_common)
            dependency(Libs.prometheus_simpleclient_httpserver)
            dependency(Libs.prometheus_simpleclient_pushgateway)
            dependency(Libs.prometheus_simpleclient_spring_boot)
            dependency(Libs.prometheus_simpleclient_tracer_common)
            dependency(Libs.prometheus_simpleclient_tracer_otel)
            dependency(Libs.prometheus_simpleclient_tracer_otel_agent)

            // OW2 ASM
            dependency(Libs.ow2_asm)
            dependency(Libs.ow2_asm_commons)
            dependency(Libs.ow2_asm_util)
            dependency(Libs.ow2_asm_tree)

            dependency(Libs.snakeyaml)
            dependency(Libs.jna)
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

        compileOnly(platform(Libs.bluetape4k_bom))
        compileOnly(platform(Libs.spring_boot_dependencies))
        compileOnly(platform(Libs.jackson_bom))
        compileOnly(platform(Libs.kotlinx_coroutines_bom))

        api(Libs.kotlin_stdlib)
        api(Libs.kotlin_stdlib_jdk8)
        api(Libs.kotlin_reflect)
        api(Libs.kotlinx_atomicfu)
        testImplementation(Libs.kotlin_test)
        testImplementation(Libs.kotlin_test_junit5)

        compileOnly(Libs.kotlinx_coroutines_core)

        // 개발 시에는 logback 이 검증하기에 더 좋고, Production에서 비동기 로깅은 log4j2 가 성능이 좋다고 합니다.
        api(Libs.slf4j_api)
        api(Libs.bluetape4k_logging)
        implementation(Libs.logback)
        testImplementation(Libs.jcl_over_slf4j)
        testImplementation(Libs.jul_to_slf4j)
        testImplementation(Libs.log4j_over_slf4j)

        // JUnit 5
        testImplementation(Libs.bluetape4k_junit5)
        testImplementation(Libs.junit_jupiter)
        testRuntimeOnly(Libs.junit_platform_engine)
        testImplementation(Libs.junit_jupiter_migrationsupport)

        testImplementation(Libs.kluent)
        testImplementation(Libs.mockk)
        testImplementation(Libs.awaitility_kotlin)

        // Property baesd test
        testImplementation(Libs.datafaker)
        testImplementation(Libs.random_beans)
    }
}

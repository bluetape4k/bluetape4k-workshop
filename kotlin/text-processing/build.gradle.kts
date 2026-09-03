import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.DefaultTask
import org.gradle.api.Task
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.specs.Spec
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.testing.TestDescriptor
import org.gradle.api.tasks.testing.TestListener
import org.gradle.api.tasks.testing.TestResult
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import java.io.Serializable
import java.util.zip.ZipFile
import javax.net.ssl.HttpsURLConnection

private object SudachiDictionarySupport {
    const val VERSION = "20260428"
    const val ARCHIVE_NAME = "sudachi-dictionary-20260428-core.zip"
    const val ENTRY = "sudachi-dictionary-20260428/system_core.dic"
    const val ARCHIVE_SHA256 = "40c8ffc095283f07aa06cae922e7b8147bf2919ec8830567b0b3f7a7efa3239f"
    const val ARCHIVE_SIZE = 72_238_136L
    const val DICTIONARY_SHA256 = "6c1d5adc8a2389875713056e7b39bbcd0073d6122ffd509866e1d3a196f8608e"
    const val DICTIONARY_SIZE = 217_374_303L
    const val URL = "https://github.com/WorksApplications/SudachiDict/releases/download/v20260428/sudachi-dictionary-20260428-core.zip"
    val allowedHosts = setOf(
        "github.com",
        "objects.githubusercontent.com",
        "release-assets.githubusercontent.com",
    )

    fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path, NOFOLLOW_LINKS).buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    }

    fun isSafeRegularPath(path: Path): Boolean =
        !Files.isSymbolicLink(path) && Files.isRegularFile(path, NOFOLLOW_LINKS)

    fun isSafeDirectoryPath(path: Path): Boolean =
        !Files.isSymbolicLink(path) && Files.isDirectory(path, NOFOLLOW_LINKS) && isSafeDirectoryTree(path)

    fun isSafeDirectoryTree(path: Path): Boolean {
        var current: Path? = path
        while (current != null) {
            if (Files.exists(current, NOFOLLOW_LINKS) &&
                (Files.isSymbolicLink(current) || !Files.isDirectory(current, NOFOLLOW_LINKS))
            ) {
                return false
            }
            if (Files.isSymbolicLink(current)) return false
            current = current.parent
        }
        return true
    }

    fun requireSafeDirectoryTree(path: Path?) {
        require(path != null && isSafeDirectoryTree(path)) {
            "Sudachi dictionary directory is not a safe non-symlink directory."
        }
    }

    fun rejectSymlink(path: Path) {
        require(!Files.isSymbolicLink(path)) {
            "Sudachi dictionary output must not be a symbolic link."
        }
    }

    fun copyBounded(input: InputStream, output: OutputStream, expectedSize: Long) {
        var copied = 0L
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val remaining = expectedSize - copied
            val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining + 1L).toInt())
            if (count < 0) break
            if (count == 0) continue
            copied += count
            require(copied <= expectedSize) { "Sudachi dictionary content exceeds the pinned size." }
            output.write(buffer, 0, count)
        }
        require(copied == expectedSize) { "Sudachi dictionary content has an unexpected size." }
    }

    fun openPinnedHttpsConnection(start: URI): HttpsURLConnection {
        var next = start
        var redirects = 0
        var current: HttpsURLConnection? = null
        try {
            while (true) {
                require(next.scheme.equals("https", ignoreCase = true) &&
                    next.host.lowercase() in allowedHosts
                ) {
                    "Sudachi dictionary download URL is outside the HTTPS allowlist."
                }
                val connection = next.toURL().openConnection() as? HttpsURLConnection
                    ?: error("Sudachi dictionary download did not use HTTPS.")
                current = connection
                connection.connectTimeout = 30_000
                connection.readTimeout = 120_000
                connection.instanceFollowRedirects = false
                when (val responseCode = connection.responseCode) {
                    in 200..299 -> {
                        current = null
                        return connection
                    }

                    HttpURLConnection.HTTP_MOVED_PERM,
                    HttpURLConnection.HTTP_MOVED_TEMP,
                    HttpURLConnection.HTTP_SEE_OTHER,
                    307,
                    308,
                    -> {
                        val location = connection.getHeaderField("Location")?.trim().orEmpty()
                        connection.disconnect()
                        current = null
                        require(location.isNotEmpty()) {
                            "Sudachi dictionary redirect did not provide a location."
                        }
                        redirects++
                        require(redirects <= 5) { "Sudachi dictionary redirect limit exceeded." }
                        next = next.resolve(location)
                    }

                    else -> error("Sudachi dictionary download returned HTTP $responseCode.")
                }
            }
        } catch (error: Throwable) {
            current?.disconnect()
            throw error
        }
    }

    fun moveAtomically(source: Path, target: Path) {
        try {
            Files.move(source, target, ATOMIC_MOVE, REPLACE_EXISTING)
        } catch (error: AtomicMoveNotSupportedException) {
            throw IllegalStateException("Sudachi dictionary requires atomic file moves.", error)
        }
    }
}

private class SudachiDictionaryUpToDateSpec : Spec<Task>, Serializable {
    override fun isSatisfiedBy(element: Task): Boolean {
        val task = element as? PrepareSudachiDictionaryTask ?: return false
        return runCatching {
            val archive = task.archiveFile.get().asFile.toPath()
            val dictionary = task.dictionaryFile.get().asFile.toPath()
            val archivePart = archive.resolveSibling("${archive.fileName}.part")
            val dictionaryPart = dictionary.resolveSibling("${dictionary.fileName}.part")
            SudachiDictionarySupport.isSafeDirectoryPath(archive.parent) &&
                SudachiDictionarySupport.isSafeRegularPath(archive) &&
                Files.size(archive) == SudachiDictionarySupport.ARCHIVE_SIZE &&
                SudachiDictionarySupport.sha256(archive) == SudachiDictionarySupport.ARCHIVE_SHA256 &&
                SudachiDictionarySupport.isSafeRegularPath(dictionary) &&
                Files.size(dictionary) == SudachiDictionarySupport.DICTIONARY_SIZE &&
                SudachiDictionarySupport.sha256(dictionary) == SudachiDictionarySupport.DICTIONARY_SHA256 &&
                !Files.isSymbolicLink(archivePart) &&
                !Files.exists(archivePart, NOFOLLOW_LINKS) &&
                !Files.isSymbolicLink(dictionaryPart) &&
                !Files.exists(dictionaryPart, NOFOLLOW_LINKS)
        }.getOrDefault(false)
    }
}

abstract class PrepareSudachiDictionaryTask : DefaultTask() {
    @get:Input
    abstract val dictionaryUrl: Property<String>

    @get:Input
    abstract val archiveSha256: Property<String>

    @get:Input
    abstract val archiveSize: Property<Long>

    @get:Input
    abstract val dictionarySha256: Property<String>

    @get:Input
    abstract val dictionarySize: Property<Long>

    @get:OutputFile
    abstract val archiveFile: RegularFileProperty

    @get:OutputFile
    abstract val dictionaryFile: RegularFileProperty

    init {
        outputs.upToDateWhen(SudachiDictionaryUpToDateSpec())
    }

    @TaskAction
    fun prepare() {
        val archive = archiveFile.get().asFile.toPath()
        val dictionary = dictionaryFile.get().asFile.toPath()
        val directory = archive.parent
        require(directory == dictionary.parent) {
            "Sudachi dictionary outputs must share one directory."
        }
        val archivePart = archive.resolveSibling("${archive.fileName}.part")
        val dictionaryPart = dictionary.resolveSibling("${dictionary.fileName}.part")

        SudachiDictionarySupport.requireSafeDirectoryTree(directory)
        SudachiDictionarySupport.rejectSymlink(archive)
        SudachiDictionarySupport.rejectSymlink(archivePart)
        SudachiDictionarySupport.rejectSymlink(dictionary)
        SudachiDictionarySupport.rejectSymlink(dictionaryPart)
        Files.createDirectories(directory)
        SudachiDictionarySupport.requireSafeDirectoryTree(directory)

        try {
            if (!SudachiDictionarySupport.isSafeRegularPath(archive) ||
                Files.size(archive) != SudachiDictionarySupport.ARCHIVE_SIZE ||
                SudachiDictionarySupport.sha256(archive) != SudachiDictionarySupport.ARCHIVE_SHA256
            ) {
                Files.deleteIfExists(archivePart)
                val connection = SudachiDictionarySupport.openPinnedHttpsConnection(URI(dictionaryUrl.get()))
                try {
                    val contentLength = connection.contentLengthLong
                    require(contentLength < 0 || contentLength == SudachiDictionarySupport.ARCHIVE_SIZE) {
                        "Sudachi dictionary archive content length is not pinned."
                    }
                    connection.inputStream.buffered().use { input ->
                        Files.newOutputStream(archivePart, CREATE_NEW, WRITE).buffered().use { output ->
                            SudachiDictionarySupport.copyBounded(
                                input,
                                output,
                                SudachiDictionarySupport.ARCHIVE_SIZE,
                            )
                        }
                    }
                } finally {
                    connection.disconnect()
                }
                require(SudachiDictionarySupport.isSafeRegularPath(archivePart))
                require(Files.size(archivePart) == SudachiDictionarySupport.ARCHIVE_SIZE)
                require(SudachiDictionarySupport.sha256(archivePart) == SudachiDictionarySupport.ARCHIVE_SHA256) {
                    "Sudachi dictionary archive SHA-256 does not match the pin."
                }
                SudachiDictionarySupport.moveAtomically(archivePart, archive)
            }

            Files.deleteIfExists(dictionaryPart)
            ZipFile(archive.toFile()).use { zip ->
                require(zip.getEntry("sudachi-dictionary-20260428/LEGAL")?.isDirectory == false) {
                    "Sudachi dictionary archive is missing LEGAL."
                }
                require(zip.getEntry("sudachi-dictionary-20260428/LICENSE-2.0.txt")?.isDirectory == false) {
                    "Sudachi dictionary archive is missing LICENSE-2.0.txt."
                }
                val entry = requireNotNull(zip.getEntry(SudachiDictionarySupport.ENTRY)) {
                    "Sudachi dictionary archive is missing the pinned dictionary entry."
                }
                require(!entry.isDirectory) { "Sudachi dictionary entry must be a file." }
                require(entry.size < 0 || entry.size == SudachiDictionarySupport.DICTIONARY_SIZE) {
                    "Sudachi dictionary entry size is not pinned."
                }
                zip.getInputStream(entry).buffered().use { input ->
                    Files.newOutputStream(dictionaryPart, CREATE_NEW, WRITE).buffered().use { output ->
                        SudachiDictionarySupport.copyBounded(
                            input,
                            output,
                            SudachiDictionarySupport.DICTIONARY_SIZE,
                        )
                    }
                }
            }
            require(SudachiDictionarySupport.isSafeRegularPath(dictionaryPart))
            require(Files.size(dictionaryPart) == SudachiDictionarySupport.DICTIONARY_SIZE)
            require(SudachiDictionarySupport.sha256(dictionaryPart) == SudachiDictionarySupport.DICTIONARY_SHA256) {
                "Sudachi system dictionary SHA-256 does not match the pin."
            }
            SudachiDictionarySupport.moveAtomically(dictionaryPart, dictionary)
            Files.deleteIfExists(archivePart)
        } catch (error: Throwable) {
            Files.deleteIfExists(archivePart)
            Files.deleteIfExists(dictionaryPart)
            throw error
        }
    }
}

fun Test.failOnZeroTests() {
    val executed = AtomicLong()
    addTestListener(
        object : TestListener {
            override fun beforeSuite(suite: TestDescriptor) = Unit
            override fun afterSuite(suite: TestDescriptor, result: TestResult) = Unit
            override fun beforeTest(testDescriptor: TestDescriptor) = Unit
            override fun afterTest(testDescriptor: TestDescriptor, result: TestResult) {
                executed.incrementAndGet()
            }
        },
    )
    doFirst { executed.set(0L) }
    doLast { check(executed.get() > 0L) { "$name discovered zero tests" } }
}

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
    // External Sudachi JVM tokenizer; its dictionary is prepared only by sudachiTest.
    implementation(libs.sudachi)

    implementation(libs.kotlinx.coroutines.core.lib)
    implementation(libs.bluetape4k.logging)

    testImplementation(libs.bluetape4k.junit5)
    testImplementation(libs.bluetape4k.assertions)
    testImplementation(libs.mockk)
    testImplementation(libs.logback.lib)
}

val sudachiDictionaryDirectory =
    layout.buildDirectory.dir("sudachi-dictionary/v${SudachiDictionarySupport.VERSION}")
val sudachiDictionaryArchive = sudachiDictionaryDirectory.map { it.file(SudachiDictionarySupport.ARCHIVE_NAME) }
val sudachiSystemDictionary = sudachiDictionaryDirectory.map { it.file("system_core.dic") }

private val prepareSudachiDictionary = tasks.register<PrepareSudachiDictionaryTask>("prepareSudachiDictionary") {
    description = "Downloads and verifies the pinned SudachiDict core dictionary into build/."
    group = "verification"
    usesService(gradle.sharedServices.registrations.named("test-mutex").get().service)
    dictionaryUrl.set(SudachiDictionarySupport.URL)
    archiveSha256.set(SudachiDictionarySupport.ARCHIVE_SHA256)
    archiveSize.set(SudachiDictionarySupport.ARCHIVE_SIZE)
    dictionarySha256.set(SudachiDictionarySupport.DICTIONARY_SHA256)
    dictionarySize.set(SudachiDictionarySupport.DICTIONARY_SIZE)
    archiveFile.set(sudachiDictionaryArchive)
    dictionaryFile.set(sudachiSystemDictionary)
}

tasks.test {
    useJUnitPlatform {
        excludeTags("sudachi-integration")
    }
}

tasks.register<Test>("sudachiTest") {
    description = "Runs Sudachi dictionary-backed tokenizer comparisons."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    workingDir(projectDir)
    dependsOn(prepareSudachiDictionary)
    shouldRunAfter(tasks.test)
    usesService(gradle.sharedServices.registrations.named("test-mutex").get().service)
    inputs.file(sudachiSystemDictionary)
        .withPropertyName("sudachiSystemDictionary")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty(
        "bluetape4k.sudachi.system-dictionary",
        sudachiSystemDictionary.get().asFile.absolutePath,
    )
    useJUnitPlatform {
        includeTags("sudachi-integration")
    }
    failOnZeroTests()
    setJvmArgs(tasks.test.get().jvmArgs ?: emptyList())
    systemProperties(tasks.test.get().systemProperties)
    environment(tasks.test.get().environment)
}

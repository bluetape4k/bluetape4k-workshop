package io.bluetape4k.workshop.storage

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Local filesystem-based [StorageService] implementation.
 *
 * ## Behavior / Contract
 * - All objects are stored under [basePath] on the local filesystem.
 * - `upload` writes bytes to `{basePath}/{key}` and returns a `file://` URL.
 * - `download` reads bytes from `{basePath}/{key}`.
 * - `getUrl` returns the `file://` URL for the stored path.
 * - `delete` removes the file; silently ignores missing files.
 * - All blocking I/O is dispatched on [Dispatchers.IO].
 * - Active when Spring profile `local` is set.
 *
 * ```kotlin
 * // With profile "local":
 * val url = storageService.upload("test.txt", "Hello".toByteArray(), "text/plain")
 * // url = "file:///tmp/storage/test.txt"
 * ```
 */
@Service
@Profile("local")
class LocalStorageService(
    @Value("\${storage.local.base-path:/tmp/bluetape4k-workshop-storage}") private val basePath: String,
) : StorageService {

    companion object : KLogging()

    private val root: Path get() = Paths.get(basePath)

    override suspend fun upload(key: String, content: ByteArray, contentType: String): String =
        withContext(Dispatchers.IO) {
            val target = resolveAndCreateParents(key)
            Files.write(target, content)
            log.debug { "Uploaded [$key] to $target (${content.size} bytes, $contentType)" }
            target.toUri().toString()
        }

    override suspend fun download(key: String): ByteArray =
        withContext(Dispatchers.IO) {
            val target = root.resolve(key)
            log.debug { "Downloading [$key] from $target" }
            Files.readAllBytes(target)
        }

    override suspend fun getUrl(key: String): String =
        withContext(Dispatchers.IO) {
            root.resolve(key).toUri().toString()
        }

    override suspend fun delete(key: String) {
        withContext(Dispatchers.IO) {
            val target = root.resolve(key)
            val deleted = Files.deleteIfExists(target)
            log.debug { "Delete [$key]: deleted=$deleted" }
        }
    }

    private fun resolveAndCreateParents(key: String): Path {
        val target = root.resolve(key)
        Files.createDirectories(target.parent ?: root)
        return target
    }
}

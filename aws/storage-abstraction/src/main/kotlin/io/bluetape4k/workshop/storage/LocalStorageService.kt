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
 * 로컬 파일시스템 기반 [StorageService] 구현입니다.
 *
 * ## 동작 / 계약
 * - 모든 객체를 로컬 파일시스템의 [basePath] 아래에 저장합니다.
 * - 파일시스템 접근 전에 객체 키가 상대 슬래시 경로인지 검증합니다.
 * - `upload`는 바이트를 `{basePath}/{key}`에 쓰고 `file://` URL을 반환합니다.
 * - `download`는 `{basePath}/{key}`에서 바이트를 읽습니다.
 * - `getUrl`은 저장된 경로의 `file://` URL을 반환합니다.
 * - `delete`는 파일을 삭제하며, 없는 파일은 조용히 무시합니다.
 * - 모든 블로킹 I/O는 [Dispatchers.IO]에서 실행합니다.
 * - Spring profile `local`이 설정되면 활성화됩니다.
 *
 * ```kotlin
 * // profile "local" 사용 시:
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
            val target = resolveKey(key)
            log.debug { "Downloading [$key] from $target" }
            Files.readAllBytes(target)
        }

    override suspend fun getUrl(key: String): String =
        withContext(Dispatchers.IO) {
            resolveKey(key).toUri().toString()
        }

    override suspend fun delete(key: String) {
        withContext(Dispatchers.IO) {
            val target = resolveKey(key)
            val deleted = Files.deleteIfExists(target)
            log.debug { "Delete [$key]: deleted=$deleted" }
        }
    }

    private fun resolveAndCreateParents(key: String): Path {
        val target = resolveKey(key)
        Files.createDirectories(target.parent ?: root)
        return target
    }

    private fun resolveKey(key: String): Path {
        val rootPath = root.toAbsolutePath().normalize()
        val target = rootPath.resolve(storageObjectKey(key)).normalize()
        require(target.startsWith(rootPath)) { "key must stay under the configured storage root: $key" }
        return target
    }
}

package io.bluetape4k.workshop.storage

/**
 * Storage abstraction interface that supports local filesystem and AWS S3 backends.
 *
 * ## Behavior / Contract
 * - `upload` stores the content and returns a URL to access the stored object.
 * - `download` retrieves the raw bytes stored under the given key.
 * - `getUrl` returns a direct URL (local) or a pre-signed URL (S3 presigned) valid for the backend.
 * - `delete` removes the object; implementations should be idempotent (no error if key is absent).
 * - Implementations are selected via Spring Profile: `local`, `s3`, or `s3-presigned`.
 *
 * ```kotlin
 * val url = storageService.upload("docs/readme.txt", content, "text/plain")
 * val bytes = storageService.download("docs/readme.txt")
 * val link = storageService.getUrl("docs/readme.txt")
 * storageService.delete("docs/readme.txt")
 * ```
 */
interface StorageService {
    /**
     * Uploads [content] under the given [key] and returns a URL to access it.
     *
     * @param key object key (e.g. "folder/file.txt")
     * @param content raw bytes to store
     * @param contentType MIME type (e.g. "text/plain", "image/png")
     * @return URL string pointing to the stored object
     */
    suspend fun upload(key: String, content: ByteArray, contentType: String): String

    /**
     * Downloads the object stored under [key] and returns its raw bytes.
     *
     * @param key object key
     * @return raw bytes of the stored object
     */
    suspend fun download(key: String): ByteArray

    /**
     * Returns a URL to access the object stored under [key].
     * For S3 presigned profile this is a time-limited pre-signed GET URL.
     *
     * @param key object key
     * @return URL string
     */
    suspend fun getUrl(key: String): String

    /**
     * Deletes the object stored under [key].
     * Implementations should be idempotent.
     *
     * @param key object key
     */
    suspend fun delete(key: String)
}

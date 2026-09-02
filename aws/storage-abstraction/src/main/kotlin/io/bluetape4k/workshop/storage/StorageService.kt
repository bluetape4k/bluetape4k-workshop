package io.bluetape4k.workshop.storage

/**
 * 로컬 파일시스템과 AWS S3 백엔드를 지원하는 스토리지 추상화 인터페이스입니다.
 *
 * ## 동작 / 계약
 * - `upload`는 콘텐츠를 저장하고 저장된 객체에 접근할 URL을 반환합니다.
 * - `download`는 지정한 키 아래에 저장된 원시 바이트를 가져옵니다.
 * - `getUrl`은 로컬 파일 URL, 엔드포인트 중립 S3 객체 URI, 또는 pre-signed URL을 반환합니다.
 * - `delete`는 객체를 삭제하며, 구현은 키가 없어도 오류가 나지 않도록 멱등이어야 합니다.
 * - 객체 키는 상대 슬래시 키여야 하며, 공백/절대 경로/백슬래시/경로 순회 키는 조기 실패합니다.
 * - 구현은 Spring Profile `local`, `s3`, `s3-presigned`, `s3-encrypted-aes`,
 *   `s3-encrypted-rsa`로 선택됩니다.
 * - encrypted profile의 [EncryptedS3StorageService]는 `uploadFile`/`downloadFile`
 *   concrete API도 제공한다. file upload는 고유 staging object를 성공 시 canonical
 *   key로 복사한 뒤 staging만 정리하며, key material은 JVM memory에만 있으므로
 *   프로세스 재시작 뒤 기존 객체를 복호화할 수 없습니다.
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
     * 지정한 [key] 아래에 [content]를 업로드하고 접근 URL을 반환합니다.
     *
     * @param key 객체 키입니다(예: "folder/file.txt").
     * @param content 저장할 원시 바이트입니다.
     * @param contentType MIME 유형입니다(예: "text/plain", "image/png").
     * @return 저장된 객체를 가리키는 URL 문자열입니다.
     */
    suspend fun upload(key: String, content: ByteArray, contentType: String): String

    /**
     * [key] 아래에 저장된 객체를 내려받아 원시 바이트를 반환합니다.
     *
     * @param key 객체 키입니다.
     * @return 저장된 객체의 원시 바이트입니다.
     */
    suspend fun download(key: String): ByteArray

    /**
     * [key] 아래에 저장된 객체에 접근할 URL을 반환합니다.
     * S3 presigned profile에서는 시간 제한이 있는 pre-signed GET URL입니다.
     *
     * @param key 객체 키입니다.
     * @return URL 문자열입니다.
     */
    suspend fun getUrl(key: String): String

    /**
     * [key] 아래에 저장된 객체를 삭제합니다.
     * 구현은 멱등이어야 합니다.
     *
     * @param key 객체 키입니다.
     */
    suspend fun delete(key: String)
}

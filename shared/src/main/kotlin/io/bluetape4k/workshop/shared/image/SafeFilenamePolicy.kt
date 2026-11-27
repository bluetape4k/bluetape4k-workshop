package io.bluetape4k.workshop.shared.image

/** 업로드 파일명에서 경로와 제어 가능한 문자를 제거하는 공통 정책입니다. */
data class SafeFilenamePolicy(
    val defaultFilename: String = "upload.jpg",
    val maxLength: Int = 120,
    val preserveExtension: Boolean = true,
) {
    init {
        require(defaultFilename.isNotBlank()) { "defaultFilename must not be blank" }
        require(maxLength > 0) { "maxLength must be positive" }
    }

    /** 최종 path segment만 남기고 안전하지 않은 문자를 `_`로 치환합니다. */
    fun sanitize(filename: String?): String {
        val finalSegment = filename
            ?.replace('\\', '/')
            ?.substringAfterLast('/')
            ?.map { char ->
                if (char.isLetterOrDigit() || char == '.' || char == '_' || char == '-') char else '_'
            }
            ?.joinToString("")
            ?.trim('.', '_', '-')
            .orEmpty()
            .ifBlank { defaultFilename }

        if (finalSegment.length <= maxLength) return finalSegment
        if (!preserveExtension) return finalSegment.take(maxLength).trimEnd('.', '_', '-')

        val extensionSeparator = finalSegment.lastIndexOf('.')
        if (extensionSeparator <= 0 || extensionSeparator == finalSegment.lastIndex) {
            return finalSegment.take(maxLength).trimEnd('.', '_', '-').ifBlank { defaultFilename }
        }

        val extension = finalSegment.substring(extensionSeparator)
        if (extension.length >= maxLength) {
            return finalSegment.take(maxLength).trimEnd('.', '_', '-').ifBlank { defaultFilename }
        }

        val stemLength = maxLength - extension.length
        return (finalSegment.substring(0, extensionSeparator).take(stemLength).trimEnd('.', '_', '-') + extension)
            .ifBlank { defaultFilename }
    }
}

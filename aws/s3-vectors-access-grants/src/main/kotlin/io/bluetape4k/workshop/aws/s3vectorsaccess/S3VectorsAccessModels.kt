package io.bluetape4k.workshop.aws.s3vectorsaccess

import java.io.Serializable

/**
 * 문서 벡터를 로컬 워크숍 인덱스에 발행하는 요청 본문입니다.
 */
data class VectorDocumentUpsertRequest(
    val documentId: String,
    val title: String,
    val objectKey: String,
    val vector: List<Float>,
    val metadata: Map<String, String> = emptyMap(),
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * 로컬 저장 벡터 순위를 계산하고 선택적으로 S3 Access Grant를 요청하는 요청 본문입니다.
 */
data class VectorSearchRequest(
    val query: List<Float>,
    val topK: Int = 3,
    val requireAccessGrant: Boolean = true,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * 예제가 문서 메타데이터를 저장하고 S3 Vectors 경계를 통과한 뒤 반환하는 결과입니다.
 */
data class VectorDocumentReport(
    val documentId: String,
    val title: String,
    val objectUri: String,
    val metadata: Map<String, String>,
    val status: BoundaryStatus,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * 벡터 경계 상태, 순위화된 매치, access-grant 결정을 담은 검색 응답입니다.
 */
data class VectorSearchReport(
    val query: BoundaryStatus,
    val matches: List<VectorSearchMatch>,
    val access: AccessGrantDecision,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * 로컬 코사인 유사도 예제가 만든 순위화된 문서 매치입니다.
 */
data class VectorSearchMatch(
    val documentId: String,
    val title: String,
    val objectUri: String,
    val score: Double,
    val metadata: Map<String, String>,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * 자격 증명이나 원시 SDK 세부 정보를 노출하지 않는 AWS 경계 호출 상태입니다.
 */
data class BoundaryStatus(
    val state: BoundaryState,
    val boundary: String,
    val message: String = "",
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L

        fun published(boundary: String): BoundaryStatus =
            BoundaryStatus(BoundaryState.PUBLISHED, boundary)

        fun failed(boundary: String, cause: Throwable): BoundaryStatus =
            BoundaryStatus(BoundaryState.FAILED, boundary, cause.safeBoundaryMessage())

        fun skipped(boundary: String, message: String): BoundaryStatus =
            BoundaryStatus(BoundaryState.SKIPPED, boundary, message)
    }
}

/**
 * S3 Access Grants 데이터 접근 요청의 마스킹된 결정입니다.
 */
data class AccessGrantDecision(
    val state: BoundaryState,
    val target: String = "",
    val permission: String = "READ",
    val redacted: Boolean = true,
    val message: String = "",
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L

        fun granted(target: String): AccessGrantDecision =
            AccessGrantDecision(
                state = BoundaryState.GRANTED,
                target = target,
                message = "Temporary data access approved; sensitive AWS values are intentionally omitted.",
            )

        fun skipped(message: String): AccessGrantDecision =
            AccessGrantDecision(BoundaryState.SKIPPED, message = message)

        fun failed(target: String, cause: Throwable): AccessGrantDecision =
            AccessGrantDecision(
                state = BoundaryState.FAILED,
                target = target,
                message = cause.safeBoundaryMessage(),
            )
    }
}

/**
 * 데모 경계 엔드포인트가 노출하는 설정 요약입니다.
 */
data class S3VectorsBoundarySummary(
    val vectorBucketName: String,
    val indexName: String,
    val documentBucketName: String,
    val accessGrantLocationArn: String,
    val localProfile: Boolean,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * 학습자 대상 AWS 경계 응답에 사용하는 대략적인 상태입니다.
 */
enum class BoundaryState {
    PUBLISHED,
    GRANTED,
    SKIPPED,
    FAILED,
}

internal data class StoredVectorDocument(
    val documentId: String,
    val title: String,
    val objectUri: String,
    val vector: List<Float>,
    val metadata: Map<String, String>,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

private fun Throwable.safeBoundaryMessage(): String {
    val sanitizedDetail = message
        ?.replace(SENSITIVE_AWS_VALUE_PATTERN, "<redacted>")
        ?.takeIf { it.isNotBlank() }

    return listOfNotNull(this::class.java.simpleName, sanitizedDetail)
        .joinToString(": ")
}

private val SENSITIVE_AWS_VALUE_PATTERN = Regex(
    pattern = """(?i)\b(accessKeyId|secretAccessKey|sessionToken|aws_access_key_id|aws_secret_access_key|aws_session_token)\s*[:=]\s*[^,\s;]+""",
)

package io.bluetape4k.workshop.aws.s3vectorsaccess

import java.io.Serializable

/**
 * Request body for publishing a document vector into the local workshop index.
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
 * Request body for ranking locally stored vectors and optionally requesting an S3 Access Grant.
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
 * Result returned after the sample stores document metadata and crosses the S3 Vectors boundary.
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
 * Search response containing the vector boundary state, ranked matches, and access-grant decision.
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
 * Ranked document match produced by the local cosine-similarity example.
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
 * Status for an AWS boundary call without exposing credentials or raw SDK details.
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
 * Redacted decision for an S3 Access Grants data-access request.
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
 * Configuration summary surfaced by the demo boundary endpoint.
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
 * Coarse state used for learner-facing AWS boundary responses.
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

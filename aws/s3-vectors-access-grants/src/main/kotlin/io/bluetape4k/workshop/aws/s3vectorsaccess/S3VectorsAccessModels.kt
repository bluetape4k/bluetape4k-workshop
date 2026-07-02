package io.bluetape4k.workshop.aws.s3vectorsaccess

import java.io.Serializable

data class VectorDocumentUpsertRequest(
    val documentId: String,
    val title: String,
    val objectKey: String,
    val vector: List<Float>,
    val metadata: Map<String, String> = emptyMap(),
): Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

data class VectorSearchRequest(
    val query: List<Float>,
    val topK: Int = 3,
    val requireAccessGrant: Boolean = true,
): Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

data class VectorDocumentReport(
    val documentId: String,
    val title: String,
    val objectUri: String,
    val metadata: Map<String, String>,
    val status: BoundaryStatus,
): Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

data class VectorSearchReport(
    val query: BoundaryStatus,
    val matches: List<VectorSearchMatch>,
    val access: AccessGrantDecision,
): Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

data class VectorSearchMatch(
    val documentId: String,
    val title: String,
    val objectUri: String,
    val score: Double,
    val metadata: Map<String, String>,
): Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

data class BoundaryStatus(
    val state: BoundaryState,
    val boundary: String,
    val message: String = "",
): Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L

        fun published(boundary: String): BoundaryStatus =
            BoundaryStatus(BoundaryState.PUBLISHED, boundary)

        fun failed(boundary: String, cause: Throwable): BoundaryStatus =
            BoundaryStatus(BoundaryState.FAILED, boundary, cause.message ?: cause::class.java.simpleName)

        fun skipped(boundary: String, message: String): BoundaryStatus =
            BoundaryStatus(BoundaryState.SKIPPED, boundary, message)
    }
}

data class AccessGrantDecision(
    val state: BoundaryState,
    val target: String = "",
    val permission: String = "READ",
    val redacted: Boolean = true,
    val message: String = "",
): Serializable {
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
                message = cause.message ?: cause::class.java.simpleName,
            )
    }
}

data class S3VectorsBoundarySummary(
    val vectorBucketName: String,
    val indexName: String,
    val documentBucketName: String,
    val accessGrantLocationArn: String,
    val localProfile: Boolean,
): Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

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

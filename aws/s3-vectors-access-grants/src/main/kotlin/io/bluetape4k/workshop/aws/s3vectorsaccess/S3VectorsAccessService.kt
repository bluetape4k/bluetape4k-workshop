package io.bluetape4k.workshop.aws.s3vectorsaccess

import io.bluetape4k.aws.s3vectors.S3VectorsOperations
import io.bluetape4k.aws.spring.s3.accessgrants.S3AccessGrantsOperations
import io.bluetape4k.support.requireInRange
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requireNotEmpty
import org.springframework.stereotype.Service
import software.amazon.awssdk.services.s3control.model.GetDataAccessRequest
import software.amazon.awssdk.services.s3control.model.Permission
import software.amazon.awssdk.services.s3vectors.model.PutVectorsRequest
import software.amazon.awssdk.services.s3vectors.model.QueryVectorsRequest
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.sqrt

/**
 * 로컬 벡터 순위 계산을 S3 Vectors 및 S3 Access Grants 경계 어댑터와 조율합니다.
 */
@Service
class S3VectorsAccessService(
    private val properties: S3VectorsAccessProperties,
    private val s3VectorsOperations: S3VectorsOperations,
    private val accessGrantsOperations: S3AccessGrantsOperations,
) {

    private val documents = ConcurrentHashMap<String, StoredVectorDocument>()

    suspend fun upsertDocument(request: VectorDocumentUpsertRequest): VectorDocumentReport {
        validateUpsert(request)
        val objectUri = toS3Uri(request.objectKey)

        val status = try {
            s3VectorsOperations.putVectors(
                PutVectorsRequest.builder()
                    .vectorBucketName(properties.vectorBucketName)
                    .indexName(properties.indexName)
                    .build()
            )
            documents[request.documentId] = StoredVectorDocument(
                documentId = request.documentId,
                title = request.title,
                objectUri = objectUri,
                vector = request.vector.toList(),
                metadata = request.metadata.toSortedMap(),
            )
            BoundaryStatus.published(S3_VECTORS_BOUNDARY)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            BoundaryStatus.failed(S3_VECTORS_BOUNDARY, e)
        }

        return VectorDocumentReport(
            documentId = request.documentId,
            title = request.title,
            objectUri = objectUri,
            metadata = request.metadata.toSortedMap(),
            status = status,
        )
    }

    suspend fun searchDocuments(request: VectorSearchRequest): VectorSearchReport {
        validateSearch(request)

        val query = try {
            s3VectorsOperations.queryVectors(
                QueryVectorsRequest.builder()
                    .vectorBucketName(properties.vectorBucketName)
                    .indexName(properties.indexName)
                    .build()
            )
            BoundaryStatus.published(S3_VECTORS_BOUNDARY)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return VectorSearchReport(
                query = BoundaryStatus.failed(S3_VECTORS_BOUNDARY, e),
                matches = emptyList(),
                access = AccessGrantDecision.skipped("S3 Vectors query failed before selecting a document."),
            )
        }

        val matches = rankDocuments(request)
        val access = if (request.requireAccessGrant && matches.isNotEmpty()) {
            requestAccessGrant(matches.first().objectUri)
        } else {
            AccessGrantDecision.skipped("Caller requested vector ranking only.")
        }

        return VectorSearchReport(query = query, matches = matches, access = access)
    }

    fun boundarySummary(): S3VectorsBoundarySummary =
        S3VectorsBoundarySummary(
            vectorBucketName = properties.vectorBucketName,
            indexName = properties.indexName,
            documentBucketName = properties.documentBucketName,
            accessGrantLocationArn = properties.accessGrants.locationArn,
            localProfile = true,
        )

    private suspend fun requestAccessGrant(target: String): AccessGrantDecision =
        try {
            accessGrantsOperations.getDataAccess(
                GetDataAccessRequest.builder()
                    .accountId(properties.accessGrants.accountId)
                    .target(target)
                    .permission(Permission.READ)
                    .build()
            )
            AccessGrantDecision.granted(target)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AccessGrantDecision.failed(target, e)
        }

    private fun rankDocuments(request: VectorSearchRequest): List<VectorSearchMatch> =
        documents.values
            .asSequence()
            .map { document ->
                VectorSearchMatch(
                    documentId = document.documentId,
                    title = document.title,
                    objectUri = document.objectUri,
                    score = cosineSimilarity(request.query, document.vector),
                    metadata = document.metadata,
                )
            }
            .sortedByDescending { it.score }
            .take(request.topK.coerceAtMost(properties.maxSearchResults))
            .toList()

    private fun validateUpsert(request: VectorDocumentUpsertRequest) {
        request.documentId.requireNotBlank("documentId")
        request.title.requireNotBlank("title")
        request.objectKey.requireNotBlank("objectKey")
        request.vector.requireNotEmpty("vector")
        request.vector.size.requireInRange(1, properties.maxVectorDimensions, "vector.size")
        request.vector.requireFinite("vector")
    }

    private fun validateSearch(request: VectorSearchRequest) {
        request.query.requireNotEmpty("query")
        request.query.size.requireInRange(1, properties.maxVectorDimensions, "query.size")
        request.query.requireFinite("query")
        request.topK.requireInRange(1, properties.maxSearchResults, "topK")
    }

    private fun toS3Uri(objectKey: String): String {
        val normalizedKey = objectKey.trim().trimStart('/')
        val normalizedPrefix = properties.objectPrefix.trim().trimStart('/')
        val key = when {
            normalizedPrefix.isBlank() -> normalizedKey
            normalizedKey.startsWith(normalizedPrefix) -> normalizedKey
            else -> "$normalizedPrefix$normalizedKey"
        }
        return "s3://${properties.documentBucketName}/$key"
    }

    private fun cosineSimilarity(left: List<Float>, right: List<Float>): Double {
        val size = minOf(left.size, right.size)
        if (size == 0) {
            return 0.0
        }

        var dot = 0.0
        var leftNorm = 0.0
        var rightNorm = 0.0
        for (index in 0 until size) {
            val leftValue = left[index].toDouble()
            val rightValue = right[index].toDouble()
            dot += leftValue * rightValue
            leftNorm += leftValue * leftValue
            rightNorm += rightValue * rightValue
        }
        if (leftNorm == 0.0 || rightNorm == 0.0) {
            return 0.0
        }
        return dot / (sqrt(leftNorm) * sqrt(rightNorm))
    }

    private fun List<Float>.requireFinite(name: String) {
        if (any { it.isNaN() || it.isInfinite() }) {
            throw IllegalArgumentException("$name must contain only finite values.")
        }
    }

    companion object {
        private const val S3_VECTORS_BOUNDARY = "S3 Vectors"
    }
}

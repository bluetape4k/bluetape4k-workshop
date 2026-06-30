package io.bluetape4k.workshop.aws.s3vectorsaccess

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.aws.s3vectors.S3VectorsOperations
import io.bluetape4k.aws.spring.s3.accessgrants.S3AccessGrantsOperations
import io.bluetape4k.junit5.coroutines.runSuspendIO
import org.junit.jupiter.api.Test
import software.amazon.awssdk.services.s3control.model.GetDataAccessRequest
import software.amazon.awssdk.services.s3control.model.GetDataAccessResponse
import software.amazon.awssdk.services.s3control.model.ListAccessGrantsInstancesRequest
import software.amazon.awssdk.services.s3control.model.ListAccessGrantsInstancesResponse
import software.amazon.awssdk.services.s3control.model.ListAccessGrantsLocationsRequest
import software.amazon.awssdk.services.s3control.model.ListAccessGrantsLocationsResponse
import software.amazon.awssdk.services.s3control.model.ListAccessGrantsRequest
import software.amazon.awssdk.services.s3control.model.ListAccessGrantsResponse
import software.amazon.awssdk.services.s3control.model.ListCallerAccessGrantsRequest
import software.amazon.awssdk.services.s3control.model.ListCallerAccessGrantsResponse
import software.amazon.awssdk.services.s3vectors.model.GetIndexRequest
import software.amazon.awssdk.services.s3vectors.model.GetIndexResponse
import software.amazon.awssdk.services.s3vectors.model.GetVectorBucketRequest
import software.amazon.awssdk.services.s3vectors.model.GetVectorBucketResponse
import software.amazon.awssdk.services.s3vectors.model.GetVectorsRequest
import software.amazon.awssdk.services.s3vectors.model.GetVectorsResponse
import software.amazon.awssdk.services.s3vectors.model.ListIndexesRequest
import software.amazon.awssdk.services.s3vectors.model.ListIndexesResponse
import software.amazon.awssdk.services.s3vectors.model.ListVectorBucketsRequest
import software.amazon.awssdk.services.s3vectors.model.ListVectorBucketsResponse
import software.amazon.awssdk.services.s3vectors.model.ListVectorsRequest
import software.amazon.awssdk.services.s3vectors.model.ListVectorsResponse
import software.amazon.awssdk.services.s3vectors.model.PutVectorsRequest
import software.amazon.awssdk.services.s3vectors.model.PutVectorsResponse
import software.amazon.awssdk.services.s3vectors.model.QueryVectorsRequest
import software.amazon.awssdk.services.s3vectors.model.QueryVectorsResponse
import kotlin.coroutines.cancellation.CancellationException

class S3VectorsAccessServiceTest {

    @Test
    fun `upserts vector document then queries and requests access grant for top match`() = runSuspendIO {
        val fixture = serviceFixture()

        val upsert = fixture.service.upsertDocument(
            VectorDocumentUpsertRequest(
                documentId = "doc-1",
                title = "Coroutine Flow backpressure",
                objectKey = "docs/coroutines-flow.md",
                vector = listOf(0.9f, 0.1f, 0.0f),
                metadata = mapOf("topic" to "coroutines"),
            )
        )

        upsert.status.state shouldBeEqualTo BoundaryState.PUBLISHED
        upsert.objectUri shouldBeEqualTo "s3://workshop-documents/docs/coroutines-flow.md"
        fixture.vectors.putRequests.single().vectorBucketName() shouldBeEqualTo "semantic-documents"
        fixture.vectors.putRequests.single().indexName() shouldBeEqualTo "docs-rag"

        val report = fixture.service.searchDocuments(
            VectorSearchRequest(
                query = listOf(0.8f, 0.2f, 0.0f),
                topK = 2,
                requireAccessGrant = true,
            )
        )

        report.query.state shouldBeEqualTo BoundaryState.PUBLISHED
        report.matches.single().documentId shouldBeEqualTo "doc-1"
        report.matches.single().objectUri shouldBeEqualTo "s3://workshop-documents/docs/coroutines-flow.md"
        report.access.state shouldBeEqualTo BoundaryState.GRANTED
        report.access.target shouldBeEqualTo "s3://workshop-documents/docs/coroutines-flow.md"
        report.access.permission shouldBeEqualTo "READ"
        report.access.redacted.shouldBeTrue()
        report.access.message shouldNotContain "accessKeyId"
        report.access.message shouldNotContain "secretAccessKey"
        report.access.message shouldNotContain "sessionToken"

        fixture.vectors.queryRequests.single().vectorBucketName() shouldBeEqualTo "semantic-documents"
        fixture.vectors.queryRequests.single().indexName() shouldBeEqualTo "docs-rag"
        fixture.accessGrants.dataAccessRequests.single().accountId() shouldBeEqualTo "123456789012"
        fixture.accessGrants.dataAccessRequests.single().target() shouldBeEqualTo
            "s3://workshop-documents/docs/coroutines-flow.md"
    }

    @Test
    fun `skips access grants when caller only wants vector ranking`() = runSuspendIO {
        val fixture = serviceFixture()
        fixture.service.upsertDocument(
            VectorDocumentUpsertRequest(
                documentId = "doc-2",
                title = "S3 Vectors overview",
                objectKey = "docs/s3-vectors.md",
                vector = listOf(0.2f, 0.8f),
            )
        )

        val report = fixture.service.searchDocuments(
            VectorSearchRequest(
                query = listOf(0.1f, 0.9f),
                requireAccessGrant = false,
            )
        )

        report.matches.single().documentId shouldBeEqualTo "doc-2"
        report.access.state shouldBeEqualTo BoundaryState.SKIPPED
        fixture.accessGrants.dataAccessRequests.size shouldBeEqualTo 0
    }

    @Test
    fun `reports query failure without requesting access grants`() = runSuspendIO {
        val fixture = serviceFixture()
        fixture.vectors.failure = IllegalStateException("query denied")

        val report = fixture.service.searchDocuments(
            VectorSearchRequest(
                query = listOf(1.0f, 0.0f),
                requireAccessGrant = true,
            )
        )

        report.query.state shouldBeEqualTo BoundaryState.FAILED
        report.query.message shouldContain "query denied"
        report.matches.size shouldBeEqualTo 0
        report.access.state shouldBeEqualTo BoundaryState.SKIPPED
        fixture.accessGrants.dataAccessRequests.size shouldBeEqualTo 0
    }

    @Test
    fun `rethrows cancellation from vector query`() = runSuspendIO {
        val fixture = serviceFixture()
        fixture.vectors.failure = CancellationException("query cancelled")

        assertFailsWith<CancellationException> {
            fixture.service.searchDocuments(VectorSearchRequest(query = listOf(1.0f)))
        }
    }

    private fun serviceFixture(): ServiceFixture {
        val properties = S3VectorsAccessProperties(
            vectorBucketName = "semantic-documents",
            indexName = "docs-rag",
            documentBucketName = "workshop-documents",
            objectPrefix = "docs/",
            accessGrants = S3AccessGrantsBoundaryProperties(
                accountId = "123456789012",
                locationArn = "arn:aws:s3:ap-northeast-2:123456789012:access-grants/default/location/default",
            ),
        )
        val vectors = CapturingS3VectorsOperations()
        val accessGrants = CapturingS3AccessGrantsOperations()
        return ServiceFixture(
            service = S3VectorsAccessService(properties, vectors, accessGrants),
            vectors = vectors,
            accessGrants = accessGrants,
        )
    }

    private class ServiceFixture(
        val service: S3VectorsAccessService,
        val vectors: CapturingS3VectorsOperations,
        val accessGrants: CapturingS3AccessGrantsOperations,
    )

    private class CapturingS3VectorsOperations: S3VectorsOperations {
        val putRequests = mutableListOf<PutVectorsRequest>()
        val queryRequests = mutableListOf<QueryVectorsRequest>()
        var failure: Throwable? = null

        override suspend fun putVectors(request: PutVectorsRequest): PutVectorsResponse {
            failure?.let { throw it }
            putRequests += request
            return PutVectorsResponse.builder().build()
        }

        override suspend fun queryVectors(request: QueryVectorsRequest): QueryVectorsResponse {
            failure?.let { throw it }
            queryRequests += request
            return QueryVectorsResponse.builder().build()
        }

        override suspend fun listVectorBuckets(request: ListVectorBucketsRequest): ListVectorBucketsResponse =
            ListVectorBucketsResponse.builder().build()

        override suspend fun getVectorBucket(request: GetVectorBucketRequest): GetVectorBucketResponse =
            GetVectorBucketResponse.builder().build()

        override suspend fun listIndexes(request: ListIndexesRequest): ListIndexesResponse =
            ListIndexesResponse.builder().build()

        override suspend fun getIndex(request: GetIndexRequest): GetIndexResponse =
            GetIndexResponse.builder().build()

        override suspend fun getVectors(request: GetVectorsRequest): GetVectorsResponse =
            GetVectorsResponse.builder().build()

        override suspend fun listVectors(request: ListVectorsRequest): ListVectorsResponse =
            ListVectorsResponse.builder().build()
    }

    private class CapturingS3AccessGrantsOperations: S3AccessGrantsOperations {
        val dataAccessRequests = mutableListOf<GetDataAccessRequest>()

        override suspend fun getDataAccess(request: GetDataAccessRequest): GetDataAccessResponse {
            dataAccessRequests += request
            return GetDataAccessResponse.builder().build()
        }

        override suspend fun listCallerAccessGrants(
            request: ListCallerAccessGrantsRequest,
        ): ListCallerAccessGrantsResponse = ListCallerAccessGrantsResponse.builder().build()

        override suspend fun listAccessGrants(request: ListAccessGrantsRequest): ListAccessGrantsResponse =
            ListAccessGrantsResponse.builder().build()

        override suspend fun listAccessGrantsInstances(
            request: ListAccessGrantsInstancesRequest,
        ): ListAccessGrantsInstancesResponse = ListAccessGrantsInstancesResponse.builder().build()

        override suspend fun listAccessGrantsLocations(
            request: ListAccessGrantsLocationsRequest,
        ): ListAccessGrantsLocationsResponse = ListAccessGrantsLocationsResponse.builder().build()
    }
}

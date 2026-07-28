package io.bluetape4k.workshop.aws.s3vectorsaccess

import io.bluetape4k.aws.s3vectors.S3VectorsOperations
import io.bluetape4k.aws.spring.s3.accessgrants.S3AccessGrantsOperations
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
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

/**
 * 예제가 실제 AWS 자격 증명 없이 실행될 때 사용하는 로컬 AWS 경계 어댑터입니다.
 */
@Configuration(proxyBeanMethods = false)
@Profile("!real-aws")
class LocalS3VectorsAccessConfig {

    @Bean
    @ConditionalOnMissingBean(S3VectorsOperations::class)
    fun localS3VectorsOperations(): S3VectorsOperations =
        LocalS3VectorsOperations()

    @Bean
    @ConditionalOnMissingBean(S3AccessGrantsOperations::class)
    fun localS3AccessGrantsOperations(): S3AccessGrantsOperations =
        LocalS3AccessGrantsOperations()
}

private class LocalS3VectorsOperations : S3VectorsOperations {

    override suspend fun putVectors(request: PutVectorsRequest): PutVectorsResponse =
        PutVectorsResponse.builder().build()

    override suspend fun queryVectors(request: QueryVectorsRequest): QueryVectorsResponse =
        QueryVectorsResponse.builder().build()

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

private class LocalS3AccessGrantsOperations : S3AccessGrantsOperations {

    override suspend fun getDataAccess(request: GetDataAccessRequest): GetDataAccessResponse =
        GetDataAccessResponse.builder().build()

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

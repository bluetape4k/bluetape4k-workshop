package io.bluetape4k.workshop.aws.s3vectorsaccess

import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
import org.springframework.boot.context.properties.ConfigurationProperties
import java.io.Serializable

/**
 * Configuration properties for the local S3 Vectors and Access Grants workshop sample.
 */
@ConfigurationProperties(prefix = "workshop.aws.s3-vector-access")
data class S3VectorsAccessProperties(
    val vectorBucketName: String = "semantic-documents",
    val indexName: String = "docs-rag",
    val documentBucketName: String = "workshop-documents",
    val objectPrefix: String = "docs/",
    val maxSearchResults: Int = 5,
    val maxVectorDimensions: Int = 16,
    val accessGrants: S3AccessGrantsBoundaryProperties = S3AccessGrantsBoundaryProperties(),
) : Serializable {

    init {
        vectorBucketName.requireNotBlank("vectorBucketName")
        indexName.requireNotBlank("indexName")
        documentBucketName.requireNotBlank("documentBucketName")
        maxSearchResults.requirePositiveNumber("maxSearchResults")
        maxVectorDimensions.requirePositiveNumber("maxVectorDimensions")
    }

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * Access Grants boundary settings used to build redacted data-access requests.
 */
data class S3AccessGrantsBoundaryProperties(
    val accountId: String = "123456789012",
    val locationArn: String = "arn:aws:s3:ap-northeast-2:123456789012:access-grants/default/location/default",
) : Serializable {

    init {
        accountId.requireNotBlank("accountId")
        locationArn.requireNotBlank("locationArn")
    }

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

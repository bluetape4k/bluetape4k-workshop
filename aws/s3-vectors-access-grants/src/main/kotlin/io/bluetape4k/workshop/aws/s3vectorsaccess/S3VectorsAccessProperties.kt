package io.bluetape4k.workshop.aws.s3vectorsaccess

import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
import org.springframework.boot.context.properties.ConfigurationProperties
import java.io.Serializable

/**
 * 로컬 S3 Vectors와 Access Grants 워크숍 예제의 설정 속성입니다.
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
 * 마스킹된 데이터 접근 요청을 만드는 데 사용하는 Access Grants 경계 설정입니다.
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

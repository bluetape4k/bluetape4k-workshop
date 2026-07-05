package io.bluetape4k.workshop.aws.s3vectorsaccess

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * HTTP API for the local S3 Vectors and Access Grants workshop flow.
 */
@RestController
@RequestMapping("/aws/s3-vectors")
class S3VectorsAccessController(
    private val service: S3VectorsAccessService,
) {

    @GetMapping("/boundary")
    fun boundary(): S3VectorsBoundarySummary =
        service.boundarySummary()

    @PostMapping("/documents")
    suspend fun upsert(@RequestBody request: VectorDocumentUpsertRequest): VectorDocumentReport =
        service.upsertDocument(request)

    @PostMapping("/search")
    suspend fun search(@RequestBody request: VectorSearchRequest): VectorSearchReport =
        service.searchDocuments(request)
}

package io.bluetape4k.workshop.exposed.javers.persistence

import io.bluetape4k.javers.codecs.JaversCodec
import io.bluetape4k.javers.persistence.redis.repository.RedissonCdoSnapshotRepository
import io.bluetape4k.javers.repository.CdoSnapshotRepository
import io.bluetape4k.redis.redisson.codec.RedissonCodecs
import org.javers.core.json.JsonConverter
import org.javers.core.metamodel.`object`.CdoSnapshot
import org.javers.core.metamodel.`object`.GlobalId
import org.javers.repository.api.QueryParams
import org.redisson.api.RListMultimap
import org.redisson.api.RedissonClient

/**
 * Exact-instance history query의 Redis read/decode를 query limit으로 제한하는 workshop adapter이다.
 *
 * filter, aggregate, skip이 있는 query는 의미를 보존하기 위해 [delegate]로 위임한다.
 */
internal class BoundedRedissonCdoSnapshotRepository(
    repositoryName: String,
    redisson: RedissonClient,
    private val snapshotCodec: JaversCodec<ByteArray>,
    private val delegate: RedissonCdoSnapshotRepository =
        RedissonCdoSnapshotRepository(repositoryName, redisson, snapshotCodec),
): CdoSnapshotRepository by delegate {

    private val snapshots: RListMultimap<String, ByteArray> =
        redisson.getListMultimap(
            "javers:$repositoryName:snapshot",
            RedissonCodecs.LZ4ForyComposite,
        )

    @Volatile
    private var jsonConverter: JsonConverter? = null

    override fun setJsonConverter(jsonConverter: JsonConverter?) {
        delegate.setJsonConverter(jsonConverter)
        this.jsonConverter = jsonConverter
    }

    override fun getStateHistory(
        globalId: GlobalId,
        queryParams: QueryParams,
    ): MutableList<CdoSnapshot> {
        if (!queryParams.supportsBoundedExactInstanceHistory()) {
            return delegate.getStateHistory(globalId, queryParams)
        }

        val converter = requireNotNull(jsonConverter) {
            "JsonConverter is not set. Ensure Javers initialized the repository before querying."
        }
        return snapshots[globalId.value()]
            .range(-queryParams.limit(), -1)
            .asReversed()
            .mapNotNull { encoded ->
                if (encoded.isEmpty()) {
                    null
                } else {
                    snapshotCodec.decode(encoded)?.let { json ->
                        converter.fromJson(json, CdoSnapshot::class.java)
                    }
                }
            }
            .toMutableList()
    }
}

private fun QueryParams.supportsBoundedExactInstanceHistory(): Boolean =
    limit() > 0 &&
        skip() == 0 &&
        !isAggregate &&
        !hasSnapshotQueryLimit() &&
        commitIds().isEmpty() &&
        toCommitId().isEmpty &&
        version().isEmpty &&
        fromVersion().isEmpty &&
        toVersion().isEmpty &&
        author().isEmpty &&
        authorLikeIgnoreCase().isEmpty &&
        from().isEmpty &&
        fromInstant().isEmpty &&
        to().isEmpty &&
        toInstant().isEmpty &&
        changedProperties().isEmpty() &&
        snapshotType().isEmpty &&
        commitProperties().isEmpty() &&
        commitPropertiesLike().isEmpty()

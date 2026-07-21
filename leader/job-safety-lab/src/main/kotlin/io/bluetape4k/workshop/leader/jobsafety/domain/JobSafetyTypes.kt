package io.bluetape4k.workshop.leader.jobsafety.domain

import io.bluetape4k.support.requireGt
import io.bluetape4k.support.requireNotBlank
import java.time.YearMonth

@JvmInline
value class LeaderOwnerId(val value: String) {
    init {
        value.requireNotBlank("leaderOwnerId")
    }
}

@JvmInline
value class FencingOwnerId(val value: String) {
    init {
        value.requireNotBlank("fencingOwnerId")
    }
}

@JvmInline
value class TenantId(val value: String) {
    init {
        value.requireNotBlank("tenantId")
    }
}

@JvmInline
value class JobName(val value: String) {
    init {
        value.requireNotBlank("jobName")
    }
}

@JvmInline
value class RegionId(val value: String) {
    init {
        value.requireNotBlank("regionId")
    }
}

@JvmInline
value class OperationId(val value: String) {
    init {
        value.requireNotBlank("operationId")
    }
}

@JvmInline
value class ConflictKey private constructor(val value: String) {
    init {
        value.requireNotBlank("conflictKey")
    }

    companion object {
        fun summary(tenantId: TenantId, period: YearMonth): ConflictKey =
            ConflictKey("summary:${tenantId.value}:$period")

        fun of(value: String): ConflictKey = ConflictKey(value)
    }
}

@JvmInline
value class FencingToken(val value: Long) : Comparable<FencingToken> {
    init {
        value.requireGt(0L, "fencingToken")
    }

    override fun compareTo(other: FencingToken): Int = value.compareTo(other.value)
}

@JvmInline
value class MembershipRevision(val value: Long) {
    init {
        value.requireGt(0L, "membershipRevision")
    }
}

@JvmInline
value class RegionEpoch(val value: Long) {
    init {
        value.requireGt(0L, "regionEpoch")
    }
}

@JvmInline
value class NamespaceEpoch(val value: Long) {
    init {
        value.requireGt(0L, "namespaceEpoch")
    }
}

@JvmInline
value class ExecutionContractVersion(val value: Int) {
    init {
        value.requireGt(0, "executionContractVersion")
    }
}

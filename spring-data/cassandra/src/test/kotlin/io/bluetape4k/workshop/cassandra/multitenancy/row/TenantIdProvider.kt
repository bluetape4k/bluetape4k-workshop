package io.bluetape4k.workshop.cassandra.multitenancy.row

import io.bluetape4k.logging.coroutines.KLoggingChannel

object TenantIdProvider: KLoggingChannel() {

    val tenantId: ThreadLocal<String> = ThreadLocal.withInitial { "" }

}

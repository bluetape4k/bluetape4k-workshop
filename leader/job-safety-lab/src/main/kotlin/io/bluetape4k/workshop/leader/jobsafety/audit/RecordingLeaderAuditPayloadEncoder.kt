package io.bluetape4k.workshop.leader.jobsafety.audit

import io.bluetape4k.leader.audit.LeaderAuditExportEvent
import io.bluetape4k.leader.audit.http.LeaderAuditHttpPayload
import io.bluetape4k.leader.audit.http.LeaderAuditPayloadEncoder

/**
 * 실제 transport와 무관하게 serialized audit payload를 bounded observation store에
 * 캡처하는 encoder decorator입니다.
 *
 * MEMORY fake와 HTTPS client 모두 이 decorator를 공유하므로 endpoint 응답이나 재시도
 * 결과와 별개로 동일한 redacted JSON 경계를 report에서 확인할 수 있습니다.
 */
class RecordingLeaderAuditPayloadEncoder(
    private val delegate: LeaderAuditPayloadEncoder,
    private val store: BoundedAuditPayloadStore,
) : LeaderAuditPayloadEncoder {

    override fun encode(event: LeaderAuditExportEvent): LeaderAuditHttpPayload =
        delegate.encode(event).also { payload ->
            store.add(payload.body())
        }
}

package io.bluetape4k.workshop.flow.subject.bridge

import java.io.Serializable

/**
 * callback-style SDK listener 에서 수신하는 device event kind 입니다.
 */
enum class DeviceEventType {
    CONNECTED,
    TELEMETRY,
    DISCONNECTED,
    FAULT,
}

/**
 * latest-state stream 으로 노출되는 current device state 입니다.
 */
enum class DeviceStatus {
    ONLINE,
    DEGRADED,
    OFFLINE,
}

/**
 * external callback API 에서 들어오는 event-only signal 입니다.
 */
data class DeviceEvent(
    val eventId: String,
    val deviceId: String,
    val type: DeviceEventType,
    val payload: String,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * device 의 latest state snapshot 입니다.
 */
data class DeviceState(
    val deviceId: String,
    val status: DeviceStatus,
    val lastEventId: String,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 2L
    }
}

/**
 * callback event 에서 생성되는 single-consumer work item 입니다.
 */
data class WorkItem(
    val workId: String,
    val deviceId: String,
    val command: String,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 3L
    }
}

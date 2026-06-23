package io.bluetape4k.workshop.flow.subject.bridge

import java.io.Serializable

/**
 * Device event kind received from a callback-style SDK listener.
 */
enum class DeviceEventType {
    CONNECTED,
    TELEMETRY,
    DISCONNECTED,
    FAULT,
}

/**
 * Current device state exposed as a latest-state stream.
 */
enum class DeviceStatus {
    ONLINE,
    DEGRADED,
    OFFLINE,
}

/**
 * Event-only signal from an external callback API.
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
 * Latest state snapshot for a device.
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
 * Single-consumer work item produced from callback events.
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

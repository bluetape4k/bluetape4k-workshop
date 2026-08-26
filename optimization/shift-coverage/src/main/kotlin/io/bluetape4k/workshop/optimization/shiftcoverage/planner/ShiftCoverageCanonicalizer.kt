package io.bluetape4k.workshop.optimization.shiftcoverage.planner

import io.bluetape4k.workshop.optimization.shiftcoverage.domain.InvalidShiftCoverageInput
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.ShiftCoverageLimits
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.ShiftCoverageSnapshot
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.SnapshotDigest
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.text.Normalizer
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.json.JsonMapper

/** immutable snapshot을 provider-independent canonical v1 JSON으로 정규화합니다. */
class ShiftCoverageCanonicalizer(
    private val mapper: ObjectMapper = JsonMapper.builder().build(),
) {
    fun canonicalBytes(snapshot: ShiftCoverageSnapshot): ByteArray {
        validateSnapshot(snapshot)
        val json = buildString {
            append('{')
            field("aggregateRevision") { append(snapshot.aggregateRevision) }
            append(',')
            field("assignments") { appendAssignments(snapshot) }
            append(',')
            field("generationId") { appendString(snapshot.generationId.value) }
            append(',')
            field("planId") { appendString(snapshot.planId.value) }
            append(',')
            field("schemaVersion") { appendString(snapshot.schemaVersion) }
            append(',')
            field("shifts") { appendShifts(snapshot) }
            append(',')
            field("siteId") { appendString(snapshot.siteId.value) }
            append(',')
            field("workers") { appendWorkers(snapshot) }
            append('}')
        }
        return json.toByteArray(UTF_8)
    }

    fun digest(snapshot: ShiftCoverageSnapshot): SnapshotDigest {
        val bytes = MessageDigest.getInstance("SHA-256").digest(canonicalBytes(snapshot))
        return SnapshotDigest(bytes.joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) })
    }

    fun compare(expected: SnapshotDigest, actual: SnapshotDigest): DigestMatch {
        val same = MessageDigest.isEqual(expected.value.toByteArray(UTF_8), actual.value.toByteArray(UTF_8))
        return if (same) DigestMatch.MATCH else DigestMatch.MISMATCH
    }

    private fun validateSnapshot(snapshot: ShiftCoverageSnapshot) {
        val workerIds = snapshot.workers.map { it.workerId }
        val shiftIds = snapshot.shifts.map { it.shiftId }
        val assignmentIds = snapshot.assignments.map { it.assignmentId }
        if (workerIds.size != workerIds.toSet().size || shiftIds.size != shiftIds.toSet().size ||
            assignmentIds.size != assignmentIds.toSet().size
        ) {
            throw InvalidShiftCoverageInput("snapshot identifiers must be unique")
        }
        if (snapshot.schemaVersion != "v1") throw InvalidShiftCoverageInput("unsupported canonical schema version")
        if (snapshot.workers.any { it.siteId != snapshot.siteId } || snapshot.shifts.any { it.siteId != snapshot.siteId } ||
            snapshot.assignments.any { it.siteId != snapshot.siteId }
        ) {
            throw InvalidShiftCoverageInput("snapshot contains a foreign site")
        }
        if (canonicalBytesSizeUpperBound(snapshot) > ShiftCoverageLimits.MAX_BODY_BYTES) {
            throw InvalidShiftCoverageInput("canonical snapshot exceeds body limit")
        }
    }

    private fun canonicalBytesSizeUpperBound(snapshot: ShiftCoverageSnapshot): Int =
        snapshot.workers.sumOf { it.displayName.length + it.skills.sumOf { skill -> skill.value.length } } +
            snapshot.shifts.sumOf { shift -> shift.requiredSkills.sumOf { skill -> skill.value.length } } + 512

    private fun StringBuilder.field(name: String, value: StringBuilder.() -> Unit) {
        appendString(name)
        append(':')
        value()
    }

    private fun StringBuilder.appendWorkers(snapshot: ShiftCoverageSnapshot) {
        append('[')
        snapshot.workers.sortedBy { it.workerId.value }.forEachIndexed { index, worker ->
            if (index > 0) append(',')
            append('{')
            field("availability") { appendIntervals(worker.availability) }
            append(','); field("displayName") { appendString(worker.displayName) }
            append(','); field("preferences") { appendPreferences(worker.preferences) }
            append(','); field("revision") { append(worker.revision) }
            append(','); field("scheduleRevision") { append(worker.scheduleRevision) }
            append(','); field("sickCalled") { append(worker.sickCalled) }
            append(','); field("siteId") { appendString(worker.siteId.value) }
            append(','); field("skills") { appendStrings(worker.skills.map { it.value }) }
            append(','); field("workerId") { appendString(worker.workerId.value) }
            append('}')
        }
        append(']')
    }

    private fun StringBuilder.appendShifts(snapshot: ShiftCoverageSnapshot) {
        append('[')
        snapshot.shifts.sortedWith(compareBy({ it.startAt }, { it.shiftId.value })).forEachIndexed { index, shift ->
            if (index > 0) append(',')
            append('{')
            append("\"demand\":"); append(shift.demand)
            append(",\"endAt\":"); appendInstant(shift.endAt)
            append(",\"pinnedWorkerId\":"); shift.pinnedWorkerId?.let { appendString(it.value) } ?: append("null")
            append(",\"preference\":"); shift.preference?.let { appendPreference(it) } ?: append("null")
            append(",\"requiredSkills\":"); appendStrings(shift.requiredSkills.map { it.value })
            append(",\"revision\":"); append(shift.revision)
            append(",\"shiftId\":"); appendString(shift.shiftId.value)
            append(",\"siteId\":"); appendString(shift.siteId.value)
            append(",\"startAt\":"); appendInstant(shift.startAt)
            append(",\"startedAt\":"); shift.startedAt?.let { instant -> appendInstant(instant) } ?: append("null")
            append('}')
        }
        append(']')
    }

    private fun StringBuilder.appendAssignments(snapshot: ShiftCoverageSnapshot) {
        append('[')
        snapshot.assignments.sortedWith(compareBy({ it.shiftId.value }, { it.workerId.value }, { it.assignmentId.value }))
            .forEachIndexed { index, assignment ->
                if (index > 0) append(',')
                append('{')
                append("\"assignmentId\":"); appendString(assignment.assignmentId.value)
                append(",\"pinned\":"); append(assignment.pinned)
                append(",\"revision\":"); append(assignment.revision)
                append(",\"shiftId\":"); appendString(assignment.shiftId.value)
                append(",\"siteId\":"); appendString(assignment.siteId.value)
                append(",\"started\":"); append(assignment.started)
                append(",\"workerId\":"); appendString(assignment.workerId.value)
                append('}')
            }
        append(']')
    }

    private fun StringBuilder.appendIntervals(intervals: List<io.bluetape4k.workshop.optimization.shiftcoverage.domain.TimeInterval>) {
        append('[')
        intervals.sortedWith(compareBy({ it.startAt }, { it.endAt })).forEachIndexed { index, interval ->
            if (index > 0) append(',')
            append('{'); append("\"endAt\":"); appendInstant(interval.endAt)
            append(",\"startAt\":"); appendInstant(interval.startAt); append('}')
        }
        append(']')
    }

    private fun StringBuilder.appendPreferences(preferences: List<io.bluetape4k.workshop.optimization.shiftcoverage.domain.WorkerPreference>) {
        append('[')
        preferences.sortedBy { it.skill.value }.forEachIndexed { index, preference ->
            if (index > 0) append(',')
            appendPreference(preference)
        }
        append(']')
    }

    private fun StringBuilder.appendPreference(preference: io.bluetape4k.workshop.optimization.shiftcoverage.domain.WorkerPreference) {
        append('{'); append("\"skill\":"); appendString(preference.skill.value)
        append(",\"weightMinor\":"); append(preference.weightMinor); append('}')
    }

    private fun StringBuilder.appendStrings(values: List<String>) {
        append('[')
        values.map(::nfc).sorted().forEachIndexed { index, value ->
            if (index > 0) append(',')
            appendString(value)
        }
        append(']')
    }

    private fun StringBuilder.appendInstant(value: Instant) = appendString(DateTimeFormatter.ISO_INSTANT.withZone(ZoneOffset.UTC).format(value))

    private fun StringBuilder.appendString(value: String) {
        // Jackson 3 supplies the escaping contract; NFC is applied before the bytes are emitted.
        append(mapper.writeValueAsString(nfc(value)))
    }

    private fun nfc(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFC)

}

enum class DigestMatch { MATCH, MISMATCH }

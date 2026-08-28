package io.bluetape4k.workshop.aws.settings

import io.bluetape4k.aws.kotlin.secretsmanager.AwsSecretValue

/** Provider-neutral read boundary for a named application setting. */
fun interface SettingsSource {
    suspend fun resolve(key: String): SettingsResolution
}

/** Result of a settings lookup without exposing provider-specific exceptions. */
sealed interface SettingsResolution {
    data class Found(val value: AwsSecretValue) : SettingsResolution

    data object Missing : SettingsResolution

    data object Denied : SettingsResolution
}

/** Action to take when a provider cannot return a requested setting. */
enum class SettingsFailureAction {
    FAIL,
    OMIT,
}

/** Missing/denied fallback policy for one startup or refresh operation. */
data class SettingsFallbackPolicy(
    val missing: SettingsFailureAction,
    val denied: SettingsFailureAction,
) {
    companion object {
        fun failFast(): SettingsFallbackPolicy = SettingsFallbackPolicy(
            missing = SettingsFailureAction.FAIL,
            denied = SettingsFailureAction.FAIL,
        )

        fun omit(): SettingsFallbackPolicy = SettingsFallbackPolicy(
            missing = SettingsFailureAction.OMIT,
            denied = SettingsFailureAction.OMIT,
        )
    }
}

/** A complete result set for one settings lookup operation. */
data class SettingsSnapshot(
    val entries: Map<String, SettingsResolution>,
) {
    /** Returns Missing for a key that was not part of this snapshot. */
    fun resolve(key: String): SettingsResolution = entries[key] ?: SettingsResolution.Missing

    /** Returns a log-safe view that never contains a resolved secret value. */
    fun redactedEntries(): Map<String, String> = entries.mapValues { (_, resolution) ->
        when (resolution) {
            is SettingsResolution.Found -> AwsSecretValue.REDACTED
            SettingsResolution.Missing -> "<missing>"
            SettingsResolution.Denied -> "<denied>"
        }
    }
}

/** Provider failure without secret payload, credential, or response details. */
class SettingsUnavailableException(
    val key: String,
    val resolution: SettingsResolution,
) : IllegalStateException("settings lookup unavailable (${resolution.kindName})")

/**
 * Resolves a complete snapshot for startup and refresh.
 *
 * Every operation builds a new snapshot from the source. Refresh is therefore a
 * full replacement and cannot accidentally retain a value removed upstream.
 */
class SettingsResolver(
    private val source: SettingsSource,
    private val startupPolicy: SettingsFallbackPolicy = SettingsFallbackPolicy.failFast(),
    private val refreshPolicy: SettingsFallbackPolicy = SettingsFallbackPolicy.omit(),
) {
    suspend fun startup(keys: Set<String>): SettingsSnapshot = load(keys, startupPolicy)

    suspend fun refresh(keys: Set<String>): SettingsSnapshot = load(keys, refreshPolicy)

    private suspend fun load(
        keys: Set<String>,
        policy: SettingsFallbackPolicy,
    ): SettingsSnapshot {
        val entries = LinkedHashMap<String, SettingsResolution>(keys.size)
        keys.forEach { key ->
            require(key.isNotBlank()) { "settings key must not be blank" }
            val resolution = source.resolve(key)
            when (resolution) {
                is SettingsResolution.Found -> entries[key] = resolution
                SettingsResolution.Missing -> {
                    if (policy.missing == SettingsFailureAction.FAIL) {
                        throw SettingsUnavailableException(key, resolution)
                    }
                    entries[key] = resolution
                }

                SettingsResolution.Denied -> {
                    if (policy.denied == SettingsFailureAction.FAIL) {
                        throw SettingsUnavailableException(key, resolution)
                    }
                    entries[key] = resolution
                }
            }
        }
        return SettingsSnapshot(entries.toMap())
    }
}

private val SettingsResolution.kindName: String
    get() = when (this) {
        is SettingsResolution.Found -> "found"
        SettingsResolution.Missing -> "missing"
        SettingsResolution.Denied -> "denied"
    }

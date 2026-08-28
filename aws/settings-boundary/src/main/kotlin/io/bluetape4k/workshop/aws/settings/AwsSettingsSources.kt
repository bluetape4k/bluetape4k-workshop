package io.bluetape4k.workshop.aws.settings

import aws.sdk.kotlin.runtime.AwsServiceException
import aws.sdk.kotlin.services.secretsmanager.SecretsManagerClient
import aws.sdk.kotlin.services.secretsmanager.model.ResourceNotFoundException
import io.bluetape4k.aws.kotlin.secretsmanager.getSecretString
import aws.sdk.kotlin.services.ssm.SsmClient
import aws.sdk.kotlin.services.ssm.model.ParameterNotFound
import aws.sdk.kotlin.services.ssm.model.AccessDeniedException as SsmAccessDeniedException
import io.bluetape4k.aws.kotlin.ssm.getSecureParameter
import io.bluetape4k.support.useSafe
import kotlinx.coroutines.CancellationException

/** Settings source backed by one AWS Secrets Manager secret per key. */
class SecretsManagerSettingsSource(
    private val clientFactory: () -> SecretsManagerClient,
    private val loadSecret: suspend (SecretsManagerClient, String) -> io.bluetape4k.aws.kotlin.secretsmanager.AwsSecretValue =
        { client, key -> client.getSecretString(key) },
) : SettingsSource {
    override suspend fun resolve(key: String): SettingsResolution {
        require(key.isNotBlank()) { "settings key must not be blank" }
        return clientFactory().useSafe { client ->
            try {
                SettingsResolution.Found(loadSecret(client, key))
            } catch (cause: Throwable) {
                classifyAwsFailure(cause) { it is ResourceNotFoundException }
            }
        }
    }
}

/** Settings source backed by one encrypted AWS Systems Manager parameter per key. */
class ParameterStoreSettingsSource(
    private val clientFactory: () -> SsmClient,
    private val loadParameter: suspend (SsmClient, String) -> io.bluetape4k.aws.kotlin.secretsmanager.AwsSecretValue =
        { client, key -> client.getSecureParameter(key) },
) : SettingsSource {
    override suspend fun resolve(key: String): SettingsResolution {
        require(key.isNotBlank()) { "settings key must not be blank" }
        return clientFactory().useSafe { client ->
            try {
                SettingsResolution.Found(loadParameter(client, key))
            } catch (cause: Throwable) {
                classifyAwsFailure(cause) { it is ParameterNotFound }
            }
        }
    }
}

private fun classifyAwsFailure(
    cause: Throwable,
    isMissing: (Throwable) -> Boolean,
): SettingsResolution {
    if (cause is CancellationException) {
        throw cause
    }
    return when {
        isMissing(cause) -> SettingsResolution.Missing
        cause.isAccessDenied() -> SettingsResolution.Denied
        else -> throw cause
    }
}

private fun Throwable.isAccessDenied(): Boolean {
    val errorCode = (this as? AwsServiceException)?.sdkErrorMetadata?.errorCode
    return this is SsmAccessDeniedException ||
        errorCode.equals("AccessDenied", ignoreCase = true) ||
        errorCode.equals("AccessDeniedException", ignoreCase = true) ||
        message?.contains("AccessDenied", ignoreCase = true) == true
}

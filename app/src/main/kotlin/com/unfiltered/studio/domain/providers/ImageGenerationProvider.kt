package com.unfiltered.studio.domain.providers

import com.unfiltered.studio.domain.models.BackendCatalog
import com.unfiltered.studio.domain.models.ConnectionInfo
import com.unfiltered.studio.domain.models.GenerationEvent
import com.unfiltered.studio.domain.models.GenerationParameters
import com.unfiltered.studio.domain.models.GenerationRequest
import com.unfiltered.studio.domain.models.ProviderCapabilities
import com.unfiltered.studio.domain.models.ProviderConfig
import kotlinx.coroutines.flow.Flow

/**
 * Application-level contract every backend adapter implements. Adapters own their wire format —
 * nothing outside an adapter assumes how a backend encodes prompts, LoRAs or images.
 *
 * Failures are thrown as [com.unfiltered.studio.core.AppError]. Cancelling the collector of
 * [generate] must abort network calls; [cancel] additionally asks the server to stop.
 */
interface ImageGenerationProvider {
    val config: ProviderConfig

    /** Lightweight reachability + authentication check. */
    suspend fun testConnection(): ConnectionInfo

    /** Lists checkpoints, LoRAs, samplers, … that the backend reports. */
    suspend fun fetchCatalog(): BackendCatalog

    /**
     * Refreshes model-specific information (e.g. Hugging Face provider routing for a repo id).
     * Default: nothing model-specific to fetch.
     */
    suspend fun refreshModelInfo(catalog: BackendCatalog, modelId: String?): BackendCatalog = catalog

    /** Pure function of config + catalog + parameters: which controls are meaningful. */
    fun capabilities(catalog: BackendCatalog?, params: GenerationParameters): ProviderCapabilities

    fun generate(request: GenerationRequest): Flow<GenerationEvent>

    /** Best-effort server-side cancellation of [remoteJobId] (or the current job when null). */
    suspend fun cancel(remoteJobId: String?)

    /**
     * Resumes tracking a job submitted before the app process died. Returns null when the backend
     * has no way to look jobs up again (synchronous APIs).
     */
    fun recover(request: GenerationRequest, remoteJobId: String): Flow<GenerationEvent>? = null

    /** Reads safetensors metadata of a LoRA stored on the server, when the backend exposes it. */
    suspend fun loraMetadata(backendName: String): Map<String, String>? = null
}

/** Resolves provider ids to live adapter instances (rebuilt when the configuration changes). */
interface ProviderRegistry {
    suspend fun get(providerId: String): ImageGenerationProvider?
    suspend fun create(config: ProviderConfig, secrets: ProviderSecretsOverride? = null): ImageGenerationProvider
    fun invalidate(providerId: String)
}

/** Unsaved secrets used when testing a provider from the editor before it is stored. */
data class ProviderSecretsOverride(
    val token: String?,
    val headerValues: Map<String, String>,
)

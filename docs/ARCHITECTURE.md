# Architecture

## Layers

```
presentation (Compose screens, ViewModels)
        │  uses
        ▼
domain (models, ImageGenerationProvider, repository interfaces, use cases)   ← pure Kotlin + a little android.net.Uri
        ▲  implemented by
        │
data (Room, DataStore, Keystore, OkHttp/Retrofit, provider adapters, WorkManager runner)
```

- **ViewModels** expose `StateFlow`s and one-off messages; screens are stateless apart from transient UI state.
- **Repositories** (`ProviderRepository`, `GenerationRepository`, `GenerationHistoryRepository`, `LoraRepository`, `ModelRepository`, `DownloadRepository`, `PromptRepository`, `SettingsRepository`, `AccountRepository`) are the only entry points the UI uses.
- **Errors** are converted to `AppError` (`core/AppError.kt`) at the boundary: transport errors, HTTP status + body (`HttpErrors`), parse errors. `AppError.userMessage` is safe to show; `technicalDetails` is behind a *Details* toggle. Cancellation is never converted.

## Provider abstraction

```kotlin
interface ImageGenerationProvider {
    suspend fun testConnection(): ConnectionInfo
    suspend fun fetchCatalog(): BackendCatalog                 // models, LoRAs, samplers, …
    suspend fun refreshModelInfo(catalog, modelId): BackendCatalog
    fun capabilities(catalog, params): ProviderCapabilities    // drives which controls are shown
    fun generate(request): Flow<GenerationEvent>              // Submitted / Queued / Status / Warning / Progress / Preview / Image / Completed
    suspend fun cancel(remoteJobId: String?)
    fun recover(request, remoteJobId): Flow<GenerationEvent>?  // re-attach after process death
    suspend fun loraMetadata(backendName): Map<String, String>?
}
```

Adapters own their wire format completely:

| Adapter | Transport | LoRA application |
|---|---|---|
| `A1111Provider` | Retrofit `sdapi/v1`, progress polling | `<lora:name:w>` prompt syntax |
| `ComfyUiProvider` | OkHttp JSON + WebSocket, `/history` polling | `LoraLoader` nodes (`ComfyWorkflowBuilder`) |
| `HuggingFaceProvider` | HF router per-provider formats, Inference Endpoints | `loras[{path,scale}]` / `lora_weights` |
| `OpenAiImagesProvider` | `/v1/images/generations` | — |
| `CustomRestProvider` | JSON template + JSON-path extraction, async polling | prompt syntax or template variable |

`ProviderCapabilities` is the contract between adapters and the UI: size constraints, step/CFG ranges, samplers, schedulers, batch mode (native/sequential), checkpoint selection (list/free text), VAE + extra component slots, LoRA support kind, cancellation semantics (server vs client-only), progress kind. A control appears only when the capability is present; provider-specific caveats are listed as notes.

### Adding a backend

1. Add a `ProviderType` and (if needed) a `@Serializable @SerialName("…") data class …Settings : ProviderSettings`.
2. Implement `ImageGenerationProvider` (use `ProviderHttp.api` for authenticated calls, `.anonymous` for CDN downloads, `.streaming` for WebSockets).
3. Add a factory and bind it in `di/AppModules.kt`:
   ```kotlin
   @Binds @IntoMap @ProviderTypeKey(ProviderType.MY_BACKEND)
   abstract fun myBackend(factory: MyBackendProviderFactory): ProviderAdapterFactory
   ```
4. Add the settings section to `ProviderEditScreen`.

Nothing else (queue, gallery, LoRA resolution, error handling) changes.

## Generation pipeline

```
GenerateViewModel ──prepare──▶ GenerationPreparer (pure)
   │                             clamp to capabilities · resolve random seed · map library LoRAs to backend names
   │                             (links, filename match, HF weights) · trigger words · compatibility warnings
   ▼
GenerationRepository.enqueue ──▶ Room: generations(status=QUEUED, paramsJson, denormalized prompt/model/seed…)
   │                                     generation_loras (for gallery LoRA filter)
   ▼
GenerationScheduler ──▶ WorkManager unique work "generation-runner-v2" (APPEND_OR_REPLACE, expedited, no network constraint)
   ▼
GenerationWorker (dataSync foreground service) ──▶ GenerationRunner
        recoverInterrupted(): RUNNING rows from a dead process → provider.recover() or FAILED
        runNext() loop: markRunning → provider.generate() events
            Submitted → persist remoteJobId (ComfyUI prompt_id is generated client-side first)
            Progress/Preview/Status/Queued → GenerationProgressBus (in-memory StateFlow, too big for WorkManager data)
            Image → ImageStorage (original bytes + 384px WebP thumbnail) → images row → optional export
            finish → SUCCEEDED / FAILED (+ technical details) / CANCELLED, warnings persisted
        cancel: bus.requestCancel(id) → job coroutine cancelled → provider.cancel(remoteJobId)
```

The UI combines Room flows (queue, latest result, gallery) with the progress bus (step counter, ETA, previews). The notification shows the same progress and offers *Cancel all* (a broadcast receiver → repository).

## Storage

| Data | Where |
|---|---|
| Providers (non-secret), history, images metadata, LoRA library + server links + active stack, prompts, templates, downloads, catalog cache | Room (`unfiltered_studio.db`, schema exported to `app/schemas`) |
| Settings, generation draft | DataStore Preferences |
| Tokens, passwords, secret headers | `SecureCredentialStore`: DataStore file of AES-256-GCM ciphertexts; key in AndroidKeyStore |
| Generated images, thumbnails, img2img inputs, LoRA previews | app-private `filesDir` |
| Downloaded models | `getExternalFilesDir()/models/{loras,checkpoints,vae,…}` |
| HTTP metadata cache (HF/Civitai, 10 min, offline fallback 7 days), Coil image cache (250 MB) | `cacheDir` |

## Networking

- One base `OkHttpClient` (connection pool, UA, debug-only safe logging). Each provider derives a client with the user's timeouts and a `HostScopedHeadersInterceptor` that injects auth only for that provider's hosts.
- `Retry.withBackoff` retries transient failures with exponential backoff + jitter, honours `Retry-After` and Hugging Face `estimated_time`. Non-idempotent generation submits are retried only when the request provably never reached the server.
- `NetworkMonitor` exposes connectivity (LAN-only Wi-Fi counts) for the offline banner and cache fallback.
- The generation worker has **no** WorkManager network constraint: `CONNECTED` means "validated internet" to JobScheduler, which never holds on LAN-only Wi-Fi, behind captive portals or when Google's connectivity check is blocked, and it would stop running jobs when validation flaps. An unreachable server fails the job with a clear error instead. Model downloads (internet only) keep their network constraint.

# Unfiltered AI Studio

A native Android client for open-source image generation on **your own GPU servers or cloud inference** — ComfyUI, Stable Diffusion WebUI / Forge, Hugging Face Inference Providers & Endpoints, OpenAI-compatible image APIs, or any REST API you describe. Built around a real LoRA manager, capability-aware generation settings and a searchable gallery with full metadata.

The phone never runs the model. It's a client: it builds requests in each backend's own format, tracks progress, downloads results and keeps your history.

> **Content:** the app does not filter prompts or outputs. What you can generate depends on the models and providers you connect; hosted providers may enforce their own policies. You are responsible for following applicable laws and model licenses.

---

## Status

| Check | Result |
|---|---|
| `./gradlew assembleDebug` | ✅ builds |
| `./gradlew assembleRelease` (R8 minify + resource shrink) | ✅ builds (≈5 MB unsigned APK) |
| `./gradlew lintDebug` | ✅ no errors (remaining warnings are intentional, see *Security*) |
| `./gradlew testDebugUnitTest` | ✅ 53 passed, 3 skipped (opt-in live ComfyUI test) |
| Run on device / emulator | ⚠️ not done in the build environment — please smoke-test on a device |
| Live servers | ⚠️ adapters are tested against mock servers that reproduce each API's documented wire format (verified against ComfyUI/WebUI source and the official `huggingface.js` client). Run `LiveComfyUiTest` against your own server — see *Testing*. |

## Features

- **Generate** — positive/negative prompt, prompt history, favorites, templates, random prompts, copy/paste, trigger-word chips, embeddings. Aspect presets (1:1, 3:2, 2:3, 4:3, 3:4, 16:9, 9:16, custom 512–2048), steps, CFG, distilled guidance (FLUX), sampler, scheduler, seed (random/manual/reuse), batch 1–4, CLIP skip, hires fix, img2img. **Only parameters the active provider + model actually support are shown.**
- **Providers** — multiple backends, switchable per generation, with live connection status. Each provider has its own adapter; nothing assumes a shared API format.
- **LoRA manager** — library with categories, favorites, search/filters, previews, trigger words, base-model compatibility, default strength and per-server file links. Stack any number of LoRAs with individual strength (and CLIP strength on ComfyUI) and enable toggles. Sync from your server's LoRA folder, import `.safetensors` (metadata + trigger words parsed from the file header), add from Civitai/Hugging Face URLs, look up by SHA-256 on Civitai.
- **Model browser** — Hugging Face and Civitai with pagination, kind filters (checkpoint, LoRA, textual inversion, ControlNet, VAE, image-to-image), base-model filter, sort, NSFW toggle (previews blurred unless enabled). Details show previews, description, license, versions, files with sizes and hashes. **Server** and **On device** tabs keep cloud/server storage and phone storage clearly separate.
- **Downloads** — WorkManager foreground downloads with progress, pause/resume (HTTP Range), retry, SHA-256 verification, Wi-Fi-only option, size check before starting.
- **Queue** — generations run in a foreground worker, survive leaving the app, can be queued, cancelled individually or all at once; ComfyUI jobs are re-attached after the app is killed.
- **Gallery & history** — thumbnail grid (originals are never decoded in grids), full-screen pager with pinch/double-tap zoom, favorites, multi-select save/share/delete, search, filters (model, LoRA, provider, date, favorites), full generation info, reuse settings, regenerate (same seed) or variation (new seed), use as img2img input. History lists failed jobs with errors and retry.
- **Export** — Pictures/Unfiltered AI Studio (MediaStore, no storage permission), or any folder you pick. PNGs get AUTOMATIC1111-style `parameters` metadata.
- **Security** — tokens/passwords encrypted with an AES-256-GCM key in the Android Keystore; never logged; credentials sent only to their own hosts; backups exclude app data.
- **UI** — Material 3 dark charcoal theme with violet/blue accents (AMOLED, light, system, Material You optional); bottom bar on phones, navigation rail on tablets/foldables, two-pane Generate screen on wide displays.

## Requirements

- **Android Studio Quail 4, Rabbit 1 or newer** (AGP 9.4.1). JDK 17+ — the bundled JBR works.
- Android SDK Platform **37**; build-tools are installed automatically.
- Device: **Android 10 (API 29)+**.

## Build

```bash
git clone <this repo>
cd Image-LLM
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest      # unit tests
```

Open the folder in Android Studio and run the `app` configuration. Debug builds install as `com.unfiltered.studio.debug` next to a release build. For a signed release, add a `signingConfig` to `app/build.gradle.kts` (keystores are git-ignored).

## Quick start

1. **Settings → Providers → Add** and pick your backend (details in [docs/PROVIDERS.md](docs/PROVIDERS.md)):
   - **ComfyUI** — `http://<pc-ip>:8188`, start ComfyUI with `--listen 0.0.0.0`.
   - **WebUI / Forge** — `http://<pc-ip>:7860`, start with `--api --listen`.
   - **Hugging Face** — add your token under *Settings → Accounts*, choose *Inference Providers*, default model e.g. `black-forest-labs/FLUX.1-dev`.
2. Tap **Test connection**, then **Save**.
3. On **Generate**: pick a checkpoint (tap ↻ to load the server's list), write a prompt, tap **Generate**.
4. **LoRAs → Sync** imports your server's LoRA folder into the library; add LoRAs to the stack from the Generate screen.

## Architecture (summary)

Single Gradle module with strict layering (`presentation → domain ← data`). See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

```
app/src/main/kotlin/com/unfiltered/studio/
├── core/            AppError (typed, user-facing), JSON tree/path/template helpers, formatters
├── domain/          models, ImageGenerationProvider + ProviderRegistry, repository interfaces, use cases (GenerationPreparer, prompt tools, infotext)
├── data/
│   ├── providers/   a1111/, comfyui/ (+ workflow builder), huggingface/, openai/, custom/ — one adapter per API
│   ├── network/     per-provider OkHttp clients, host-scoped auth, retry/backoff, HF Hub & Civitai Retrofit APIs, connectivity
│   ├── database/    Room entities + DAOs (history, images, LoRAs, links, prompts, downloads, catalog cache)
│   ├── repositories/ implementations; generation/ queue runner, progress bus, exporter; storage/ Keystore secrets, images, PNG/safetensors
│   └── paging/      Hugging Face + Civitai cursor PagingSources
├── workers/         GenerationWorker (foreground queue), ModelDownloadWorker, notifications
├── di/              Hilt modules (provider adapters are a Hilt map — add one line per backend)
└── presentation/    theme, components (zoomable image, pickers…), navigation, screens (generate, models, loras, gallery, settings)
```

**Generation flow:** UI → `GenerationRepository.prepare` (clamps to capabilities, resolves seed + LoRA backend names, warnings) → row in Room (`QUEUED`) → `GenerationWorker` (foreground, unique & appended) → `ImageGenerationProvider.generate()` emits `Submitted/Queued/Progress/Preview/Image/Completed` → images stored + thumbnails + optional export → UI observes Room + in-memory progress bus.

## Testing

```bash
./gradlew testDebugUnitTest
```

- Provider adapters run against MockWebServer with each API's real request/response shapes: A1111 (LoRA prompt syntax, `override_settings`, Forge distilled CFG/modules, progress polling, 422 errors), ComfyUI (client `prompt_id`, polling, `/history` outputs, validation `node_errors`, execution errors, WebSocket text and binary preview frames, legacy + V3 combo formats), Hugging Face (fal.ai queue flow incl. LoRA endpoint switch and credential scoping, HF Inference raw bytes + 503 retry, routing), Custom REST (sync template, Replicate-style async polling), OpenAI-compatible.
- ComfyUI workflow builder: built-in graph wiring (LoRA chain, CLIP skip, VAE, hires, img2img, FLUX guidance) and custom-workflow mapping/LoRA splicing for KSampler and SamplerCustomAdvanced graphs.
- Domain logic, JSON path/templates, error mapping, PNG text chunks, safetensors metadata, HF/Civitai mappers.

**Live ComfyUI check** (no models needed — validates the built-in templates against your server's node definitions and runs an `EmptyImage → SaveImage` round trip over WebSocket and polling):

```bash
COMFY_URL=http://192.168.1.20:8188 ./gradlew :app:testDebugUnitTest --tests '*LiveComfyUiTest*'
```

## Security & privacy

- Secrets live only in `SecureCredentialStore` (AES-256-GCM, non-exportable Android Keystore key, key name bound as AAD). The editor never shows a saved secret; you can only replace or remove it.
- `HostScopedHeadersInterceptor` attaches credentials only to the provider's own hosts. Results fetched from CDNs (fal.media, replicate.delivery…) never receive your tokens; OkHttp also drops `Authorization` on cross-host redirects.
- Debug logging prints method/host/path/status only — no headers, query strings or bodies. Release builds don't log requests.
- `allowBackup=false` and data-extraction rules exclude everything (Keystore keys can't move to another device anyway).
- Cleartext HTTP and user-installed CAs are **intentionally** allowed so LAN/VPN servers and self-signed reverse proxies work; the provider editor warns when credentials would travel over `http://`. Prefer HTTPS or a VPN such as Tailscale for remote access.
- No analytics or telemetry.

## Known limitations

- **LoRA files can't be uploaded to servers** through the ComfyUI or WebUI APIs (neither exposes model upload). The app links library entries to files already on the server, downloads files to the phone and can send them to your PC/server via the share sheet.
- **Hugging Face LoRAs:** stacking with per-LoRA strength works on fal.ai LoRA endpoints (the app switches `fal-ai/flux/dev` → `fal-ai/flux-lora` automatically) and WaveSpeed LoRA endpoints; Replicate `-lora` models take one LoRA; other routes ignore LoRAs (the app warns before generating).
- **ComfyUI built-in templates** cover SD1.x/2.x/SDXL/Pony/Illustrious, SD3/3.5 and FLUX.1 (all-in-one or split files). Other architectures (FLUX.2, Qwen-Image, Chroma, HiDream, Z-Image…) work through a custom workflow exported with *Export (API)*.
- Cancelling on HF Inference / Together / Nscale / OpenAI-compatible APIs only stops waiting (shown in the UI); fal.ai, Replicate, ComfyUI and WebUI cancel server-side.
- UI strings are English and inline (not yet extracted to resources for translation).
- No instrumented/UI tests yet; the UI has not been exercised on a device in the build environment.

## Tech stack

| | |
|---|---|
| Language / UI | Kotlin 2.4.21, Jetpack Compose (BOM 2026.09.00), Material 3 1.4 |
| Build | AGP 9.4.1 (built-in Kotlin), Gradle 9.8.2, KSP 2.3.13, compile/target SDK 37, min SDK 29 |
| Architecture | MVVM, Hilt 2.60.1, Coroutines/Flow, Navigation Compose 2.10 (type-safe routes) |
| Data | Room 2.8.5, DataStore 1.3, WorkManager 2.12, Paging 3.5 |
| Network | Retrofit 3, OkHttp 5.5 (incl. WebSocket), kotlinx.serialization 1.12 |
| Images | Coil 3.6 (memory + 250 MB disk cache) |

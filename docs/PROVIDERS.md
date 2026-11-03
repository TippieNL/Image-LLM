# Connecting providers

Every provider is added under **Settings → Providers → Add**. Use **Test connection** before saving — it runs with the values in the form, so you can fix mistakes without saving a broken provider. One provider is *active* (radio button); switch quickly from the chip at the top of the Generate screen.

Networking basics for self-hosted servers:

- The phone must reach the PC: same Wi-Fi, or a VPN such as **Tailscale/ZeroTier** (recommended for remote use).
- Use the PC's LAN IP (`ipconfig` / `ip a`), not `localhost`.
- Allow the port through the PC firewall.
- Plain `http://` is allowed for LAN servers. If you add authentication, prefer HTTPS (reverse proxy) or a VPN — the app warns when credentials would be sent unencrypted. Self-signed HTTPS works if you install your CA under *Android Settings → Security → Encryption & credentials → Install a certificate*.

---

## ComfyUI

**Server**

```bash
python main.py --listen 0.0.0.0 --port 8188 --preview-method auto
```

`--preview-method auto` enables live previews in the app. Behind a reverse proxy, sub-paths work (`https://host/comfy`).

**App**

| Field | Value |
|---|---|
| Server URL | `http://192.168.1.20:8188` |
| Authentication | None, or Basic/Bearer/API-key header if your proxy requires it |
| Workflow | **Built-in templates** or **Custom workflow** |

**Built-in templates** (choose *Automatic* to pick by architecture):

| Template | Graph | Use for |
|---|---|---|
| Checkpoint | CheckpointLoaderSimple → LoRAs → CLIP skip → KSampler (+ hires: LatentUpscaleBy → KSampler) | SD 1.x/2.x, SDXL, Pony, Illustrious/NoobAI |
| SD3 / SD3.5 | Checkpoint + EmptySD3LatentImage | SD3/3.5 single-file checkpoints |
| FLUX.1 all-in-one | Checkpoint + FluxGuidance + EmptySD3LatentImage (CFG 1) | Comfy-Org fp8 FLUX checkpoints |
| FLUX.1 split files | UNETLoader + DualCLIPLoader(flux) + VAELoader | `diffusion_models/flux1-dev.safetensors`, `t5xxl` + `clip_l`, `ae.safetensors` |

**Custom workflows** — build anything in ComfyUI, then **Workflow → Export (API)** and paste or import the JSON. The app maps parameters automatically:

- prompt/negative: traced from the sampler's `positive`/`negative` (through FluxGuidance, conditioning nodes, guiders) to the text encoder;
- seed (`KSampler`, `KSamplerAdvanced`, `RandomNoise`), steps/scheduler (`BasicScheduler`), sampler (`KSamplerSelect`), CFG (`CFGGuider`), guidance (`FluxGuidance`), size/batch (the latent node), checkpoint/UNET, VAE, `LoadImage` for img2img;
- **LoRAs** are inserted right after the model loader and all consumers are rewired, so any number of LoRAs with separate model/CLIP strength work in any workflow.

For full control, put placeholders in the JSON — they win over auto-mapping:

`{{positive_prompt}} {{negative_prompt}} {{seed}} {{steps}} {{cfg}} {{guidance}} {{width}} {{height}} {{batch_size}} {{sampler}} {{scheduler}} {{denoise}} {{checkpoint}} {{vae}} {{init_image}} {{clip_skip}}`

Write numeric placeholders quoted (`"steps": "{{steps}}"`) — they become numbers. The editor shows which inputs were detected.

**How it runs:** the app generates the `prompt_id` itself and stores it before submitting, opens the WebSocket with its own `clientId` (progress, executing node, previews), and falls back to polling `/history` and `/queue`. If Android kills the app mid-job, the job is picked up again from `/history` on the next start. Cancel deletes the job from the queue or interrupts exactly that `prompt_id`.

**Errors:** validation problems from ComfyUI are shown per node, e.g. `Node 4 (CheckpointLoaderSimple) · ckpt_name: 'x' not in [...]`. Execution errors show the failing node and message (e.g. out of memory).

**LoRAs:** files must be in `ComfyUI/models/loras`. *LoRAs → Sync* creates/links library entries (and reads embedded metadata through `/view_metadata/loras`).

---

## Stable Diffusion WebUI (AUTOMATIC1111) / Forge / reForge

**Server**

```bash
./webui.sh --api --listen            # Windows: set COMMANDLINE_ARGS=--api --listen
# optional: --api-auth user:password  → use "Username + password" in the app
```

**App**

| Field | Value |
|---|---|
| Server URL | `http://192.168.1.20:7860` |
| Server type | Auto-detect (Forge is detected via `/sdapi/v1/sd-modules`) |
| Restore server settings | On = the WebUI's own selected model/VAE stay unchanged after app requests |

**How parameters are sent** (`POST /sdapi/v1/txt2img` or `img2img`):

- checkpoint → `override_settings.sd_model_checkpoint` (empty = the server's current model);
- VAE → `override_settings.sd_vae` (AUTOMATIC1111) — Forge uses **VAE / text encoders** → `forge_additional_modules`;
- CLIP skip → `CLIP_stop_at_last_layers`; FLUX on Forge → `distilled_cfg_scale`;
- hires fix → `enable_hr`, `hr_scale`, `hr_upscaler`, `hr_second_pass_steps`, `denoising_strength`;
- **LoRAs** → `<lora:name:weight>` appended to the prompt (skipped if you already wrote it). Files must be in `models/Lora`; *Sync* reads `/sdapi/v1/loras` including training metadata (base model, trigger words).

Progress and live previews come from `/sdapi/v1/progress` (enable *Settings → Live previews* in the WebUI). Cancel calls `/sdapi/v1/interrupt`.

The txt2img request stays open until all images are done; if large batches or hires fix time out, raise **Settings → Network → Generation timeout**.

---

## Hugging Face

First add your token: **Settings → Accounts → Hugging Face** → *Validate & save*. Create it at <https://huggingface.co/settings/tokens>; fine-grained tokens need **"Make calls to Inference Providers"** (the app tells you if it's missing). Gated models (e.g. FLUX.1-dev) require accepting the license on the model page with the same account.

### Mode: Inference Providers (serverless)

Requests go through `https://router.huggingface.co`, billed to your HF account.

| Field | Value |
|---|---|
| Use my account token | On (default) |
| Route through | *Automatic* (first live provider in your [HF provider order](https://huggingface.co/settings/inference-providers)) or a specific provider |
| Default model | e.g. `black-forest-labs/FLUX.1-dev`, `Tongyi-MAI/Z-Image-Turbo` |

The app reads each model's live provider mapping and uses the right request format per provider:

| Provider | Request | Batch | LoRAs | Cancel |
|---|---|---|---|---|
| fal.ai | queue API (`?_subdomain=queue`), `image_size`, `num_images` | native | many, per-LoRA scale (`fal-ai/flux-lora`) | server |
| Replicate | predictions + `Prefer: wait`, polls when needed | native (`num_outputs`) | one (`lora_weights`) on `-lora` models | server |
| WaveSpeed | `/api/v3/…` + polling | sequential | several on `-lora` endpoints | client |
| Together / Nscale | `/v1/images/generations` | native / sequential | — | client |
| HF Inference | `{inputs, parameters}` → raw image, retries while loading | sequential | — | client |

LoRAs used here must be **Hugging Face LoRA repos** — set *Hugging Face repo* and *weights file* in the LoRA editor, or add the LoRA from the Hugging Face browser. A LoRA repo can also be used directly as the model id.

*Provider safety checker:* fal.ai, Replicate and WaveSpeed run their own output checker by default (flagged images may come back blank — the app tells you). The option *"Ask … to skip their safety checker"* sends the providers' documented flags (`enable_safety_checker=false`, `disable_safety_checker=true`). Providers can still enforce their policies.

### Mode: Dedicated Inference Endpoint

| Field | Value |
|---|---|
| Endpoint URL | `https://xxxx.us-east-1.aws.endpoints.huggingface.cloud` |
| Namespace / Endpoint name | optional — enables state display (running, scaled to zero, paused) and **Resume endpoint** |

The default diffusers handler accepts `{"inputs": prompt, "parameters": {...}}` and returns an image. Scaled-to-zero endpoints return 503 while waking; the app keeps retrying (with status messages) for several minutes.

### Mode: Custom compatible URL

Any endpoint speaking the same `{inputs, parameters}` format (self-hosted diffusers servers, Spaces with an API wrapper…). Responses may be raw images or JSON containing base64/URLs.

---

## OpenAI-compatible Images API

For servers exposing `POST /v1/images/generations` (Together, DeepInfra, LocalAI, many self-hosted gateways).

| Field | Value |
|---|---|
| Server URL | `https://api.together.xyz/v1` (`/v1` is added if missing) |
| Authentication | Bearer token |
| Default model | provider model id |
| Extended parameters | on → also sends `negative_prompt`, `steps`, `seed`, `guidance_scale`, `width`/`height` |
| Extra JSON | merged into every request, e.g. `{"disable_safety_checker": true}` |

---

## Custom REST API

Describe any JSON API without code. Start from a **preset** (Replicate, fal.ai queue, RunPod Serverless, generic sync JSON, raw image bytes) and adjust.

- **Request:** method, path (may contain `{{model}}`), JSON body template with placeholders:
  `{{prompt}} {{negative_prompt}} {{width}} {{height}} {{size}} {{steps}} {{cfg_scale}} {{guidance}} {{seed}} {{batch_size}} {{sampler}} {{scheduler}} {{model}} {{denoise}} {{loras}} {{loras_path_scale}} {{prompt_with_loras}} {{init_image_base64}} {{init_image_data_uri}}`
  Quote numbers (`"{{steps}}"`); force types with `{{seed|str}}`, `{{cfg|float}}`, `{{x|int}}`, `{{x|json}}`.
- **Response:** raw bytes, or JSON with an image path such as `images[*]`, `data[*].b64_json`, `output`, `images[*].url` (values may be base64, data URIs or URLs).
- **Async jobs:** job id path, status URL path (or template like `{{base_url}}/status/{{job_id}}`), status field, success/failure values, optional result URL path, error path, cancel URL + method, poll interval.
- **Capabilities:** tick the parameters your API accepts — only those appear on the Generate screen.
- **LoRAs:** none, A1111 prompt syntax, or the `{{loras}}` / `{{loras_path_scale}}` variables.

Authentication supports Bearer, Basic, API-key header with prefix (e.g. `Authorization: Key …` for fal.ai) and any number of extra headers; mark headers as secret to store them encrypted.

---

## Model browser & downloads

- **Hugging Face / Civitai tabs** browse remote repositories (add your Civitai API key under *Settings → Accounts* — many Civitai downloads require it).
- **On server** lists what the active provider actually has (checkpoints, LoRAs, VAEs, UNET/CLIP files, embeddings) — these are usable for generation.
- **On device** lists files downloaded to the phone. Self-hosted and cloud backends cannot read phone storage: send the file to your PC (share sheet) and place it in the server's model folder, then *Sync*.

Downloads show the size and free space before starting, resume after pauses or network loss, and verify the SHA-256 published by Hugging Face/Civitai.

## Troubleshooting

| Symptom | Fix |
|---|---|
| "Can't reach 192.168.x.x" | Server started with `--listen`? Same network/VPN? Firewall port open? |
| WebUI: 404 on test | Start with `--api`. |
| ComfyUI: "UI-format workflow" | Export with *Workflow → Export (API)*, not *Save*. |
| ComfyUI: "Value not in list" | The checkpoint/LoRA name isn't on that server — refresh the model list or re-sync LoRAs. |
| HF 401 / 403 | Token invalid, missing *Inference Providers* permission, or gated model license not accepted. |
| HF 402 | Inference Providers credits exhausted. |
| Generation timed out (WebUI) | Raise *Settings → Network → Generation timeout*. |
| LoRA "not installed on server" | Copy the file into the server's LoRA folder, then *LoRAs → Sync*, or link it manually in the LoRA editor. |

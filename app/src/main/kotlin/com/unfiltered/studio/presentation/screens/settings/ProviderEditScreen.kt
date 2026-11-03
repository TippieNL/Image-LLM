package com.unfiltered.studio.presentation.screens.settings

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.unfiltered.studio.data.providers.custom.CustomRestPresets
import com.unfiltered.studio.domain.models.A1111Settings
import com.unfiltered.studio.domain.models.A1111Variant
import com.unfiltered.studio.domain.models.AuthType
import com.unfiltered.studio.domain.models.ComfySettings
import com.unfiltered.studio.domain.models.ComfyTemplate
import com.unfiltered.studio.domain.models.ComfyWorkflowMode
import com.unfiltered.studio.domain.models.CustomAsyncConfig
import com.unfiltered.studio.domain.models.CustomLoraMode
import com.unfiltered.studio.domain.models.CustomResponseMode
import com.unfiltered.studio.domain.models.CustomRestSettings
import com.unfiltered.studio.domain.models.HfMode
import com.unfiltered.studio.domain.models.HfRouterProviders
import com.unfiltered.studio.domain.models.HuggingFaceSettings
import com.unfiltered.studio.domain.models.OpenAiImagesSettings
import com.unfiltered.studio.domain.models.OpenAiResponseFormat
import com.unfiltered.studio.domain.models.ProviderType
import com.unfiltered.studio.presentation.components.BannerTone
import com.unfiltered.studio.presentation.components.ConfirmDialog
import com.unfiltered.studio.presentation.components.ErrorPanel
import com.unfiltered.studio.presentation.components.InfoBanner
import com.unfiltered.studio.presentation.components.SecretField
import com.unfiltered.studio.presentation.components.SectionCard
import com.unfiltered.studio.presentation.components.SelectField
import com.unfiltered.studio.presentation.theme.LocalExtendedColors
import com.unfiltered.studio.presentation.theme.MonoStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ProviderEditScreen(onBack: () -> Unit, viewModel: ProviderEditViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val accounts by viewModel.accountState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { viewModel.messages.collect { snackbar.showSnackbar(it) } }
    var confirmDelete by remember { mutableStateOf(false) }
    val config = state.config

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (viewModel.isNew) "Add ${config.type.displayName}" else "Edit provider") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    if (!viewModel.isNew) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, "Delete provider") }
                    TextButton(onClick = { viewModel.save(onBack) }, enabled = !state.saving) { Text(if (state.saving) "Saving…" else "Save") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (!state.loaded) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SectionCard(title = "Connection") {
                OutlinedTextField(config.name, { v -> viewModel.update { it.copy(name = v) } }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                val hf = config.settings as? HuggingFaceSettings
                if (config.type == ProviderType.HUGGING_FACE) {
                    SelectField("Mode", HfMode.entries.toList(), hf?.mode ?: HfMode.INFERENCE_PROVIDERS, viewModel::setHfMode, { it.displayName }, itemSupporting = { it.description })
                }
                if (!(config.type == ProviderType.HUGGING_FACE && hf?.mode == HfMode.INFERENCE_PROVIDERS)) {
                    OutlinedTextField(
                        config.baseUrl,
                        { v -> viewModel.update { it.copy(baseUrl = v.trim()) } },
                        label = { Text(if (config.type == ProviderType.HUGGING_FACE) "Endpoint URL" else "Server URL") },
                        placeholder = { Text(urlHint(config.type)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                        supportingText = { Text(urlSupport(config.type)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (config.baseUrl.startsWith("http://") && config.auth.type != AuthType.NONE) {
                    InfoBanner("Credentials will be sent unencrypted over http://. Prefer https, a VPN (e.g. Tailscale) or a trusted LAN.", tone = BannerTone.WARNING)
                }
            }

            AuthSection(state, accounts.hasHfToken, viewModel)

            when (val s = config.settings) {
                is A1111Settings -> A1111Section(s, viewModel)
                is ComfySettings -> ComfySection(s, state, viewModel)
                is HuggingFaceSettings -> HfSection(s, viewModel, !viewModel.isNew)
                is OpenAiImagesSettings -> OpenAiSection(s, viewModel)
                is CustomRestSettings -> CustomSection(s, viewModel)
            }

            SectionCard(title = "Test") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = viewModel::test, enabled = !state.testing) {
                        if (state.testing) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Text("Test connection")
                    }
                    Spacer(Modifier.width(10.dp))
                    Text("Uses the values above without saving.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                state.testResult?.let { info ->
                    Surface(shape = MaterialTheme.shapes.medium, color = LocalExtendedColors.current.success.copy(alpha = 0.12f)) {
                        Column(Modifier.fillMaxWidth().padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.CheckCircle, null, tint = LocalExtendedColors.current.success)
                                Spacer(Modifier.width(8.dp))
                                Text(listOfNotNull(info.serverName, info.version).joinToString(" "), style = MaterialTheme.typography.titleSmall)
                            }
                            info.details.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
                state.testError?.let { ErrorPanel(it) }
            }
            Spacer(Modifier.size(24.dp))
        }
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = "Delete ${config.name}?",
            text = "Its saved credentials are erased. Your images and history are kept.",
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = { viewModel.delete(onBack) },
            onDismiss = { confirmDelete = false },
        )
    }
}

private fun urlHint(type: ProviderType) = when (type) {
    ProviderType.A1111 -> "http://192.168.1.20:7860"
    ProviderType.COMFYUI -> "http://192.168.1.20:8188"
    ProviderType.HUGGING_FACE -> "https://xxxx.endpoints.huggingface.cloud"
    ProviderType.OPENAI_COMPATIBLE -> "https://api.example.com/v1"
    ProviderType.CUSTOM_REST -> "https://api.example.com"
}

private fun urlSupport(type: ProviderType) = when (type) {
    ProviderType.A1111 -> "Start WebUI/Forge with --api (and --listen for LAN access)."
    ProviderType.COMFYUI -> "Start ComfyUI with --listen 0.0.0.0 for LAN access. Reverse-proxy subpaths are supported."
    ProviderType.HUGGING_FACE -> "The full endpoint URL."
    ProviderType.OPENAI_COMPATIBLE -> "Base URL; /v1/images/generations is appended."
    ProviderType.CUSTOM_REST -> "Base URL; the request path is set below."
}

@Composable
private fun AuthSection(state: ProviderEditorState, hasAccountToken: Boolean, viewModel: ProviderEditViewModel) {
    val auth = state.config.auth
    val isHf = state.config.type == ProviderType.HUGGING_FACE
    SectionCard(title = "Authentication", subtitle = "Secrets are encrypted with the Android Keystore and never shown again") {
        if (isHf) {
            ToggleRow("Use my Hugging Face account token", auth.useAccountToken, if (hasAccountToken) "Set in Settings → Accounts" else "No account token saved yet — add one in Settings → Accounts") { v ->
                viewModel.updateAuth { it.copy(useAccountToken = v, type = AuthType.BEARER) }
            }
        }
        if (!isHf || !auth.useAccountToken) {
            SelectField("Method", AuthType.entries.toList(), auth.type, { t -> viewModel.updateAuth { it.copy(type = t) } }, { it.displayName })
            when (auth.type) {
                AuthType.NONE -> Unit
                AuthType.BEARER -> SecretField("Token", state.token, viewModel::setToken, state.hasSavedToken && !state.clearToken, onClear = viewModel::clearToken)
                AuthType.BASIC -> {
                    OutlinedTextField(auth.username.orEmpty(), { v -> viewModel.updateAuth { it.copy(username = v) } }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    SecretField("Password", state.token, viewModel::setToken, state.hasSavedToken && !state.clearToken, onClear = viewModel::clearToken, supporting = "For --api-auth user:pass or a reverse proxy.")
                }
                AuthType.API_KEY_HEADER -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(auth.headerName.orEmpty(), { v -> viewModel.updateAuth { it.copy(headerName = v) } }, label = { Text("Header name") }, placeholder = { Text("X-API-Key") }, singleLine = true, modifier = Modifier.weight(1f))
                        OutlinedTextField(auth.headerValuePrefix.orEmpty(), { v -> viewModel.updateAuth { it.copy(headerValuePrefix = v.ifEmpty { null }) } }, label = { Text("Value prefix") }, placeholder = { Text("e.g. Key ") }, singleLine = true, modifier = Modifier.weight(1f))
                    }
                    SecretField("API key", state.token, viewModel::setToken, state.hasSavedToken && !state.clearToken, onClear = viewModel::clearToken)
                }
            }
        }
        Text("Extra headers", style = MaterialTheme.typography.labelLarge)
        auth.extraHeaders.forEachIndexed { index, header ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(header.name, { v -> viewModel.updateHeader(index, header.copy(name = v)) }, label = { Text("Name") }, singleLine = true, modifier = Modifier.weight(1f))
                    IconButton(onClick = { viewModel.removeHeader(index) }) { Icon(Icons.Default.Close, "Remove header") }
                }
                if (header.secret) {
                    SecretField("Value (secret)", state.headerValues[header.name].orEmpty(), { viewModel.setHeaderValue(header.name, it) }, header.name in state.savedHeaderSecrets)
                } else {
                    OutlinedTextField(header.value, { v -> viewModel.updateHeader(index, header.copy(value = v)) }, label = { Text("Value") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                ToggleRow("Treat as secret", header.secret) { v -> viewModel.updateHeader(index, header.copy(secret = v, value = if (v) "" else header.value)) }
            }
        }
        OutlinedButton(onClick = viewModel::addHeader) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("Add header") }
    }
}

@Composable
private fun A1111Section(s: A1111Settings, viewModel: ProviderEditViewModel) {
    SectionCard(title = "WebUI / Forge options") {
        SelectField("Server type", A1111Variant.entries.toList(), s.variant, { v -> viewModel.updateSettings { s.copy(variant = v) } }, { it.displayName }, supporting = "Auto-detect checks for Forge's /sdapi/v1/sd-modules.")
        OutlinedTextField(s.defaultCheckpoint.orEmpty(), { v -> viewModel.updateSettings { s.copy(defaultCheckpoint = v.ifBlank { null }) } }, label = { Text("Default checkpoint (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        ToggleRow("Restore server settings after each request", s.restoreSettingsAfterwards, "Keeps the WebUI's own selected model/VAE unchanged") { v -> viewModel.updateSettings { s.copy(restoreSettingsAfterwards = v) } }
        ToggleRow("Live previews while generating", s.livePreview, "Requires \"Show live previews\" in the WebUI settings") { v -> viewModel.updateSettings { s.copy(livePreview = v) } }
        ToggleRow("Also save images on the server", s.saveImagesOnServer) { v -> viewModel.updateSettings { s.copy(saveImagesOnServer = v) } }
    }
}

@Composable
private fun ComfySection(s: ComfySettings, state: ProviderEditorState, viewModel: ProviderEditViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val importJson = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val (text, name) = withContext(Dispatchers.IO) {
                val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                (context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } ?: "") to name
            }
            viewModel.setWorkflowJson(text, name)
        }
    }
    SectionCard(title = "ComfyUI workflow") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(s.workflowMode == ComfyWorkflowMode.BUILT_IN, { viewModel.updateSettings { s.copy(workflowMode = ComfyWorkflowMode.BUILT_IN) } }, { Text("Built-in templates") })
            FilterChip(s.workflowMode == ComfyWorkflowMode.CUSTOM, { viewModel.updateSettings { s.copy(workflowMode = ComfyWorkflowMode.CUSTOM) } }, { Text("Custom workflow") })
        }
        if (s.workflowMode == ComfyWorkflowMode.BUILT_IN) {
            SelectField("Template", ComfyTemplate.entries.toList(), s.template, { t -> viewModel.updateSettings { s.copy(template = t) } }, { it.displayName }, itemSupporting = { it.description })
            OutlinedTextField(s.defaultCheckpoint.orEmpty(), { v -> viewModel.updateSettings { s.copy(defaultCheckpoint = v.ifBlank { null }) } }, label = { Text("Default checkpoint (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        } else {
            InfoBanner("In ComfyUI: Workflow → Export (API). Prompts, seed, steps, CFG, size, sampler and checkpoint are mapped automatically; add {{placeholders}} for full control. LoRAs are inserted after the model loader.")
            OutlinedButton(onClick = { importJson.launch(arrayOf("application/json", "text/plain", "*/*")) }) { Icon(Icons.Default.FileOpen, null); Spacer(Modifier.width(6.dp)); Text("Import workflow file") }
            OutlinedTextField(
                value = s.customWorkflowJson.orEmpty(),
                onValueChange = { viewModel.setWorkflowJson(it, s.customWorkflowName) },
                label = { Text("Workflow (API format JSON)") },
                textStyle = MonoStyle,
                minLines = 6,
                maxLines = 14,
                modifier = Modifier.fillMaxWidth(),
            )
            state.workflowError?.let { InfoBanner(it, tone = BannerTone.ERROR) }
            state.analysis?.let { a ->
                Text("Detected inputs", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        "Prompt" to a.positiveText, "Negative" to a.negativeText, "Size" to a.size, "Batch" to a.batch, "Seed" to a.seed,
                        "Steps" to a.steps, "CFG" to a.cfg, "Sampler" to a.sampler, "Scheduler" to a.scheduler, "Guidance" to a.guidance,
                        "Checkpoint" to a.checkpoint, "LoRA injection" to a.loraInjection, "img2img" to a.img2img,
                    ).forEach { (label, ok) ->
                        AssistChip(onClick = {}, label = { Text(label) }, leadingIcon = { Icon(if (ok) Icons.Default.CheckCircle else Icons.Default.Close, null, Modifier.size(16.dp), tint = if (ok) LocalExtendedColors.current.success else MaterialTheme.colorScheme.outline) })
                    }
                }
                Text("${a.outputs} image output node(s)" + if (a.placeholders.isNotEmpty()) " · placeholders: ${a.placeholders.joinToString()}" else "", style = MaterialTheme.typography.bodySmall)
                a.problems.forEach { InfoBanner(it, tone = BannerTone.WARNING) }
            }
        }
        ToggleRow("Live progress & previews (WebSocket)", s.useWebSocket, "Falls back to polling when disabled or unavailable. Start ComfyUI with --preview-method auto for previews.") { v -> viewModel.updateSettings { s.copy(useWebSocket = v) } }
        OutlinedTextField(s.filenamePrefix, { v -> viewModel.updateSettings { s.copy(filenamePrefix = v.ifBlank { "UnfilteredAI" }) } }, label = { Text("Server filename prefix") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun HfSection(s: HuggingFaceSettings, viewModel: ProviderEditViewModel, saved: Boolean) {
    SectionCard(title = "Hugging Face options") {
        if (s.mode == HfMode.INFERENCE_PROVIDERS) {
            val options = listOf(HfRouterProviders.AUTO) + HfRouterProviders.supported
            SelectField("Route through", options, s.routingProvider, { v -> viewModel.updateSettings { s.copy(routingProvider = v) } }, { HfRouterProviders.displayName(it) },
                supporting = "Automatic uses the first live provider in your HF provider order.")
            OutlinedTextField(s.defaultModelId.orEmpty(), { v -> viewModel.updateSettings { s.copy(defaultModelId = v.trim().ifBlank { null }) } }, label = { Text("Default model") }, placeholder = { Text("black-forest-labs/FLUX.1-dev") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            ToggleRow(
                "Ask fal.ai / Replicate / WaveSpeed to skip their safety checker",
                s.disableProviderSafetyChecker,
                "Sends the provider's documented flag (enable_safety_checker=false / disable_safety_checker=true). Providers may still enforce their own policies.",
            ) { v -> viewModel.updateSettings { s.copy(disableProviderSafetyChecker = v) } }
            InfoBanner("Billing is handled by Hugging Face (Inference Providers credits). Multiple LoRAs with custom strengths work on fal.ai's LoRA endpoints; other routes ignore LoRAs.")
        } else {
            if (s.mode == HfMode.DEDICATED_ENDPOINT) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(s.endpointNamespace.orEmpty(), { v -> viewModel.updateSettings { s.copy(endpointNamespace = v.trim().ifBlank { null }) } }, label = { Text("Namespace") }, placeholder = { Text("your-username") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(s.endpointName.orEmpty(), { v -> viewModel.updateSettings { s.copy(endpointName = v.trim().ifBlank { null }) } }, label = { Text("Endpoint name") }, singleLine = true, modifier = Modifier.weight(1f))
                }
                Text("Optional: lets the app show the endpoint state (running / scaled to zero / paused) and resume it.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (saved && s.endpointName != null) OutlinedButton(onClick = viewModel::resumeEndpoint) { Text("Resume endpoint") }
            }
            InfoBanner("The endpoint must accept {\"inputs\": prompt, \"parameters\": {…}} and return an image (default diffusers handler) or JSON with base64.")
        }
    }
}

@Composable
private fun OpenAiSection(s: OpenAiImagesSettings, viewModel: ProviderEditViewModel) {
    SectionCard(title = "OpenAI-compatible options") {
        OutlinedTextField(s.defaultModel.orEmpty(), { v -> viewModel.updateSettings { s.copy(defaultModel = v.ifBlank { null }) } }, label = { Text("Default model id") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        ToggleRow("Send extended parameters", s.sendExtendedParams, "negative_prompt, steps, seed, guidance_scale, width/height") { v -> viewModel.updateSettings { s.copy(sendExtendedParams = v) } }
        ToggleRow("Server supports n > 1", s.supportsBatch) { v -> viewModel.updateSettings { s.copy(supportsBatch = v) } }
        SelectField("Response format", OpenAiResponseFormat.entries.toList(), s.responseFormat, { v -> viewModel.updateSettings { s.copy(responseFormat = v) } }, { if (it == OpenAiResponseFormat.B64_JSON) "b64_json" else "url" })
        OutlinedTextField(s.extraJson.orEmpty(), { v -> viewModel.updateSettings { s.copy(extraJson = v.ifBlank { null }) } }, label = { Text("Extra JSON merged into the body") }, placeholder = { Text("{\"disable_safety_checker\": true}") }, textStyle = MonoStyle, minLines = 2, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun CustomSection(s: CustomRestSettings, viewModel: ProviderEditViewModel) {
    SectionCard(title = "Presets") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            CustomRestPresets.all.forEach { preset -> AssistChip(onClick = { viewModel.applyPreset(preset) }, label = { Text(preset.name) }) }
        }
    }
    SectionCard(title = "Request") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SelectField("Method", listOf("POST", "PUT", "GET"), s.method, { v -> viewModel.updateSettings { s.copy(method = v) } }, { it }, Modifier.width(120.dp))
            OutlinedTextField(s.path, { v -> viewModel.updateSettings { s.copy(path = v) } }, label = { Text("Path") }, placeholder = { Text("/v1/generate") }, singleLine = true, modifier = Modifier.weight(1f))
        }
        OutlinedTextField(
            s.bodyTemplate,
            { v -> viewModel.updateSettings { s.copy(bodyTemplate = v) } },
            label = { Text("JSON body template") },
            textStyle = MonoStyle,
            minLines = 6,
            maxLines = 16,
            supportingText = { Text("Variables: {{prompt}} {{negative_prompt}} {{width}} {{height}} {{size}} {{steps}} {{cfg_scale}} {{guidance}} {{seed}} {{batch_size}} {{sampler}} {{model}} {{loras}} {{loras_path_scale}} {{prompt_with_loras}} {{init_image_base64}} {{init_image_data_uri}} {{denoise}}. Quote numbers: \"{{steps}}\" → 30. Casts: {{seed|str}}.") },
            modifier = Modifier.fillMaxWidth(),
        )
    }
    SectionCard(title = "Response") {
        SelectField("Response type", CustomResponseMode.entries.toList(), s.responseMode, { v -> viewModel.updateSettings { s.copy(responseMode = v) } }, { it.displayName })
        if (s.responseMode == CustomResponseMode.JSON) {
            OutlinedTextField(s.imagePath, { v -> viewModel.updateSettings { s.copy(imagePath = v) } }, label = { Text("Image JSON path") }, placeholder = { Text("images[*] · data[*].b64_json · output") }, singleLine = true, textStyle = MonoStyle, modifier = Modifier.fillMaxWidth())
        }
        ToggleRow("Asynchronous job (submit, then poll)", s.async != null) { v -> viewModel.updateSettings { s.copy(async = if (v) (s.async ?: CustomAsyncConfig()) else null) } }
        s.async?.let { a ->
            fun upd(t: (CustomAsyncConfig) -> CustomAsyncConfig) = viewModel.updateSettings { s.copy(async = t(a)) }
            PathField("Job id path", a.jobIdPath) { v -> upd { it.copy(jobIdPath = v) } }
            PathField("Status URL path (in submit response)", a.statusUrlPath.orEmpty()) { v -> upd { it.copy(statusUrlPath = v.ifBlank { null }) } }
            PathField("…or status URL template", a.statusUrlTemplate.orEmpty()) { v -> upd { it.copy(statusUrlTemplate = v.ifBlank { null }) } }
            PathField("Status field path", a.statusPath) { v -> upd { it.copy(statusPath = v) } }
            PathField("Success values (comma separated)", a.successValues.joinToString(",")) { v -> upd { it.copy(successValues = v.split(',').map { x -> x.trim() }.filter { x -> x.isNotEmpty() }) } }
            PathField("Failure values", a.failureValues.joinToString(",")) { v -> upd { it.copy(failureValues = v.split(',').map { x -> x.trim() }.filter { x -> x.isNotEmpty() }) } }
            PathField("Result URL path (optional)", a.resultUrlPath.orEmpty()) { v -> upd { it.copy(resultUrlPath = v.ifBlank { null }) } }
            PathField("Error message path", a.errorPath.orEmpty()) { v -> upd { it.copy(errorPath = v.ifBlank { null }) } }
            PathField("Cancel URL path or template", a.cancelUrlPath ?: a.cancelUrlTemplate.orEmpty()) { v ->
                upd { if (v.contains("{{")) it.copy(cancelUrlTemplate = v.ifBlank { null }, cancelUrlPath = null) else it.copy(cancelUrlPath = v.ifBlank { null }, cancelUrlTemplate = null) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SelectField("Cancel method", listOf("POST", "PUT", "DELETE", "GET"), a.cancelMethod, { v -> upd { it.copy(cancelMethod = v) } }, { it }, Modifier.weight(1f))
                OutlinedTextField(a.pollIntervalMs.toString(), { v -> v.toLongOrNull()?.let { ms -> upd { it.copy(pollIntervalMs = ms) } } }, label = { Text("Poll ms") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
            }
        }
    }
    SectionCard(title = "Capabilities", subtitle = "Only checked parameters are shown on the Generate screen") {
        ToggleRow("Negative prompt", s.supportsNegativePrompt) { v -> viewModel.updateSettings { s.copy(supportsNegativePrompt = v) } }
        ToggleRow("Width / height", s.supportsSize) { v -> viewModel.updateSettings { s.copy(supportsSize = v) } }
        ToggleRow("Steps", s.supportsSteps) { v -> viewModel.updateSettings { s.copy(supportsSteps = v) } }
        ToggleRow("CFG / guidance", s.supportsCfg) { v -> viewModel.updateSettings { s.copy(supportsCfg = v) } }
        ToggleRow("Seed", s.supportsSeed) { v -> viewModel.updateSettings { s.copy(supportsSeed = v) } }
        ToggleRow("Native batch ({{batch_size}})", s.supportsBatch, "Off = one request per image") { v -> viewModel.updateSettings { s.copy(supportsBatch = v) } }
        ToggleRow("Model id ({{model}})", s.supportsModel) { v -> viewModel.updateSettings { s.copy(supportsModel = v) } }
        ToggleRow("Sampler ({{sampler}})", s.supportsSampler) { v -> viewModel.updateSettings { s.copy(supportsSampler = v) } }
        if (s.supportsSampler) {
            OutlinedTextField(s.samplerOptions.joinToString(", "), { v -> viewModel.updateSettings { s.copy(samplerOptions = v.split(',').map { it.trim() }.filter { it.isNotEmpty() }) } }, label = { Text("Sampler options") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        SelectField("LoRAs", CustomLoraMode.entries.toList(), s.loraMode, { v -> viewModel.updateSettings { s.copy(loraMode = v) } }, { it.displayName })
    }
}

@Composable
private fun PathField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, label = { Text(label) }, singleLine = true, textStyle = MonoStyle, modifier = Modifier.fillMaxWidth())
}

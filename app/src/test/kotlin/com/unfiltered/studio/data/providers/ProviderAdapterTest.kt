package com.unfiltered.studio.data.providers

import com.unfiltered.studio.core.AppError
import com.unfiltered.studio.core.AppJson
import com.unfiltered.studio.core.arr
import com.unfiltered.studio.core.doubleValueOrNull
import com.unfiltered.studio.core.longValueOrNull
import com.unfiltered.studio.core.obj
import com.unfiltered.studio.core.str
import com.unfiltered.studio.data.providers.a1111.A1111Provider
import com.unfiltered.studio.data.providers.comfyui.ComfyUiProvider
import com.unfiltered.studio.data.providers.comfyui.ComfyWsEvent
import com.unfiltered.studio.data.providers.custom.CustomRestPresets
import com.unfiltered.studio.data.providers.custom.CustomRestProvider
import com.unfiltered.studio.data.providers.huggingface.HuggingFaceProvider
import com.unfiltered.studio.data.providers.openai.OpenAiImagesProvider
import com.unfiltered.studio.domain.models.A1111Settings
import com.unfiltered.studio.domain.models.A1111Variant
import com.unfiltered.studio.domain.models.BackendCatalog
import com.unfiltered.studio.domain.models.ComfySettings
import com.unfiltered.studio.domain.models.CustomRestSettings
import com.unfiltered.studio.domain.models.GenerationEvent
import com.unfiltered.studio.domain.models.GenerationParameters
import com.unfiltered.studio.domain.models.GenerationRequest
import com.unfiltered.studio.domain.models.HfModelInfo
import com.unfiltered.studio.domain.models.HfProviderMapping
import com.unfiltered.studio.domain.models.HuggingFaceSettings
import com.unfiltered.studio.domain.models.LoraSelection
import com.unfiltered.studio.domain.models.LoraSupport
import com.unfiltered.studio.domain.models.ModelArchitecture
import com.unfiltered.studio.domain.models.OpenAiImagesSettings
import com.unfiltered.studio.domain.models.ProviderType
import com.unfiltered.studio.testutil.RoutingDispatcher
import com.unfiltered.studio.testutil.TestHttp
import com.unfiltered.studio.testutil.TestHttp.bodyJson
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

abstract class MockServerTest {
    protected lateinit var server: MockWebServer

    @Before fun startServer() {
        server = MockWebServer().apply { start() }
    }

    @After fun stopServer() {
        server.shutdown()
    }

    protected val baseUrl: String get() = server.url("/").toString().trimEnd('/')

    protected fun request(params: GenerationParameters) = GenerationRequest("gen-1", "provider-1", params)

    protected suspend fun expectFailure(block: suspend () -> Unit): Throwable {
        try {
            block()
        } catch (e: Throwable) {
            return e
        }
        fail("Expected an exception")
        throw IllegalStateException()
    }
}

class A1111ProviderTest : MockServerTest() {

    private fun info(seeds: List<Long>) = AppJson.encodeToString(
        JsonObject.serializer(),
        AppJson.parseToJsonElement("""{"all_seeds": ${seeds}, "infotexts": ["one", "two"], "index_of_first_image": 0, "sd_model_name": "dreamshaper"}""") as JsonObject,
    )

    private fun dispatcher(forge: Boolean) = RoutingDispatcher { r ->
        when {
            r.path!!.startsWith("/sdapi/v1/sd-modules") -> if (forge) TestHttp.json("""[{"model_name":"ae","filename":"/m/VAE/ae.safetensors"}]""") else null
            r.path!!.startsWith("/sdapi/v1/progress") -> TestHttp.json("""{"progress":0.5,"eta_relative":3.0,"state":{"job_count":1,"job_no":0,"sampling_step":10,"sampling_steps":20},"current_image":"${TestHttp.PNG_B64}"}""")
            r.path == "/sdapi/v1/txt2img" -> TestHttp.json(
                AppJson.encodeToString(
                    JsonObject.serializer(),
                    kotlinx.serialization.json.buildJsonObject {
                        put("images", kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive(TestHttp.PNG_B64), kotlinx.serialization.json.JsonPrimitive(TestHttp.PNG_B64))))
                        put("info", kotlinx.serialization.json.JsonPrimitive(info(listOf(111, 112))))
                    },
                ),
            ).setBodyDelay(1300, TimeUnit.MILLISECONDS)
            else -> null
        }
    }

    @Test fun txt2imgSendsLoraSyntaxOverridesAndParsesSeeds() = runTest {
        val routes = dispatcher(forge = false)
        server.dispatcher = routes
        val provider = A1111Provider(TestHttp.config(ProviderType.A1111, baseUrl, A1111Settings()), TestHttp.http(TestHttp.client()))
        val params = GenerationParameters(
            prompt = "portrait", negativePrompt = "blurry", width = 832, height = 1216, steps = 20, cfgScale = 6f, seed = 111,
            sampler = "DPM++ 2M", scheduler = "Karras", batchSize = 2, clipSkip = 2, checkpoint = "dreamshaper.safetensors [abc]",
            architecture = ModelArchitecture.SDXL,
            loras = listOf(LoraSelection(name = "Detail", backendName = "detail_tweaker", strength = 0.75f)),
        )
        val events = provider.generate(request(params)).toList()

        val body = AppJson.parseToJsonElement(routes.bodies[routes.requests.indexOfFirst { it.path == "/sdapi/v1/txt2img" }]) as JsonObject
        assertEquals("portrait <lora:detail_tweaker:0.75>", body.str("prompt"))
        assertEquals("DPM++ 2M", body.str("sampler_name"))
        assertEquals("Karras", body.str("scheduler"))
        assertEquals(2L, body["batch_size"].longValueOrNull)
        assertEquals("dreamshaper.safetensors [abc]", body.obj("override_settings")!!.str("sd_model_checkpoint"))
        assertEquals(2L, body.obj("override_settings")!!["CLIP_stop_at_last_layers"].longValueOrNull)
        assertFalse(body.containsKey("distilled_cfg_scale"))

        val images = events.filterIsInstance<GenerationEvent.Image>()
        assertEquals(listOf(111L, 112L), images.map { it.image.seed })
        assertArrayEquals(TestHttp.PNG, images.first().image.bytes)
        assertTrue(events.any { it is GenerationEvent.Progress && it.step == 10 && it.totalSteps == 20 })
        assertTrue(events.any { it is GenerationEvent.Preview })
        assertTrue(events.last() is GenerationEvent.Completed)
    }

    @Test fun forgeGetsDistilledCfgAndAdditionalModules() = runTest {
        val routes = dispatcher(forge = true)
        server.dispatcher = routes
        val provider = A1111Provider(TestHttp.config(ProviderType.A1111, baseUrl, A1111Settings(variant = A1111Variant.AUTO)), TestHttp.http(TestHttp.client()))
        val params = GenerationParameters(
            prompt = "x", architecture = ModelArchitecture.FLUX1, distilledGuidance = 3.5f, cfgScale = 1f, vae = "ignored-on-forge",
            components = mapOf(A1111Provider.SLOT_FORGE_MODULES to "/m/VAE/ae.safetensors\n/m/text_encoder/t5xxl.safetensors"),
        )
        provider.generate(request(params)).toList()
        val body = AppJson.parseToJsonElement(routes.bodies[routes.requests.indexOfFirst { it.path == "/sdapi/v1/txt2img" }]) as JsonObject
        assertEquals(3.5, body["distilled_cfg_scale"].doubleValueOrNull!!, 0.01)
        val overrides = body.obj("override_settings")!!
        assertEquals(2, overrides.arr("forge_additional_modules")!!.size)
        assertFalse(overrides.containsKey("sd_vae"))
        assertFalse(overrides.containsKey("CLIP_stop_at_last_layers"))
    }

    @Test fun validationErrorsAreReadable() = runTest {
        server.dispatcher = RoutingDispatcher { r ->
            when {
                r.path!!.startsWith("/sdapi/v1/txt2img") -> TestHttp.json("""{"detail":[{"loc":["body","steps"],"msg":"value is not a valid integer"}]}""", 422)
                else -> null
            }
        }
        val provider = A1111Provider(TestHttp.config(ProviderType.A1111, baseUrl, A1111Settings(variant = A1111Variant.AUTOMATIC1111)), TestHttp.http(TestHttp.client()))
        val error = expectFailure { provider.generate(request(GenerationParameters(prompt = "x"))).toList() }
        assertTrue(error is AppError.Validation)
        assertTrue((error as AppError).technicalDetails!!.contains("steps"))
    }

    @Test fun capabilitiesFollowServerAndArchitecture() {
        val provider = A1111Provider(TestHttp.config(ProviderType.A1111, baseUrl, A1111Settings()), TestHttp.http(TestHttp.client()))
        val catalog = BackendCatalog("provider-1", 0, samplers = listOf("Euler a"), upscalers = listOf("Latent", "R-ESRGAN 4x+"), flags = setOf(BackendCatalog.FLAG_FORGE))
        val flux = provider.capabilities(catalog, GenerationParameters(architecture = ModelArchitecture.FLUX1))
        assertTrue(flux.distilledGuidance)
        assertFalse(flux.clipSkip)
        assertEquals(null, flux.vaes)
        val sdxl = provider.capabilities(catalog.copy(flags = emptySet()), GenerationParameters(architecture = ModelArchitecture.SDXL))
        assertTrue(sdxl.clipSkip)
        assertNotNull(sdxl.vaes)
        assertEquals(LoraSupport.PromptSyntax, sdxl.loras)
    }
}

class ComfyUiProviderTest : MockServerTest() {

    private fun comfy(settings: ComfySettings = ComfySettings(useWebSocket = false)) =
        ComfyUiProvider(TestHttp.config(ProviderType.COMFYUI, baseUrl, settings), TestHttp.http(TestHttp.client()), pollIntervalMs = 50)

    @Test fun submitsWithClientPromptIdPollsHistoryAndDownloadsOutputs() = runTest {
        var promptId = ""
        val historyCalls = AtomicInteger()
        val routes = RoutingDispatcher { r ->
            val path = r.path!!
            when {
                path == "/prompt" -> {
                    promptId = (AppJson.parseToJsonElement(r.body.clone().readUtf8()) as JsonObject).str("prompt_id")!!
                    TestHttp.json("""{"prompt_id":"$promptId","number":1,"node_errors":{}}""")
                }
                path.startsWith("/history/") -> if (historyCalls.incrementAndGet() < 3) TestHttp.json("{}") else TestHttp.json(
                    """{"$promptId":{"status":{"status_str":"success","completed":true,"messages":[]},
                       "outputs":{"9":{"images":[{"filename":"UnfilteredAI_00001_.png","subfolder":"","type":"output"}]},
                                  "12":{"images":[{"filename":"preview.png","subfolder":"","type":"temp"}]}}}}""",
                )
                path.startsWith("/queue") -> TestHttp.json("""{"queue_running":[[1,"$promptId",{},{},[]]],"queue_pending":[]}""")
                path.startsWith("/view?") -> TestHttp.png()
                else -> null
            }
        }
        server.dispatcher = routes
        val events = comfy().generate(
            request(GenerationParameters(prompt = "a fox", checkpoint = "sdxl.safetensors", seed = 42, loras = listOf(LoraSelection(name = "L", backendName = "l.safetensors")))),
        ).toList()

        val submitted = events.filterIsInstance<GenerationEvent.Submitted>().single()
        assertEquals(promptId, submitted.remoteJobId)
        assertTrue(Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$").matches(promptId))
        val body = AppJson.parseToJsonElement(routes.bodies[routes.requests.indexOfFirst { it.path == "/prompt" }]) as JsonObject
        assertNotNull(body.str("client_id"))
        val graph = body.obj("prompt")!!
        assertTrue(graph.values.any { (it as JsonObject).str("class_type") == "LoraLoader" })

        val images = events.filterIsInstance<GenerationEvent.Image>()
        assertEquals("Only SaveImage outputs, not temp previews", 1, images.size)
        assertEquals(42L, images.single().image.seed)
        val view = routes.requests.first { it.path!!.startsWith("/view?") }.requestUrl!!
        assertEquals("UnfilteredAI_00001_.png", view.queryParameter("filename"))
        assertEquals("output", view.queryParameter("type"))
        assertTrue(events.last() is GenerationEvent.Completed)
    }

    @Test fun validationErrorsListNodeProblems() = runTest {
        server.dispatcher = RoutingDispatcher { r ->
            if (r.path == "/prompt") TestHttp.json(
                """{"error":{"type":"prompt_outputs_failed_validation","message":"Prompt outputs failed validation","details":"","extra_info":{}},
                    "node_errors":{"1":{"errors":[{"type":"value_not_in_list","message":"Value not in list","details":"ckpt_name: 'missing.safetensors' not in []","extra_info":{"input_name":"ckpt_name"}}],"dependent_outputs":["9"],"class_type":"CheckpointLoaderSimple"}}}""",
                400,
            ) else null
        }
        val error = expectFailure { comfy().generate(request(GenerationParameters(prompt = "x", checkpoint = "missing.safetensors"))).toList() }
        assertTrue(error is AppError.Validation)
        val issues = (error as AppError.Validation).issues
        assertTrue(issues.any { "CheckpointLoaderSimple" in it && "ckpt_name" in it && "missing.safetensors" in it })
    }

    @Test fun executionErrorsFromHistorySurface() = runTest {
        var promptId = ""
        server.dispatcher = RoutingDispatcher { r ->
            val path = r.path!!
            when {
                path == "/prompt" -> {
                    promptId = (AppJson.parseToJsonElement(r.body.clone().readUtf8()) as JsonObject).str("prompt_id")!!
                    TestHttp.json("""{"prompt_id":"$promptId","number":1,"node_errors":{}}""")
                }
                path.startsWith("/history/") -> TestHttp.json(
                    """{"$promptId":{"status":{"status_str":"error","completed":false,"messages":[["execution_start",{}],["execution_error",{"node_type":"KSampler","exception_message":"CUDA out of memory","traceback":["line 1\n"]}]]},"outputs":{}}}""",
                )
                else -> null
            }
        }
        val error = expectFailure { comfy().generate(request(GenerationParameters(prompt = "x", checkpoint = "c"))).toList() }
        assertTrue(error is AppError.GenerationFailed)
        assertTrue((error as AppError).userMessage.contains("CUDA out of memory"))
    }

    @Test fun parsesWebSocketMessages() {
        val progress = ComfyWsEvent.parseText("""{"type":"progress","data":{"value":7,"max":30,"prompt_id":"p","node":"3"}}""") as ComfyWsEvent.Progress
        assertEquals(7, progress.value)
        assertEquals(30, progress.max)
        val done = ComfyWsEvent.parseText("""{"type":"executing","data":{"node":null,"prompt_id":"p"}}""") as ComfyWsEvent.Executing
        assertEquals(null, done.node)
        assertTrue(ComfyWsEvent.parseText("""{"type":"execution_success","data":{"prompt_id":"p"}}""") is ComfyWsEvent.Success)

        val legacy = ByteBuffer.allocate(8 + TestHttp.PNG.size).putInt(1).putInt(2).put(TestHttp.PNG).array()
        assertArrayEquals(TestHttp.PNG, (ComfyWsEvent.parseBinary(legacy) as ComfyWsEvent.Preview).bytes)

        val meta = """{"prompt_id":"p","node_id":"3","image_type":"image/png"}""".toByteArray()
        val withMeta = ByteBuffer.allocate(8 + meta.size + TestHttp.PNG.size).putInt(4).putInt(meta.size).put(meta).put(TestHttp.PNG).array()
        val preview = ComfyWsEvent.parseBinary(withMeta) as ComfyWsEvent.Preview
        assertEquals("p", preview.promptId)
        assertArrayEquals(TestHttp.PNG, preview.bytes)
    }

    @Test fun catalogHandlesLegacyAndV3ComboFormats() = runTest {
        server.dispatcher = RoutingDispatcher { r ->
            val path = r.path!!
            when {
                path == "/system_stats" -> TestHttp.json("""{"system":{"comfyui_version":"0.9.1"},"devices":[{"name":"cuda:0 RTX 4090","vram_total":25769803776,"vram_free":20000000000}]}""")
                path == "/object_info/KSampler" -> TestHttp.json("""{"KSampler":{"input":{"required":{"sampler_name":[["euler","dpmpp_2m"],{}],"scheduler":["COMBO",{"options":["normal","karras"]}]}}}}""")
                path == "/object_info/CheckpointLoaderSimple" -> TestHttp.json("""{"CheckpointLoaderSimple":{"input":{"required":{"ckpt_name":[["sdxl.safetensors","flux1-dev.safetensors"],{}]}}}}""")
                path == "/object_info/LoraLoader" -> TestHttp.json("""{"LoraLoader":{"input":{"required":{"lora_name":["COMBO",{"options":["styles/a.safetensors"]}]}}}}""")
                path == "/embeddings" -> TestHttp.json("""["easynegative"]""")
                else -> null
            }
        }
        val catalog = comfy().fetchCatalog()
        assertEquals(listOf("euler", "dpmpp_2m"), catalog.samplers)
        assertEquals(listOf("normal", "karras"), catalog.schedulers)
        assertEquals(ModelArchitecture.FLUX1, catalog.checkpoints[1].architecture)
        assertEquals("styles/a.safetensors", catalog.loras.single().name)
        assertEquals(listOf("easynegative"), catalog.embeddings)
        assertTrue(catalog.serverLabel!!.contains("0.9.1"))
    }
}

class HuggingFaceProviderTest : MockServerTest() {

    private fun hf(settings: HuggingFaceSettings) = HuggingFaceProvider(
        TestHttp.config(ProviderType.HUGGING_FACE, "https://router.huggingface.co", settings),
        TestHttp.http(
            TestHttp.client(redirectTo = server, authHosts = setOf("router.huggingface.co", "huggingface.co"), authHeaders = mapOf("Authorization" to "Bearer hf_test")),
            anonymous = TestHttp.client(redirectTo = server),
        ),
    )

    private val mapping = """{"id":"black-forest-labs/FLUX.1-dev","pipeline_tag":"text-to-image","gated":false,"tags":[],
        "inferenceProviderMapping":[{"provider":"fal-ai","providerId":"fal-ai/flux/dev","status":"live","task":"text-to-image"},
                                    {"provider":"replicate","providerId":"black-forest-labs/flux-dev","status":"live","task":"text-to-image"}]}"""

    @Test fun falQueueFlowWithLoraEndpointSwitch() = runTest {
        val statusCalls = AtomicInteger()
        val routes = RoutingDispatcher { r ->
            val path = r.path!!
            when {
                path.startsWith("/api/models/black-forest-labs/FLUX.1-dev") -> TestHttp.json(mapping)
                path.startsWith("/fal-ai/fal-ai/flux-lora?") && r.method == "POST" -> TestHttp.json(
                    """{"request_id":"abc","status":"IN_QUEUE","response_url":"https://queue.fal.run/fal-ai/flux-lora/requests/abc","status_url":"https://queue.fal.run/fal-ai/flux-lora/requests/abc/status"}""",
                )
                path.startsWith("/fal-ai/fal-ai/flux-lora/requests/abc/status") ->
                    if (statusCalls.incrementAndGet() < 2) TestHttp.json("""{"status":"IN_QUEUE","queue_position":0}""") else TestHttp.json("""{"status":"COMPLETED"}""")
                path.startsWith("/fal-ai/fal-ai/flux-lora/requests/abc?") -> TestHttp.json("""{"images":[{"url":"https://v3.fal.media/files/out.png","width":1024,"height":1024}],"seed":5,"has_nsfw_concepts":[false]}""")
                path == "/files/out.png" -> TestHttp.png()
                else -> null
            }
        }
        server.dispatcher = routes
        val provider = hf(HuggingFaceSettings(routingProvider = "fal-ai", disableProviderSafetyChecker = true))
        val params = GenerationParameters(
            prompt = "a lighthouse", checkpoint = "black-forest-labs/FLUX.1-dev", architecture = ModelArchitecture.FLUX1, distilledGuidance = 3f,
            width = 1024, height = 768, steps = 24, seed = 5, batchSize = 2,
            loras = listOf(LoraSelection(name = "Realism", hfRepoId = "XLabs-AI/flux-RealismLora", hfWeightFile = "lora.safetensors", strength = 0.7f)),
        )
        val events = provider.generate(request(params)).toList()

        val submit = routes.requests.indexOfFirst { it.method == "POST" }
        val body = AppJson.parseToJsonElement(routes.bodies[submit]) as JsonObject
        assertEquals("router.huggingface.co", routes.requests[submit].getHeader("X-Original-Host"))
        assertEquals("Bearer hf_test", routes.requests[submit].getHeader("Authorization"))
        assertEquals(2L, body["num_images"].longValueOrNull)
        assertEquals(1024L, body.obj("image_size")!!["width"].longValueOrNull)
        assertEquals(3.0, body["guidance_scale"].doubleValueOrNull!!, 0.01)
        assertEquals("false", body["enable_safety_checker"].toString())
        val lora = body.arr("loras")!!.single() as JsonObject
        assertEquals("https://huggingface.co/XLabs-AI/flux-RealismLora/resolve/main/lora.safetensors", lora.str("path"))
        assertEquals(0.7, lora["scale"].doubleValueOrNull!!, 0.01)
        assertTrue(events.any { it is GenerationEvent.Queued })
        assertArrayEquals(TestHttp.PNG, events.filterIsInstance<GenerationEvent.Image>().single().image.bytes)
        val download = routes.requests.first { it.path == "/files/out.png" }
        assertEquals("CDN downloads must not carry credentials", null, download.getHeader("Authorization"))
    }

    @Test fun hfInferenceReturnsRawBytesAndRetriesWhileLoading() = runTest {
        val calls = AtomicInteger()
        server.dispatcher = RoutingDispatcher { r ->
            val path = r.path!!
            when {
                path.startsWith("/api/models/stabilityai/sdxl") -> TestHttp.json(
                    """{"id":"stabilityai/sdxl","pipeline_tag":"text-to-image","inferenceProviderMapping":{"hf-inference":{"providerId":"stabilityai/sdxl","status":"live","task":"text-to-image"}}}""",
                )
                path == "/hf-inference/models/stabilityai/sdxl" ->
                    if (calls.incrementAndGet() == 1) TestHttp.json("""{"error":"Model stabilityai/sdxl is currently loading","estimated_time":0.5}""", 503) else TestHttp.png()
                else -> null
            }
        }
        val events = hf(HuggingFaceSettings()).generate(request(GenerationParameters(prompt = "x", checkpoint = "stabilityai/sdxl", seed = 3))).toList()
        assertEquals(2, calls.get())
        assertTrue(events.any { it is GenerationEvent.Status && "loading" in it.message })
        assertEquals(3L, events.filterIsInstance<GenerationEvent.Image>().single().image.seed)
    }

    @Test fun routingRespectsLiveStatusTaskAndAdapters() {
        val provider = hf(HuggingFaceSettings())
        val info = HfModelInfo(
            modelId = "XLabs-AI/flux-RealismLora", pipelineTag = "text-to-image", gated = false, isLora = true,
            mappings = listOf(
                HfProviderMapping("together", "x", "error", "text-to-image"),
                HfProviderMapping("fal-ai", "fal-ai/flux-lora", "live", "text-to-image", adapter = "lora", adapterWeightsPath = "lora.safetensors"),
            ),
        )
        val plan = provider.planRoute(info, "text-to-image")!!
        assertEquals("fal-ai", plan.provider)
        assertEquals("https://huggingface.co/XLabs-AI/flux-RealismLora/resolve/main/lora.safetensors", plan.adapterLoraUrl)
        assertTrue(plan.loraSupport is LoraSupport.RemoteWeights)
        assertEquals(null, provider.planRoute(info, "image-to-image"))
    }
}

class GenericProvidersTest : MockServerTest() {

    @Test fun customSyncJsonTemplate() = runTest {
        val routes = RoutingDispatcher { r -> if (r.path == "/generate") TestHttp.json("""{"images":["data:image/png;base64,${TestHttp.PNG_B64}"]}""") else null }
        server.dispatcher = routes
        val provider = CustomRestProvider(TestHttp.config(ProviderType.CUSTOM_REST, baseUrl, CustomRestSettings(path = "/generate")), TestHttp.http(TestHttp.client()))
        val events = provider.generate(request(GenerationParameters(prompt = "a \"quoted\" prompt", steps = 30, seed = 9))).toList()
        val body = AppJson.parseToJsonElement(routes.bodies.single()) as JsonObject
        assertEquals("a \"quoted\" prompt", body.str("prompt"))
        assertEquals("30", body["steps"].toString())
        assertArrayEquals(TestHttp.PNG, events.filterIsInstance<GenerationEvent.Image>().single().image.bytes)
    }

    @Test fun replicatePresetPollsUntilSucceeded() = runTest {
        val polls = AtomicInteger()
        val routes = RoutingDispatcher { r ->
            val path = r.path!!
            when {
                path == "/v1/models/black-forest-labs/flux-dev/predictions" -> TestHttp.json(
                    """{"id":"p1","status":"starting","urls":{"get":"$baseUrl/v1/predictions/p1","cancel":"$baseUrl/v1/predictions/p1/cancel"}}""",
                )
                path == "/v1/predictions/p1" ->
                    if (polls.incrementAndGet() < 2) TestHttp.json("""{"id":"p1","status":"processing"}""")
                    else TestHttp.json("""{"id":"p1","status":"succeeded","output":["$baseUrl/out.png"]}""")
                path == "/out.png" -> TestHttp.png()
                else -> null
            }
        }
        server.dispatcher = routes
        val preset = CustomRestPresets.all.first { it.name == "Replicate" }
        val settings = preset.settings.copy(async = preset.settings.async!!.copy(pollIntervalMs = 250))
        val provider = CustomRestProvider(TestHttp.config(ProviderType.CUSTOM_REST, baseUrl, settings, preset.auth), TestHttp.http(TestHttp.client()))
        val events = provider.generate(request(GenerationParameters(prompt = "x", checkpoint = "black-forest-labs/flux-dev", batchSize = 1))).toList()
        assertEquals(1, events.filterIsInstance<GenerationEvent.Image>().size)
        assertTrue(events.any { it is GenerationEvent.Submitted && it.remoteJobId == "p1" })
        val submitted = AppJson.parseToJsonElement(routes.bodies.first()) as JsonObject
        assertEquals("x", submitted.obj("input")!!.str("prompt"))
    }

    @Test fun openAiCompatibleImagesApi() = runTest {
        val routes = RoutingDispatcher { r -> if (r.path == "/v1/images/generations") TestHttp.json("""{"created":1,"data":[{"b64_json":"${TestHttp.PNG_B64}"},{"b64_json":"${TestHttp.PNG_B64}"}]}""") else null }
        server.dispatcher = routes
        val provider = OpenAiImagesProvider(
            TestHttp.config(ProviderType.OPENAI_COMPATIBLE, baseUrl, OpenAiImagesSettings(defaultModel = "flux-schnell", sendExtendedParams = true, extraJson = """{"disable_safety_checker": true}""")),
            TestHttp.http(TestHttp.client()),
        )
        val events = provider.generate(request(GenerationParameters(prompt = "x", width = 768, height = 1024, batchSize = 2, steps = 4, seed = 1))).toList()
        val body = routes.requests.single().bodyJson()
        assertEquals("768x1024", body.str("size"))
        assertEquals(2L, body["n"].longValueOrNull)
        assertEquals("flux-schnell", body.str("model"))
        assertEquals("true", body["disable_safety_checker"].toString())
        assertEquals(2, events.filterIsInstance<GenerationEvent.Image>().size)
    }
}

package com.unfiltered.studio.data.providers.a1111

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

/** AUTOMATIC1111 / Forge `sdapi/v1` (server started with `--api`). */
interface A1111Api {
    @POST("sdapi/v1/txt2img")
    suspend fun txt2img(@Body body: JsonObject): A1111ImageResponse

    @POST("sdapi/v1/img2img")
    suspend fun img2img(@Body body: JsonObject): A1111ImageResponse

    @GET("sdapi/v1/progress")
    suspend fun progress(@Query("skip_current_image") skipCurrentImage: Boolean): A1111Progress

    @POST("sdapi/v1/interrupt")
    suspend fun interrupt(): Response<Unit>

    @GET("sdapi/v1/sd-models")
    suspend fun sdModels(): List<A1111SdModel>

    @GET("sdapi/v1/samplers")
    suspend fun samplers(): List<A1111Named>

    @GET("sdapi/v1/schedulers")
    suspend fun schedulers(): List<A1111Scheduler>

    @GET("sdapi/v1/upscalers")
    suspend fun upscalers(): List<A1111Named>

    @GET("sdapi/v1/latent-upscale-modes")
    suspend fun latentUpscaleModes(): List<A1111Named>

    /** AUTOMATIC1111 only (Forge replaced it with sd-modules). */
    @GET("sdapi/v1/sd-vae")
    suspend fun sdVae(): List<A1111Module>

    /** Forge only: VAEs and text encoders usable as `forge_additional_modules`. */
    @GET("sdapi/v1/sd-modules")
    suspend fun sdModules(): List<A1111Module>

    /** Provided by the built-in Lora extension. */
    @GET("sdapi/v1/loras")
    suspend fun loras(): List<A1111Lora>

    @POST("sdapi/v1/refresh-loras")
    suspend fun refreshLoras(): Response<Unit>

    @POST("sdapi/v1/refresh-checkpoints")
    suspend fun refreshCheckpoints(): Response<Unit>

    @GET("sdapi/v1/embeddings")
    suspend fun embeddings(): A1111Embeddings
}

@Serializable
data class A1111ImageResponse(
    val images: List<String>? = null,
    val info: String? = null,
)

@Serializable
data class A1111Progress(
    val progress: Double = 0.0,
    @SerialName("eta_relative") val etaRelative: Double = 0.0,
    val state: A1111State? = null,
    @SerialName("current_image") val currentImage: String? = null,
    val textinfo: String? = null,
)

@Serializable
data class A1111State(
    val interrupted: Boolean = false,
    val job: String? = null,
    @SerialName("job_count") val jobCount: Int = 0,
    @SerialName("job_no") val jobNo: Int = 0,
    @SerialName("sampling_step") val samplingStep: Int = 0,
    @SerialName("sampling_steps") val samplingSteps: Int = 0,
)

@Serializable
data class A1111SdModel(
    val title: String,
    @SerialName("model_name") val modelName: String,
    val hash: String? = null,
    val sha256: String? = null,
    val filename: String? = null,
)

@Serializable
data class A1111Named(val name: String)

@Serializable
data class A1111Scheduler(val name: String, val label: String? = null)

@Serializable
data class A1111Module(@SerialName("model_name") val modelName: String, val filename: String? = null)

@Serializable
data class A1111Lora(
    val name: String,
    val alias: String? = null,
    val path: String? = null,
    val metadata: JsonObject? = null,
)

@Serializable
data class A1111Embeddings(val loaded: Map<String, JsonObject> = emptyMap())

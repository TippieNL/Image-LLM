package com.unfiltered.studio.data.network

import com.unfiltered.studio.core.AppJson
import com.unfiltered.studio.data.network.civitai.CivitaiMapper
import com.unfiltered.studio.data.network.civitai.CivitaiPageDto
import com.unfiltered.studio.data.network.civitai.CivitaiQueries
import com.unfiltered.studio.data.network.huggingface.HfMapper
import com.unfiltered.studio.data.network.huggingface.HfModelDto
import com.unfiltered.studio.data.network.huggingface.HfQueries
import com.unfiltered.studio.data.network.huggingface.HfTreeEntryDto
import com.unfiltered.studio.domain.models.ArchitectureFamily
import com.unfiltered.studio.domain.models.BrowseQuery
import com.unfiltered.studio.domain.models.ModelArchitecture
import com.unfiltered.studio.domain.models.ModelKind
import com.unfiltered.studio.domain.models.ModelSource
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HfMapperTest {
    @Test fun normalizesArrayAndObjectProviderMappings() {
        val array = AppJson.parseToJsonElement("""[{"provider":"fal-ai","providerId":"fal-ai/flux/dev","status":"live","task":"text-to-image"}]""")
        val obj = AppJson.parseToJsonElement("""{"replicate":{"providerId":"bfl/flux","status":"staging","task":"text-to-image"}}""")
        assertEquals("fal-ai/flux/dev", HfMapper.mappings(array).single().providerId)
        val fromObject = HfMapper.mappings(obj).single()
        assertEquals("replicate", fromObject.provider)
        assertTrue(!fromObject.isLive)
    }

    @Test fun mapsLoraRepoWithTreeSizesAndHashes() {
        val dto = AppJson.decodeFromString(
            HfModelDto.serializer(),
            """{"id":"ByteDance/Hyper-SD","downloads":10,"likes":5,"pipeline_tag":"text-to-image","tags":["lora","base_model:adapter:black-forest-labs/FLUX.1-dev"],
               "cardData":{"license":"other","license_name":"flux-1-dev","base_model":"black-forest-labs/FLUX.1-dev","instance_prompt":"hyper style"},"gated":false}""",
        )
        val tree = listOf(
            HfTreeEntryDto("file", "Hyper-FLUX.1-dev-8steps-lora.safetensors", 1388026440, com.unfiltered.studio.data.network.huggingface.HfLfsDto("6461f67d", 1388026440)),
            HfTreeEntryDto("file", "README.md", 100),
        )
        val remote = HfMapper.toRemote(dto, description = null, tree = tree)
        assertEquals(ModelKind.LORA, remote.kind)
        assertEquals(ModelArchitecture.FLUX1, remote.architecture)
        assertEquals("flux-1-dev", remote.license)
        val file = remote.primaryFile!!
        assertEquals(1388026440L, file.sizeBytes)
        assertEquals("6461f67d", file.sha256)
        assertEquals("https://huggingface.co/ByteDance/Hyper-SD/resolve/main/Hyper-FLUX.1-dev-8steps-lora.safetensors", file.downloadUrl)
        assertEquals(listOf("hyper style"), remote.versions.single().triggerWords)
    }

    @Test fun buildsListingQueriesAndParsesLinkHeader() {
        val url = HfQueries.firstPageUrl(BrowseQuery(text = "anime", kind = ModelKind.LORA, family = ArchitectureFamily.SDXL), 30).toHttpUrl()
        assertEquals(listOf("lora", "base_model:adapter:stabilityai/stable-diffusion-xl-base-1.0"), url.queryParameterValues("filter"))
        assertEquals("anime", url.queryParameter("search"))
        assertTrue("inferenceProviderMapping" in url.queryParameterValues("expand[]"))
        val next = HfQueries.nextLink("""<https://huggingface.co/api/models?limit=30&cursor=abc>; rel="next"""")
        assertEquals("https://huggingface.co/api/models?limit=30&cursor=abc", next)
    }
}

class CivitaiMapperTest {
    private val page = """{"items":[{"id":264290,"name":"Not Artists Styles","description":"<p>Hi</p>","type":"LORA","nsfw":false,
        "tags":["style"],"creator":{"username":"obuyb404"},"stats":{"downloadCount":666415,"thumbsUpCount":26790},
        "allowCommercialUse":["Image","Rent"],"allowDerivatives":true,"allowNoCredit":true,
        "modelVersions":[{"id":1558543,"name":"v1","baseModel":"Pony","trainedWords":["abstractionism, brush stroke, "],
          "files":[{"id":1,"sizeKB":56075.03,"name":"styles.safetensors","type":"Model","metadata":{"format":"SafeTensor","fp":null},
                    "hashes":{"SHA256":"ABCDEF"},"downloadUrl":"https://civitai.com/api/download/models/1558543","primary":true}],
          "images":[{"url":"https://image.civitai.com/a.jpeg","nsfwLevel":1},{"url":"https://image.civitai.com/b.jpeg","nsfwLevel":8}]}]}],
        "metadata":{"nextCursor":"463030|43804","nextPage":"https://civitai.com/api/v1/models?cursor=463030%7C43804"}}"""

    @Test fun mapsLiveShape() {
        val dto = AppJson.decodeFromString(CivitaiPageDto.serializer(), page)
        val model = CivitaiMapper.toRemote(dto.items.single())
        assertEquals(ModelSource.CIVITAI, model.source)
        assertEquals(ModelKind.LORA, model.kind)
        assertEquals(ModelArchitecture.PONY, model.architecture)
        val version = model.versions.single()
        assertEquals(listOf("abstractionism", "brush stroke"), version.triggerWords)
        val file = version.files.single()
        assertEquals("abcdef", file.sha256)
        assertEquals((56075.03 * 1024).toLong(), file.sizeBytes)
        assertEquals(listOf(false, true), model.previewUrls.map { it.nsfw })
        assertTrue(model.license!!.contains("Image, Rent"))
        assertEquals("https://civitai.com/api/v1/models?cursor=463030%7C43804", dto.metadata?.nextPage)
    }

    @Test fun buildsFilteredQuery() {
        val url = CivitaiQueries.firstPageUrl(BrowseQuery(source = ModelSource.CIVITAI, kind = ModelKind.LORA, family = ArchitectureFamily.FLUX1, includeNsfw = false), 20).toHttpUrl()
        assertEquals(listOf("LORA", "LoCon", "DoRA"), url.queryParameterValues("types"))
        assertTrue("Flux.1 D" in url.queryParameterValues("baseModels"))
        assertEquals("false", url.queryParameter("nsfw"))
        assertEquals("Most Downloaded", url.queryParameter("sort"))
    }
}

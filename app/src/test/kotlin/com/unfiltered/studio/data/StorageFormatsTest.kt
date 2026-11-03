package com.unfiltered.studio.data

import com.unfiltered.studio.data.storage.LoraMetadataParser
import com.unfiltered.studio.data.storage.PngText
import com.unfiltered.studio.data.storage.SafetensorsReader
import com.unfiltered.studio.domain.models.ModelArchitecture
import com.unfiltered.studio.testutil.TestHttp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.imageio.ImageIO

class PngTextTest {
    @Test fun insertsUtf8ParametersThatRoundTripAndKeepImageValid() {
        val text = "a café at night 🌙\nNegative prompt: blurry\nSteps: 20, Seed: 1"
        val out = PngText.withText(TestHttp.PNG, "parameters", text)
        assertEquals(text, PngText.readText(out)["parameters"])
        val decoded = ImageIO.read(ByteArrayInputStream(out))
        assertEquals(2, decoded.width)
    }

    @Test fun nonPngHasNoText() {
        assertTrue(PngText.readText(byteArrayOf(1, 2, 3)).isEmpty())
    }
}

class SafetensorsTest {
    private fun safetensors(headerJson: String): ByteArray {
        val header = headerJson.toByteArray()
        return ByteBuffer.allocate(8 + header.size + 4).order(ByteOrder.LITTLE_ENDIAN).putLong(header.size.toLong()).put(header).putInt(0).array()
    }

    @Test fun readsKohyaMetadata() {
        val tagFrequency = """{"10_ohwx woman": {"ohwx woman": 30, "smile": 12, "outdoors": 5}}""".replace("\"", "\\\"")
        val file = safetensors(
            """{"__metadata__":{"ss_output_name":"my_lora","ss_base_model_version":"sdxl_base_v1-0","ss_tag_frequency":"$tagFrequency"},"lora_unet_a.weight":{"dtype":"F16","shape":[4],"data_offsets":[0,8]}}""",
        )
        val header = SafetensorsReader.readHeader(ByteArrayInputStream(file))
        assertEquals(1, header.tensorCount)
        val parsed = LoraMetadataParser.parse(header.metadata)
        assertEquals("my_lora", parsed.title)
        assertEquals(ModelArchitecture.SDXL, parsed.architecture)
        assertEquals("ohwx woman", parsed.triggerWords.first())
        assertTrue("smile" in parsed.triggerWords)
    }

    @Test fun modelSpecFieldsWin() {
        val parsed = LoraMetadataParser.parse(mapOf("modelspec.architecture" to "flux-1-dev/lora", "modelspec.trigger_phrase" to "neon style, glow", "modelspec.title" to "Neon"))
        assertEquals(ModelArchitecture.FLUX1, parsed.architecture)
        assertEquals(listOf("neon style", "glow"), parsed.triggerWords)
        assertEquals("Neon", parsed.title)
    }

    @Test(expected = java.io.IOException::class)
    fun rejectsNonSafetensors() {
        SafetensorsReader.readHeader(ByteArrayInputStream(ByteArray(16) { 0x7F }))
    }
}

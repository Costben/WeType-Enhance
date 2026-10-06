package com.xposed.wetypehook.wetype.voice.shell.eta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class EtaDexAnchorsTest {

    /**
     * 锚点表。前两条是 Eta 3.2.0 反编译核对过的真实目标：源码里的 `speechBaseUrl`
     * 与 OkHttp `Headers.checkValue` / `checkName`，混淆后分别落在 `Lk04;` 与 `Lbb5;`。
     */
    private val anchors = listOf(SPEECH_BASE_URL, HEADER_CHAR)

    private val hits: Map<String, List<DexMethodRef>> by lazy {
        EtaDexAnchors.scan(dexBytes(), anchors)
    }

    @Test
    fun `speech base url anchor resolves to the only obfuscated method`() {
        val matched = hits.getValue(SPEECH_BASE_URL)
        println("$SPEECH_BASE_URL -> $matched")
        assertEquals(1, matched.size)
        assertEquals(
            DexMethodRef("Lk04;", "B", listOf("Ljava/lang/String;"), "Ljava/lang/String;"),
            matched.single(),
        )
    }

    @Test
    fun `okhttp header char anchor resolves to check name and check value`() {
        val matched = hits.getValue(HEADER_CHAR)
        println("$HEADER_CHAR -> $matched")
        assertTrue(DexMethodRef("Lbb5;", "B", listOf("Ljava/lang/String;"), "V") in matched)
        assertTrue(DexMethodRef("Lbb5;", "C", listOf("Ljava/lang/String;", "Ljava/lang/String;"), "V") in matched)
    }

    private fun dexBytes(): ByteArray {
        val path = System.getenv("ETA_DEX_PATH") ?: DEFAULT_DEX_PATH
        val file = File(path)
        assumeTrue("Eta dex not found at $path; set ETA_DEX_PATH to run this test", file.isFile)
        return file.readBytes()
    }

    private companion object {
        const val DEFAULT_DEX_PATH = "/tmp/eta-apk/classes.dex"
        const val SPEECH_BASE_URL = "服务地址须为不含认证信息和查询参数的 HTTPS 地址"
        const val HEADER_CHAR = "Unexpected char 0x"
    }
}

package com.xposed.wetypehook.wetype.voice.shell.eta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 用真 dex 验证「按方法体里的字符串常量反查混淆方法」这条链路。
 *
 * 断言只钉**形状**，不钉混淆名：R8 每次发版都会重排类名与方法名，写死就等于把测试绑在
 * 某一个版本上（曾经绑 3.2.0 的 `Lk04;.B` / `Lbb5;.B`，换 3.4.0 的 dex 直接变红，而功能
 * 本身是好的）。已核对过的两版：
 *
 * - Eta 3.2.0：`speechBaseUrl` = `Lk04;.B`，okhttp 校验 = `Lbb5;.B` / `Lbb5;.C`
 * - Eta 3.4.0：`speechBaseUrl` = `Lzo4;.b0`，okhttp 校验 = `Lq35;.q`（另有 `(String)V` 的 checkName）
 */
class EtaDexAnchorsTest {

    private val anchors = listOf(SPEECH_BASE_URL, HEADER_CHAR)

    private val hits: Map<String, List<DexMethodRef>> by lazy {
        EtaDexAnchors.scan(dexBytes(), anchors)
    }

    /**
     * 锚点串在整个 dex 的字符串池里只出现一次，所以只能命中一个方法 —— 命中多个就说明
     * R8 把 `speechBaseUrl` 内联进了调用方，那时 hook 会挂到错误的方法上，必须重新设计。
     */
    @Test
    fun `speech base url anchor resolves to exactly one string to string method`() {
        val matched = hits.getValue(SPEECH_BASE_URL)
        println("$SPEECH_BASE_URL -> $matched")
        assertEquals(1, matched.size)
        val ref = matched.single()
        assertEquals(listOf("Ljava/lang/String;"), ref.parameterTypes)
        assertEquals("Ljava/lang/String;", ref.returnType)
    }

    /** okhttp 的 `checkName` 与 `checkValue` 共用同一条报错前缀，两个都得命中。 */
    @Test
    fun `okhttp header char anchor resolves to check name and check value`() {
        val matched = hits.getValue(HEADER_CHAR)
        println("$HEADER_CHAR -> $matched")
        assertTrue(
            "checkName(String): void 没命中",
            matched.any { it.parameterTypes == listOf("Ljava/lang/String;") && it.returnType == "V" },
        )
        assertTrue(
            "checkValue(String, String): void 没命中",
            matched.any {
                it.parameterTypes == listOf("Ljava/lang/String;", "Ljava/lang/String;") &&
                    it.returnType == "V"
            },
        )
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

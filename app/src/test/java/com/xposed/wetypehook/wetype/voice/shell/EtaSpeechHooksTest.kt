package com.xposed.wetypehook.wetype.voice.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 钉住 [EtaSpeechHooks.plaintextBaseUrl] 的口径：壳只跑明文，明文地址放行、回环上的
 * `https://` 改写成 `http://`、回环之外一律不碰。
 *
 * 这条规则决定「用户填什么地址能连上」，边界比实现看起来多，所以逐条钉死。
 */
class EtaSpeechHooksTest {

    @Test
    fun `plaintext addresses pass through`() {
        assertEquals("http://127.0.0.1:18516", EtaSpeechHooks.plaintextBaseUrl("http://127.0.0.1:18516"))
        assertEquals("http://192.168.1.5:18516", EtaSpeechHooks.plaintextBaseUrl("http://192.168.1.5:18516"))
        assertEquals("http://localhost:18516", EtaSpeechHooks.plaintextBaseUrl("http://localhost:18516"))
    }

    /** 设置页复制出来的地址不带斜杠，但用户手输时常常带一个。 */
    @Test
    fun `trailing slash and surrounding blanks are trimmed`() {
        assertEquals("http://127.0.0.1:18516", EtaSpeechHooks.plaintextBaseUrl("  http://127.0.0.1:18516/  "))
        assertEquals("http://127.0.0.1:18516", EtaSpeechHooks.plaintextBaseUrl("http://127.0.0.1:18516///"))
    }

    /** 上一版要求填 `https://`，用户地址栏里多半还留着 —— 回环上的改写成明文，不用重填。 */
    @Test
    fun `loopback https is rewritten to plaintext`() {
        assertEquals("http://127.0.0.1:18516", EtaSpeechHooks.plaintextBaseUrl("https://127.0.0.1:18516"))
        assertEquals("http://127.0.0.5:18516", EtaSpeechHooks.plaintextBaseUrl("https://127.0.0.5:18516"))
        assertEquals("http://localhost:18516", EtaSpeechHooks.plaintextBaseUrl("https://localhost:18516"))
        assertEquals("http://[::1]:18516", EtaSpeechHooks.plaintextBaseUrl("https://[::1]:18516"))
    }

    /** 回环之外的 https 只可能是真服务，降级成明文会把用户的安全连接打坏。 */
    @Test
    fun `non loopback https is left to the original check`() {
        assertNull(EtaSpeechHooks.plaintextBaseUrl("https://dashscope.aliyuncs.com"))
        assertNull(EtaSpeechHooks.plaintextBaseUrl("https://192.168.1.5:18516"))
        // `1270.0.0.1` / `127.0.0.1.evil.com` 只是长得像回环。
        assertNull(EtaSpeechHooks.plaintextBaseUrl("https://127.0.0.1.evil.com:18516"))
    }

    /** 认证信息、查询串、片段这些 Eta 自己也会拒，放行等于让它后面撞墙。 */
    @Test
    fun `addresses the original check would reject stay rejected`() {
        assertNull(EtaSpeechHooks.plaintextBaseUrl("http://user:pass@127.0.0.1:18516"))
        assertNull(EtaSpeechHooks.plaintextBaseUrl("http://127.0.0.1:18516/?key=1"))
        assertNull(EtaSpeechHooks.plaintextBaseUrl("http://127.0.0.1:18516/#frag"))
        assertNull(EtaSpeechHooks.plaintextBaseUrl("https://127.0.0.1:18516/?key=1"))
    }

    @Test
    fun `shapes without a host or with another scheme stay rejected`() {
        assertNull(EtaSpeechHooks.plaintextBaseUrl("http://"))
        assertNull(EtaSpeechHooks.plaintextBaseUrl("http:///path"))
        assertNull(EtaSpeechHooks.plaintextBaseUrl("wss://127.0.0.1:18516"))
        assertNull(EtaSpeechHooks.plaintextBaseUrl("127.0.0.1:18516"))
        assertNull(EtaSpeechHooks.plaintextBaseUrl(""))
    }
}

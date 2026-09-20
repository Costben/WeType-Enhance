package com.xposed.wetypehook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModuleUpdateCheckerTest {

    @Test
    fun detectsNewerPatchMinorAndMajorVersions() {
        assertTrue(ModuleUpdateChecker.isNewerVersion("1.28.7", "1.28.6"))
        assertTrue(ModuleUpdateChecker.isNewerVersion("1.29.0", "1.28.6"))
        assertTrue(ModuleUpdateChecker.isNewerVersion("2.0.0", "1.28.6"))
        assertTrue(ModuleUpdateChecker.isNewerVersion("1.28.10", "1.28.9"))
    }

    @Test
    fun ignoresEqualAndOlderVersions() {
        assertFalse(ModuleUpdateChecker.isNewerVersion("1.28.6", "1.28.6"))
        assertFalse(ModuleUpdateChecker.isNewerVersion("1.28.5", "1.28.6"))
        assertFalse(ModuleUpdateChecker.isNewerVersion("1.9.0", "1.28.6"))
    }

    @Test
    fun ignoresNonNumericSuffixes() {
        assertFalse(ModuleUpdateChecker.isNewerVersion("1.28.6-beta", "1.28.6"))
        assertTrue(ModuleUpdateChecker.isNewerVersion("1.28.6-beta", "1.28.5"))
    }

    @Test
    fun parseReturnsNullWhenNoNewerRelease() {
        val body = releaseJson(tag = "v1.28.6", apkUrl = "https://example.com/a.apk")
        assertNull(ModuleUpdateChecker.parseReleaseResponse(body, "1.28.6"))
    }

    @Test
    fun parseBuildsInfoFromNewerRelease() {
        val body = releaseJson(tag = "v1.29.0", apkUrl = "https://example.com/WeType.apk")
        val info = ModuleUpdateChecker.parseReleaseResponse(body, "1.28.6")
        assertEquals("1.29.0", info?.versionName)
        assertEquals("https://example.com/WeType.apk", info?.targetUrl)
        assertEquals("https://example.com/releases/v1.29.0", info?.releaseUrl)
        assertTrue(info?.changelog.orEmpty().contains("修复隐藏 Logo"))
    }

    @Test
    fun parseFallsBackToReleasePageWithoutApkAsset() {
        val body = releaseJson(tag = "v1.29.0", apkUrl = null)
        val info = ModuleUpdateChecker.parseReleaseResponse(body, "1.28.6")
        assertEquals("https://example.com/releases/v1.29.0", info?.targetUrl)
    }

    @Test
    fun formatChangelogStripsMarkdownAndVersionCode() {
        val raw = "## WeType Enhance v1.29.0\n\nversionCode 43 / versionName 1.29.0\n\n- 新增检查更新\n* 修复隐藏 Logo\n"
        val formatted = ModuleUpdateChecker.formatChangelog(raw)
        assertEquals("· 新增检查更新\n· 修复隐藏 Logo", formatted)
    }

    private fun releaseJson(tag: String, apkUrl: String?): String {
        val assets = if (apkUrl == null) {
            "[]"
        } else {
            """[{"name":"WeType_Enhance-1.29.0-signed.apk","browser_download_url":"$apkUrl"}]"""
        }
        return """
            {
              "tag_name": "$tag",
              "html_url": "https://example.com/releases/$tag",
              "body": "## WeType Enhance $tag\n\nversionCode 43 / versionName ${tag.removePrefix("v")}\n\n- 修复隐藏 Logo",
              "assets": $assets
            }
        """.trimIndent()
    }
}

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
    fun ranksPrereleasesBelowTheirReleaseAndAboveThePreviousBase() {
        assertTrue(ModuleUpdateChecker.isNewerVersion("1.28.19-alpha1", "1.28.18"))
        assertTrue(ModuleUpdateChecker.isNewerVersion("1.28.19-alpha2", "1.28.19-alpha1"))
        assertTrue(ModuleUpdateChecker.isNewerVersion("1.28.19-alpha10", "1.28.19-alpha9"))
        assertFalse(ModuleUpdateChecker.isNewerVersion("1.28.19-alpha1", "1.28.19-alpha2"))
        assertTrue(ModuleUpdateChecker.isNewerVersion("1.28.19", "1.28.19-alpha2"))
        assertFalse(ModuleUpdateChecker.isNewerVersion("1.28.19-alpha2", "1.28.19"))
    }

    @Test
    fun readsVersionCodeFromReleaseBody() {
        assertEquals(57, ModuleUpdateChecker.parseVersionCode("## WeType Enhance v1.28.19-alpha2\n\nversionCode 57 / versionName 1.28.19-alpha2"))
        assertNull(ModuleUpdateChecker.parseVersionCode("## WeType Enhance v1.28.6\n\n- 修复隐藏 Logo"))
    }

    @Test
    fun parseReturnsNullWhenNoNewerRelease() {
        val body = releasesJson(releaseJson(tag = "v1.28.6", code = 42, apkUrl = "https://example.com/a.apk"))
        assertNull(ModuleUpdateChecker.parseReleasesResponse(body, 42, "1.28.6", includePrereleases = false))
    }

    @Test
    fun parseBuildsInfoFromNewerRelease() {
        val body = releasesJson(releaseJson(tag = "v1.29.0", code = 60, apkUrl = "https://example.com/WeType.apk"))
        val info = ModuleUpdateChecker.parseReleasesResponse(body, 42, "1.28.6", includePrereleases = false)
        assertEquals("1.29.0", info?.versionName)
        assertEquals("https://example.com/WeType.apk", info?.targetUrl)
        assertEquals("https://example.com/releases/v1.29.0", info?.releaseUrl)
        assertTrue(info?.changelog.orEmpty().contains("修复隐藏 Logo"))
    }

    @Test
    fun parseFallsBackToReleasePageWithoutApkAsset() {
        val body = releasesJson(releaseJson(tag = "v1.29.0", code = 60, apkUrl = null))
        val info = ModuleUpdateChecker.parseReleasesResponse(body, 42, "1.28.6", includePrereleases = false)
        assertEquals("https://example.com/releases/v1.29.0", info?.targetUrl)
    }

    @Test
    fun ignoresPrereleasesUnlessRequested() {
        val body = releasesJson(releaseJson(tag = "v1.28.19-alpha2", code = 57, apkUrl = null, prerelease = true))
        assertNull(ModuleUpdateChecker.parseReleasesResponse(body, 54, "1.28.18", includePrereleases = false))
        val info = ModuleUpdateChecker.parseReleasesResponse(body, 54, "1.28.18", includePrereleases = true)
        assertEquals("1.28.19-alpha2", info?.versionName)
    }

    @Test
    fun picksNewestAcrossMixedReleases() {
        val body = releasesJson(
            releaseJson(tag = "v1.29.0", code = 56, apkUrl = null),
            releaseJson(tag = "v1.30.0-alpha3", code = 59, apkUrl = null, prerelease = true),
            releaseJson(tag = "v1.30.0-alpha1", code = 57, apkUrl = null, prerelease = true)
        )
        assertEquals(
            "1.29.0",
            ModuleUpdateChecker.parseReleasesResponse(body, 54, "1.28.18", includePrereleases = false)?.versionName
        )
        assertEquals(
            "1.30.0-alpha3",
            ModuleUpdateChecker.parseReleasesResponse(body, 54, "1.28.18", includePrereleases = true)?.versionName
        )
    }

    @Test
    fun doesNotOfferOlderBuildWithHigherVersionName() {
        // v1.28.19 是 alpha 轮之前发的预发布（code 55），版本号却比 alpha2 高；
        // 已经装上 alpha2（code 57）的用户不该被提示「升级」到它。
        val body = releasesJson(
            releaseJson(tag = "v1.28.19", code = 55, apkUrl = null, prerelease = true),
            releaseJson(tag = "v1.28.19-alpha2", code = 57, apkUrl = null, prerelease = true)
        )
        assertNull(ModuleUpdateChecker.parseReleasesResponse(body, 57, "1.28.19-alpha2", includePrereleases = true))
    }

    @Test
    fun fallsBackToVersionNameWhenBodyHasNoVersionCode() {
        val body = releasesJson(releaseJson(tag = "v1.29.0", code = null, apkUrl = null))
        val info = ModuleUpdateChecker.parseReleasesResponse(body, 42, "1.28.6", includePrereleases = false)
        assertEquals("1.29.0", info?.versionName)
    }

    @Test
    fun skipsDrafts() {
        val body = releasesJson(releaseJson(tag = "v1.29.0", code = 60, apkUrl = null, draft = true))
        assertNull(ModuleUpdateChecker.parseReleasesResponse(body, 42, "1.28.6", includePrereleases = true))
    }

    @Test
    fun formatChangelogStripsMarkdownAndVersionCode() {
        val raw = "## WeType Enhance v1.29.0\n\nversionCode 43 / versionName 1.29.0\n\n- 新增检查更新\n* 修复隐藏 Logo\n"
        val formatted = ModuleUpdateChecker.formatChangelog(raw)
        assertEquals("· 新增检查更新\n· 修复隐藏 Logo", formatted)
    }

    private fun releasesJson(vararg releases: String): String =
        releases.joinToString(prefix = "[", postfix = "]", separator = ",")

    private fun releaseJson(
        tag: String,
        code: Int?,
        apkUrl: String?,
        prerelease: Boolean = false,
        draft: Boolean = false
    ): String {
        val assets = if (apkUrl == null) {
            "[]"
        } else {
            """[{"name":"WeType_Enhance-1.29.0-signed.apk","browser_download_url":"$apkUrl"}]"""
        }
        val versionLine = if (code == null) "" else "versionCode $code / versionName ${tag.removePrefix("v")}\\n\\n"
        return """
            {
              "tag_name": "$tag",
              "html_url": "https://example.com/releases/$tag",
              "body": "## WeType Enhance $tag\n\n${versionLine}- 修复隐藏 Logo",
              "prerelease": $prerelease,
              "draft": $draft,
              "assets": $assets
            }
        """.trimIndent()
    }
}

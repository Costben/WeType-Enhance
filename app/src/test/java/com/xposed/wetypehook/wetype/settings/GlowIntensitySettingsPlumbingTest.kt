package com.xposed.wetypehook.wetype.settings

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `glow_intensity` 必须落满「能存能读」需要的每一个点。
 *
 * 与 [KeyEdgeLightSettingsMirrorTest] 同一类问题：漏掉任何一处都不是编译错误，表现是保存后不
 * 生效、或者重启后被悄悄改回默认，从现象根本倒推不出来。设置层的落点是固定的，所以这里逐个
 * 断言，而不是只检查常量存在。
 */
class GlowIntensitySettingsPlumbingTest {

    private val settings = File(
        "src/main/java/com/xposed/wetypehook/wetype/settings/WeTypeSettings.kt"
    ).readText()

    @Test
    fun everyLandingSiteIsPresent() {
        LANDING_SITES.forEach { site ->
            assertTrue("缺少落点：$site", settings.contains(site))
        }
    }

    /**
     * 基准值必须是 100：滑杆的 100 与加这个设置之前的行为逐像素一致，调高才开始往外发光。
     * 上下限同时钉住，免得日后改区间时忘记亮层与暗层是按 100 对折的。
     */
    @Test
    fun theNeutralValueIsTheBaseline() {
        assertTrue(settings.contains("const val DEFAULT_GLOW_INTENSITY = 100"))
        assertTrue(settings.contains("const val MIN_GLOW_INTENSITY = 0"))
        assertTrue(settings.contains("const val MAX_GLOW_INTENSITY = 200"))
    }

    /**
     * 两条读取路径都必须夹紧到合法区间。写盘路径夹紧过、读取路径忘了夹紧时，一份手改过的
     * prefs 就能把倍数顶到荒谬的值，所以单独钉一条。
     */
    @Test
    fun bothReadPathsClamp() {
        val clamping = "coerceIn(MIN_GLOW_INTENSITY, MAX_GLOW_INTENSITY)"
        assertEquals(3, Regex(Regex.escape(clamping)).findAll(settings).count())
    }

    private companion object {
        val LANDING_SITES = listOf(
            // 常量与默认值
            "private const val KEY_GLOW_INTENSITY = \"glow_intensity\"",
            // 快照字段
            "val glowIntensity: Int = DEFAULT_GLOW_INTENSITY,",
            // 三个保存入口：局部更新、整页保存、Logo 图保存
            "glowIntensity: Int? = null,",
            "glowIntensity = glowIntensity ?: current.glowIntensity,",
            "glowIntensity = glowIntensity,",
            "glowIntensity = current.glowIntensity,",
            // 两条 xposed 读取与本地读取
            "fun getGlowIntensityXposed(): Int = readSnapshotXposed().glowIntensity",
            "defaults.glowIntensity",
            "getInt(KEY_GLOW_INTENSITY, DEFAULT_GLOW_INTENSITY)",
            // 写盘与镜像
            "putInt(KEY_GLOW_INTENSITY, snapshot.glowIntensity)",
            "putInt(KEY_GLOW_INTENSITY, glowIntensity)",
            // 默认快照与迁移判据
            "glowIntensity = DEFAULT_GLOW_INTENSITY,",
            "contains(KEY_GLOW_INTENSITY) ||"
        )
    }
}

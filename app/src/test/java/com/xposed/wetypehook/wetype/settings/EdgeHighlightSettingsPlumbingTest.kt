package com.xposed.wetypehook.wetype.settings

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 三类光感（背景 / 图标 / 按键）各是一份 [EdgeLightGroup]，必须落满「能存能读」需要的每一个点。
 *
 * 漏掉任何一处都不是编译错误，表现是保存后不生效、或者重启后被悄悄改回默认，从现象根本倒推
 * 不出来。三类之间还必须完全对称：只补了图标、忘了按键，同样是保存不生效。
 *
 * 所以这里不逐个枚举行号，而是按「落点模板 + 字段名」展开：模板列一次，三个字段各跑一遍，
 * 少一个字段就会指名叫出来。
 */
class EdgeLightSettingsPlumbingTest {

    private val settings = File(
        "src/main/java/com/xposed/wetypehook/wetype/settings/WeTypeSettings.kt"
    ).readText()

    @Test
    fun everyGroupLandsOnEveryReadWriteSite() {
        TARGETS.forEach { target ->
            LANDING_SITES.forEach { template ->
                val expected = template
                    .replace("%FIELD%", target.field)
                    .replace("%KEY%", target.key)
                    .replace("%LITERAL%", target.literal)
                assertTrue(
                    "${target.field} 缺少落点：${expected.lineSequence().first().trim()}",
                    settings.contains(expected)
                )
            }
        }
    }

    /** 旧字段必须清干净：留着半套字段等于两套真相，改一处不生效。 */
    @Test
    fun legacySingleSliderFieldsAreGone() {
        LEGACY_FIELDS.forEach { legacy ->
            assertFalse("旧字段 $legacy 应当已被 EdgeLightGroup 取代", settings.contains(legacy))
        }
    }

    /**
     * 升级路径：读不到新紧凑串时，三类都从旧字段合成基线，否则老安装升上来会丢掉已调好的光感。
     * 旧的内发光上限是 200，必须在合成时夹到 100。
     */
    @Test
    fun legacyFieldsStillFeedTheMigrationBaseline() {
        assertTrue(settings.contains("private fun SharedPreferences.legacyEdgeLightGroup(enabled: Boolean)"))
        assertTrue(settings.contains("legacyEdgeLightGroup(enabled = true)"))
        assertTrue(settings.contains("KEY_ICON_EDGE_LIGHT_ENABLED"))
        assertTrue(settings.contains("KEY_KEY_EDGE_LIGHT_ENABLED"))
        assertTrue(settings.contains("KEY_GLOW_INTENSITY"))
        assertTrue(settings.contains("KEY_EDGE_HIGHLIGHT_INTENSITY"))
        assertTrue(settings.contains("KEY_EDGE_LIGHT_WIDTH"))
        assertTrue(settings.contains("KEY_EDGE_HIGHLIGHT_STROKE_ENABLED"))
        // 只剩旧字段的老安装也要走 toSnapshot，迁移才有机会发生。
        LEGACY_KEYS.forEach { key ->
            assertTrue("$key 必须留在迁移判据里", settings.contains("contains($key) ||"))
        }
    }

    /** 绘制侧的取源：三类各读自己那一份，两个既有的目标开关改成读分组里的 enabled。 */
    @Test
    fun drawingSideReadsTheGroupedSnapshot() {        assertTrue(
            settings.contains(
                "internal fun getEdgeLightGroupXposed(target: EdgeLightTarget): EdgeLightGroup"
            )
        )
        assertTrue(settings.contains("EdgeLightTarget.BACKGROUND -> snapshot.backgroundLight"))
        assertTrue(settings.contains("EdgeLightTarget.ICON -> snapshot.iconLight"))
        assertTrue(settings.contains("EdgeLightTarget.KEY -> snapshot.keyLight"))
        assertTrue(settings.contains("readSnapshotXposed().iconLight.enabled"))
        assertTrue(settings.contains("readSnapshotXposed().keyLight.enabled"))
        assertTrue(settings.contains("readSnapshot(context).keyLight.enabled"))
    }

    /**
     * `rememberSaveable` 只认 Bundle 装得下的值，直接放 [EdgeLightGroup] 会在合成设置页时抛
     * 「item at index N can't be saved」——设置页一打开就崩，且崩在宿主进程里。所以 SavedState
     * 里存的必须是 [EdgeLightGroup.text] 写出的串，读回时走 [EdgeLightGroup.parse]。
     */
    @Test
    fun savedStateStoresTheCompactStringNotTheGroupObject() {
        val saver = File("src/main/java/com/xposed/wetypehook/WeTypeSettingsStateSaver.kt").readText()
        TARGETS.forEach { target ->
            assertTrue(
                "SavedState 必须存 ${target.field}.text()",
                saver.contains("\"${target.field}\" to ${target.field}.text(),")
            )
            assertTrue(
                "SavedState 读回必须走 EdgeLightGroup.parse",
                saver.contains(
                    "${target.field} = EdgeLightGroup.parse(saved[\"${target.field}\"] as? String, ${target.field})"
                )
            )
            assertFalse(
                "SavedState 不能直接塞 ${target.field} 对象，Bundle 存不下",
                saver.contains("\"${target.field}\" to ${target.field},")
            )
        }
    }

    private data class Target(val field: String, val key: String, val literal: String)

    private companion object {
        val TARGETS = listOf(
            Target("backgroundLight", "KEY_BACKGROUND_LIGHT", "background_light"),
            Target("iconLight", "KEY_ICON_LIGHT", "icon_light"),
            Target("keyLight", "KEY_KEY_LIGHT", "key_light"),
        )

        /** `%FIELD%` / `%KEY%` / `%LITERAL%` 会被 [TARGETS] 里的三个字段各展开一次。 */
        val LANDING_SITES = listOf(
            // 常量
            "private const val %KEY% = \"%LITERAL%\"",
            // 快照字段
            "val %FIELD%: EdgeLightGroup = EdgeLightGroup(),",
            // 定点更新入口：参数 + 透传
            "%FIELD%: EdgeLightGroup? = null,",
            "%FIELD% = %FIELD% ?: current.%FIELD%,",
            // 整页保存入口的参数（与快照字段同形，靠前面的 val 区分）
            "\n        %FIELD%: EdgeLightGroup = EdgeLightGroup(),",
            "%FIELD% = %FIELD%,",
            // 局部更新读回当前值
            "%FIELD% = current.%FIELD%,",
            // 写盘前夹紧
            "%FIELD% = %FIELD%.normalized(),",
            // 两条写盘路径
            ".putString(%KEY%, snapshot.%FIELD%.text())",
            "putString(%KEY%, %FIELD%.text())",
            // 两条读取路径
            "EdgeLightGroup.parse(getString(%KEY%), defaults.%FIELD%)",
            "EdgeLightGroup.parse(\n            getString(%KEY%, null),",
            // 迁移判据：有新键就说明这份 prefs 用过光感设置
            "contains(%KEY%) ||",
        )

        val LEGACY_FIELDS = listOf(
            "edgeHighlightIntensity",
            "edgeHighlightStrokeEnabled",
            "getGlowIntensityXposed",
            "isEdgeHighlightStrokeEnabledXposed",
            "getEdgeLightWidthXposed",
        )

        val LEGACY_KEYS = listOf(
            "KEY_EDGE_HIGHLIGHT_STROKE_ENABLED",
            "KEY_EDGE_HIGHLIGHT_INTENSITY",
            "KEY_GLOW_INTENSITY",
            "KEY_EDGE_LIGHT_WIDTH",
            "KEY_ICON_EDGE_LIGHT_ENABLED",
            "KEY_KEY_EDGE_LIGHT_ENABLED",
        )
    }
}

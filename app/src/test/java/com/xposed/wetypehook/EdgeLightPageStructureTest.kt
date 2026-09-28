package com.xposed.wetypehook

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「光感设置」页的排版契约。这几条是用户直接看得见的顺序与归属，改错了不会编译失败，只会让
 * 页面换个样子，所以用源码扫描钉住：
 *
 * - 顶层四段的顺序固定为 总控 → 分类 → 高级参数 → 恢复默认。
 * - 分类是一张卡里的三行，顺序为 背景 → 按钮 → 图标，每行一支下拉。
 * - 边缘 / 内发光及其滑杆不在分类里，只从「高级参数调节」进。
 *
 * 三行下拉的默认项由 `MaterialPresetCatalog.selectableOptions` 给出（第一项固定「无预设」），
 * 由 `MaterialPresetCatalogTest` 与 `EdgeLightGroupTest` 分别钉住。
 */
class EdgeLightPageStructureTest {

    private val page = File(
        "src/main/java/com/xposed/wetypehook/EdgeLightSubPage.kt"
    ).readText()

    private val advancedPage = File(
        "src/main/java/com/xposed/wetypehook/AdvancedParametersSubPage.kt"
    ).readText()

    @Test
    fun sectionsAppearInTheRequiredOrder() {
        val markers = listOf(
            "SmallTitle(text = \"总控\")",
            "SmallTitle(text = \"分类\")",
            "SmallTitle(text = \"高级参数\")",
            "title = \"恢复默认\"",
        )
        val positions = markers.map { marker ->
            val index = page.indexOf(marker)
            assertTrue("缺少标记：$marker", index >= 0)
            index
        }
        positions.zipWithNext().forEach { (before, after) ->
            assertTrue("页面顺序不对：$before 必须排在 $after 之前", before < after)
        }
    }

    /** 旧的「高级预设」标题换成「高级参数」，不留两块并存的残影。 */
    @Test
    fun theOldAdvancedPresetTitleIsGone() {
        assertFalse(page.contains("SmallTitle(text = \"高级预设\")"))
    }

    @Test
    fun theThreeCategoryRowsAreOrderedBackgroundButtonIcon() {
        val markers = listOf("title = \"背景\"", "title = \"按钮\"", "title = \"图标\"")
        val positions = markers.map { marker ->
            val index = page.indexOf(marker)
            assertTrue("缺少分类行：$marker", index >= 0)
            index
        }
        positions.zipWithNext().forEach { (before, after) ->
            assertTrue("分类行顺序不对：$before 必须排在 $after 之前", before < after)
        }
    }

    /**
     * 三行共用同一张卡：三支下拉都在同一个 `Card` 里，所以它们之间只有分割线，展开任何一行都
     * 不会把另外两行拉长。
     */
    @Test
    fun theThreeRowsShareOneCard() {
        val section = page.substringAfter("SmallTitle(text = \"分类\")")
            .substringBefore("SmallTitle(text = \"高级参数\")")
        val cardCount = Regex("\\bCard\\(").findAll(section).count()
        assertTrue("分类区应当只有一张卡，实际 $cardCount", cardCount == 1)
        assertTrue(
            "三行下拉都要由 selectableOptions 提供（第一项才是「无预设」）",
            page.substringAfter("private fun LightTargetPresetRow")
                .contains("MaterialPresetCatalog.selectableOptions(")
        )
    }

    /** 分类区不再有边缘 / 内发光开关：它们只从「高级参数」进。 */
    @Test
    fun edgeAndGlowControlsMovedOutOfTheCategoryCard() {
        val categorySection = page.substringAfter("SmallTitle(text = \"分类\")")
            .substringBefore("SmallTitle(text = \"高级参数\")")
        assertFalse(categorySection.contains("title = \"边缘\""))
        assertFalse(categorySection.contains("title = \"内发光\""))
        assertFalse(categorySection.contains("LightIntensitySlider("))
        assertFalse(categorySection.contains("LightWidthSlider("))
    }

    /**
     * 「高级参数」区的三项顺序：允许全部 36 个预设 → 高级参数调节 → 参数文档；
     * 点「高级参数调节」进的是四级子页，文档入口沿用原 URL。
     */
    @Test
    fun advancedSectionOrderAndEntries() {
        val section = page.substringAfter("SmallTitle(text = \"高级参数\")")
            .substringBefore("title = \"恢复默认\"")
        val allowAll = section.indexOf("允许全部 36 个预设")
        val adjust = section.indexOf("高级参数调节")
        val docs = section.indexOf("预设参数文档")
        assertTrue(allowAll in 0 until adjust)
        assertTrue(adjust in 0 until docs)
        assertTrue(section.contains("onOpenAdvancedParameters"))
    }

    /** 「高级参数调节」是真正注册在导航里的一页，不是就地展开。 */
    @Test
    fun advancedParametersIsARegisteredSubPage() {
        val navigation = File("src/main/java/com/xposed/wetypehook/SettingsNavigation.kt").readText()
        assertTrue(navigation.contains("ADVANCED_PARAMETERS(\"高级参数调节\")"))
        val wiring = File(
            "src/main/java/com/xposed/wetypehook/WeTypeSettingsSubPages.kt"
        ).readText()
        assertTrue(wiring.contains("SettingsSubPage.ADVANCED_PARAMETERS -> AdvancedParametersSubPageContent("))
    }

    /** 自定义预设的编辑项覆盖边缘 / 内发光 / 角度这三组，内置项没有任何滑杆。 */
    @Test
    fun editorsCoverEdgeGlowAndAngleForCustomPresetsOnly() {
        val editors = advancedPage.substringAfter("private fun ColumnScope.AdvancedPresetEditors")
        assertTrue(editors.contains("LightAngleSlider("))
        assertTrue(editors.contains("title = \"边缘\""))
        assertTrue(editors.contains("title = \"内发光\""))
        assertTrue(editors.contains("LightIntensitySlider("))
        assertTrue(editors.contains("LightWidthSlider("))
        // 编辑控件只在自定义预设那一段里出现：内置项那一段（到「自定义预设」标题为止）不许有。
        val builtInSection = advancedPage
            .substringAfter("internal fun LazyListScope.AdvancedParametersSubPageContent")
            .substringBefore("SmallTitle(text = \"自定义预设\")")
        assertFalse(builtInSection.contains("SliderPreference"))
        assertFalse(builtInSection.contains("SwitchPreference"))
    }
}

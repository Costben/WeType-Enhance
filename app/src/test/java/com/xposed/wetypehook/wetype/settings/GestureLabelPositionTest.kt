package com.xposed.wetypehook.wetype.settings

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/**
 * Guards the gesture-label position contract: horizontal centering is the
 * default (left/right margins may offset it), vertical anchoring offers only
 * 顶部/底部, and the initial top/bottom margins land the label on the key
 * area's midline out of the box. The retired CENTER value (2) must normalize
 * to the default instead of leaking into the UI or drawing code.
 */
class GestureLabelPositionTest {
    private val settings = File("src/main/java/com/xposed/wetypehook/wetype/settings/WeTypeSettings.kt").readText()
    private val hooks = File("src/main/java/com/xposed/wetypehook/wetype/hook/WeTypeKeyLabelHooks.kt").readText()
    private val ui = File("src/main/java/com/xposed/wetypehook/MainActivity.kt").readText()

    @Test fun centerOptionIsGone() {
        assertFalse(settings.contains("GESTURE_LABEL_POSITION_CENTER"))
        assertFalse(hooks.contains("GESTURE_LABEL_POSITION_CENTER"))
        assertFalse(ui.contains("GESTURE_LABEL_POSITION_CENTER"))
        assertFalse(ui.contains("\"居中\""))
    }

    @Test fun defaultIsBottomWithLabelUnderTheLetter() {
        assertTrue(settings.contains("const val DEFAULT_GESTURE_LABEL_POSITION = GESTURE_LABEL_POSITION_BOTTOM"))
        // 15dp 是沿垂直中线向下的偏移量（216 实测，密度 3.0：10dp=30px 墨迹中心只落到
        // 中线下方 34px 仍挤在字母上；20dp=60px 顶穿按键底边 1637>1636 太局促；
        // 15dp=45px 才是字母与按键底边之间都留余量的位置）。
        assertTrue(settings.contains("const val DEFAULT_GESTURE_LABEL_MARGIN_TOP_DP = 15"))
        assertTrue(settings.contains("const val DEFAULT_GESTURE_LABEL_MARGIN_BOTTOM_DP = 15"))
    }

    /**
     * 默认值只是起点，上下两个方向都必须留得动：上限太小时用户只能往一边挪。
     */
    @Test fun marginRangeLeavesRoomBothWaysFromDefault() {
        assertTrue(settings.contains("const val GESTURE_LABEL_MARGIN_MIN_DP = 0"))
        assertTrue(settings.contains("const val GESTURE_LABEL_MARGIN_MAX_DP = 48"))
        assertTrue(
            "标签边距的取值范围必须用命名常量收敛，不能在画布代码里另写死数字",
            hooks.contains("WeTypeSettings.GESTURE_LABEL_MARGIN_MAX_DP")
        )
        assertFalse(hooks.contains("coerceIn(0, 24)"))
        assertFalse(ui.contains("max = 24"))
        assertFalse(ui.contains("coerceIn(0, 24)"))
    }

    @Test fun legacyCenterValueNormalizesToDefault() {
        assertTrue(settings.contains("fun normalizeGestureLabelPosition"))
        assertTrue(settings.contains("normalizeGestureLabelPosition(storedLabelPosition)"))
        assertTrue(settings.contains("normalizeGestureLabelPosition(gestureLabelPosition)"))
    }

    /**
     * 老用户预置里存的是旧默认边距，只改常量救不了评论区那台机器：必须在读配置时
     * 把"从没动过滑块"的存量提升到中线默认值，且只认成对命中，不覆盖调过的人。
     */
    @Test fun legacyDefaultMarginsArePromotedToMidline() {
        assertTrue(settings.contains("LEGACY_DEFAULT_GESTURE_LABEL_MARGIN_TOP_DP = 0"))
        assertTrue(settings.contains("LEGACY_DEFAULT_GESTURE_LABEL_MARGIN_BOTTOM_DP = 3"))
        assertTrue(settings.contains("shouldMigrateLabelMargins"))
        assertTrue(
            "迁移必须同时要求迁移标记缺失，否则用户手动调回 0/3 会被反复顶掉",
            settings.contains("!getBoolean(KEY_GESTURE_LABEL_MIDLINE_MIGRATED, false)")
        )
        assertTrue(
            "迁移只能用 -1 兜底判断键是否存在，新装用户的键缺失不能被当成旧默认值",
            settings.contains("getInt(KEY_GESTURE_LABEL_MARGIN_TOP_DP, -1) == LEGACY_DEFAULT_GESTURE_LABEL_MARGIN_TOP_DP")
        )
        assertTrue(settings.contains("putBoolean(KEY_GESTURE_LABEL_MIDLINE_MIGRATED, true)"))
    }

    @Test fun horizontalRemainsCentered() {
        assertTrue(
            "label x must stay on the key horizontal center; left/right margins offset from there",
            hooks.contains("(rect.left + rect.right) / 2f")
        )
    }

    /**
     * 横向基准必须是**键帽**而不是宿主分配的格子。绘制上下文里有两个矩形：格子含不等宽的
     * 左右 padding（Q 左 13 右 8、Z 左 18 右 8…），拿格子当基准会让每个键按自己的 padding
     * 各自偏移，肉眼看到的就是"全选没对齐"。取被包住且更窄的那个才是键帽。
     */
    @Test fun horizontalAnchorUsesKeyCapNotTheCell() {
        assertTrue(
            "绘制前必须先从多个矩形里挑出键帽",
            hooks.contains("resolveKeyCapRect(access, drawCtx)")
        )
        assertTrue(
            "键帽判据：被别的矩形包住、且更窄",
            hooks.contains("outer.contains(inner) && inner.width() < outer.width()")
        )
        assertTrue(
            "绘制上下文的矩形字段必须全部收集，不能再只取第一个",
            hooks.contains("val rectFields = mutableListOf<Field>()")
        )
        assertTrue(
            "getter 改名后推导必须还在，作兜底",
            hooks.contains("access.rectFields.mapNotNull")
        )
    }

    /**
     * 键帽矩形的一级来源必须按**名**取 getter，不能只靠字段顺序或几何推导。
     * 实测（3.5.3/3.5.4 双版本逐键核对）宿主 `selfdraw.j` 上是 `l` 字段 / `t()` 方法，
     * 两者选中的矩形与几何推导一致；名字是宿主自己的语义，字段顺序变了也动不了它。
     */
    @Test fun keyCapRectPrefersNamedGetterOverFieldOrder() {
        assertTrue(
            "候选 getter 名必须收敛在命名常量里",
            hooks.contains("DRAW_RECT_GETTER_NAMES = listOf(\"getDrawRect\", \"t\")")
        )
        assertTrue(
            "必须真的去解析并调用 getter",
            hooks.contains("resolveDrawRectGetter(clazz)")
        )
        assertTrue(
            "getter 取的矩形要经 isEmpty 过滤，空矩形不能当位置用",
            hooks.contains("if (rect != null && !rect.isEmpty) return rect")
        )
        assertTrue(
            "getter 缺失时 [KeyDrawAccess] 仍要能用推导建起来",
            hooks.contains("viewField != null && (drawRectGetter != null || rectFields.isNotEmpty())")
        )
    }

    /**
     * 宿主单键绘制是嵌套的（最外层 `c#a` 内部再调 `c#e`/`e#b`/`c#f`/`c#g`），
     * 而 hookAfter 是返回后触发，同一帧同一个键会被连报 5 次。标签半透明
     * （默认 alpha 153/255≈60%），叠 5 层后有效不透明度约 99%，透明度设置等于失效。
     * 必须只让最外层那次落笔。
     */
    @Test fun nestedKeyDrawOnlyPaintsOncePerFrame() {
        assertTrue(
            "嵌套计数必须挂在同一个 around 拦截器里，before/after 靠 try/finally 保证配对",
            hooks.contains("method.hookAround(")
        )
        assertTrue(
            "计数仍要逐层增减",
            hooks.contains("drawNesting.set(")
        )
        assertTrue(
            "只有退回到最外层、且宿主没抛异常时才画",
            hooks.contains("if (remaining == 0 && failure == null) {")
        )
        assertTrue(
            "计数器必须是每线程独立的，绘制可能发生在不同线程",
            hooks.contains("ThreadLocal.withInitial { 0 }")
        )
        assertFalse(
            "不能再把 before/after 拆成两个独立钩子：宿主一抛异常 after 就被跳过，计数器永久泄漏",
            hooks.contains("method.hookBefore {") && hooks.contains("method.hookAfter { param ->")
        )
    }

    /**
     * 泄漏自愈的日志必须限额。
     *
     * 这条日志走 `module.log()`，在 LSPatch 内嵌模式下是一次跨进程 IPC；而泄漏一旦
     * 发生就会在每次按键绘制时命中，足以把键盘帧拖垮。
     */
    @Test fun nestingLeakLogIsRateLimited() {
        assertTrue(
            "自愈日志要有配额常量",
            hooks.contains("NESTING_LEAK_LOG_BUDGET")
        )
        assertTrue(
            "配额必须是递减的原子计数，不能每次都打",
            hooks.contains("nestingLeakLogsRemaining.getAndUpdate")
        )
    }

    /**
     * 垂直基准只能是按键区域的中线。锚到按键上下边缘是错的：那样"边距 0"落在按键外沿，
     * 用户无论怎么调都回不到中线，正是评论区"怎么调都不居中"的成因。
     */
    @Test fun verticalAnchorIsTheKeyMidline() {
        assertTrue(
            "垂直基准必须取 rect 的垂直中线",
            hooks.contains("val midline = (rect.top + rect.bottom) / 2f")
        )
        val whenBlock = hooks.substringAfter("val y = when (snapshot.verticalPosition) {")
            .substringBefore("canvas.drawText")
        assertTrue(whenBlock.contains("GESTURE_LABEL_POSITION_TOP -> midlineBaseline - snapshot.marginTopPx"))
        assertTrue(whenBlock.contains("else -> midlineBaseline + snapshot.marginBottomPx"))
        assertFalse("不能再锚到按键上边缘", hooks.contains("zoneTop - metrics.ascent"))
        assertFalse("不能再锚到按键下边缘", hooks.contains("zoneBottom - metrics.descent"))
    }

    @Test fun zeroMarginPutsInkCentreOnTheMidline() {
        assertTrue(
            "边距为 0 时墨迹中心必须落在中线上：基线 = 中线 - (ascent + descent) / 2",
            hooks.contains("midline - (metrics.ascent + metrics.descent) / 2f")
        )
    }

    @Test fun dropdownOffersOnlyBottomAndTop() {
        assertTrue(ui.contains("items = listOf(\"底部\", \"顶部\")"))
    }
}

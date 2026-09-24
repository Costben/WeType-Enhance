package com.xposed.wetypehook

import com.xposed.wetypehook.wetype.settings.WeTypeSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 复刻件几何必须原样复现基准档（1080 宽、1010 高、72 系统抬高）的真机实测值；
 * 这些数字来自 216 上的真机像素测量，改比例常量就会在这里断。
 */
class KeyboardReplicaTest {

    private val reference = resolveReplicaGeometry(
        widthPx = 1080,
        totalHeightPx = 1010,
        systemInsetPx = 72
    )!!

    @Test
    fun referenceFrameMatchesDeviceMeasurement() {
        assertEquals(938, reference.bodyHeightPx)
        assertEquals(72, reference.systemInsetPx)
        assertEquals(1010, reference.totalHeightPx)
        assertEquals(0, reference.toolbarTopPx)
        assertEquals(140, reference.toolbarHeightPx)
        assertEquals(140, reference.toolbarBottomPx)
    }

    @Test
    fun keyRowBandsMatchDeviceMeasurement() {
        assertEquals(listOf(154, 331, 507, 683), reference.rows.map { it.topPx })
        assertEquals(listOf(143, 143, 143, 143), reference.rows.map { it.heightPx })
    }

    @Test
    fun keyRunRowsUseUniformPitch() {
        assertEquals(listOf(14, 121, 228, 335, 442, 549, 656, 763, 870, 977), reference.rows[0].keys.map { it.leftPx })
        assertEquals(listOf(67, 174, 281, 388, 495, 602, 709, 816, 923), reference.rows[1].keys.map { it.leftPx })
        assertEquals(List(10) { 90 }, reference.rows[0].keys.map { it.widthPx })
        assertEquals(List(9) { 90 }, reference.rows[1].keys.map { it.widthPx })
    }

    @Test
    fun modifierKeysKeepTheirMeasuredWidths() {
        assertEquals(listOf(14, 174, 281, 388, 495, 602, 709, 816, 939), reference.rows[2].keys.map { it.leftPx })
        assertEquals(listOf(128, 90, 90, 90, 90, 90, 90, 90, 128), reference.rows[2].keys.map { it.widthPx })
        assertEquals(listOf(14, 174, 281, 388, 736, 855), reference.rows[3].keys.map { it.leftPx })
        assertEquals(listOf(143, 90, 90, 332, 103, 212), reference.rows[3].keys.map { it.widthPx })
    }

    @Test
    fun narrowerKeyboardScalesHorizontallyOnly() {
        val narrow = resolveReplicaGeometry(widthPx = 540, totalHeightPx = 1010, systemInsetPx = 72)!!
        assertEquals(938, narrow.bodyHeightPx)
        assertEquals(7, narrow.rows[0].keys.first().leftPx)
        assertEquals(45, narrow.rows[0].keys.first().widthPx)
        assertEquals(194, narrow.rows[3].keys[3].leftPx)
        assertEquals(166, narrow.rows[3].keys[3].widthPx)
    }

    @Test
    fun tallerKeyboardScalesVertically() {
        val taller = resolveReplicaGeometry(widthPx = 1080, totalHeightPx = 1300, systemInsetPx = 72)!!
        assertEquals(1228, taller.bodyHeightPx)
        assertEquals(183, taller.toolbarHeightPx)
        assertEquals(202, taller.rows[0].topPx)
        assertEquals(433, taller.rows[1].topPx)
    }

    @Test
    fun insetZeroLeavesTheWholeWindowToTheBody() {
        val floating = resolveReplicaGeometry(widthPx = 1080, totalHeightPx = 1010, systemInsetPx = 0)!!
        assertEquals(0, floating.systemInsetPx)
        assertEquals(1010, floating.bodyHeightPx)
        assertEquals(166, floating.rows[0].topPx)
    }

    @Test
    fun oversizedInsetCannotCollapseTheBody() {
        val degenerate = resolveReplicaGeometry(widthPx = 1080, totalHeightPx = 1010, systemInsetPx = 5000)!!
        assertEquals(1009, degenerate.systemInsetPx)
        assertEquals(1, degenerate.bodyHeightPx)
    }

    @Test
    fun degenerateInputReturnsNull() {
        assertNull(resolveReplicaGeometry(widthPx = 0, totalHeightPx = 1010, systemInsetPx = 72))
        assertNull(resolveReplicaGeometry(widthPx = 1080, totalHeightPx = 0, systemInsetPx = 0))
        assertNull(resolveReplicaGeometry(widthPx = -1, totalHeightPx = -1, systemInsetPx = 0))
    }

    @Test
    fun fallbackHeightUsesTheMeasuredRatio() {
        assertEquals(1010, resolveReplicaFallbackHeightPx(2376))
    }

    @Test
    fun derivedScaleFollowsTheReferenceFrame() {
        assertEquals(1f, reference.widthScale, 1e-6f)
        assertEquals(59.94f, reference.keyLabelPx, 0.01f)
        assertEquals(36, reference.toolbarLogoLeftPx)
        assertEquals(31, reference.toolbarLogoTopPx)
        assertEquals(90, reference.toolbarLogoSizePx)

        val narrow = resolveReplicaGeometry(widthPx = 540, totalHeightPx = 1010, systemInsetPx = 72)!!
        assertEquals(0.5f, narrow.widthScale, 1e-6f)
        assertEquals(29.97f, narrow.keyLabelPx, 0.01f)
        assertEquals(18, narrow.toolbarLogoLeftPx)
        assertEquals(31, narrow.toolbarLogoTopPx)
        assertEquals(45, narrow.toolbarLogoSizePx)
    }

    /** 点键模拟候选词：0 次不显示，奇数次出「test s」，偶数次出中文那组，来回循环。 */
    @Test
    fun candidateDemoAlternatesBetweenTwoStates() {
        assertEquals(emptyList<String>(), replicaCandidateWords(0))
        assertEquals(emptyList<String>(), replicaCandidateWords(-1))
        assertEquals(listOf("test s"), replicaCandidateWords(1))
        assertEquals(listOf("你好", "测试"), replicaCandidateWords(2))
        assertEquals(listOf("test s"), replicaCandidateWords(3))
        assertEquals(listOf("你好", "测试"), replicaCandidateWords(4))
    }

    /** 候选条上的每条文字都要带上 TEST 后缀，免得跟真输入混起来。 */
    @Test
    fun candidateLabelsCarryTheTestSuffix() {
        assertEquals(listOf("test sTEST"), replicaCandidateLabels(1))
        assertEquals(listOf("你好TEST", "测试TEST"), replicaCandidateLabels(2))
        assertEquals("TEST", CANDIDATE_TEST_SUFFIX)
    }

    /** ⌫ 是退出组词：不管推到第几态都退回工具栏条带那一态；其余键照旧往前推。 */
    @Test
    fun backspaceLeavesTheCandidateDemo() {
        val backspace = ReplicaKeyFace.Backspace
        val letter = ReplicaKeyFace.Letter("A")
        assertEquals(0, replicaClickCountAfter(backspace, 0))
        assertEquals(0, replicaClickCountAfter(backspace, 1))
        assertEquals(0, replicaClickCountAfter(backspace, 2))
        assertEquals(0, replicaClickCountAfter(backspace, 7))
        assertEquals(1, replicaClickCountAfter(letter, 0))
        assertEquals(3, replicaClickCountAfter(ReplicaKeyFace.Shift, 2))
        assertEquals(emptyList<String>(), replicaCandidateLabels(replicaClickCountAfter(backspace, 2)))
    }

    /**
     * 候选条几何对真机实测：行基础左沿 12.72（+ 左边距设置 × density）、行高 152、内容不额外下沉、
     * 项内边距 20、项间距 21、选中底 96 高、字号 56、基线在内容中心下方 0.36em；
     * 末尾分隔线 1×56、距候选 6，箭头 44×26、笔画 6、距分隔线 50。
     */
    @Test
    fun candidateRowFollowsTheReferenceFrame() {
        assertEquals(13, reference.candidateRowLeftPx)
        assertEquals(152, reference.candidateRowHeightPx)
        assertEquals(0, reference.candidateContentDropPx)
        assertEquals(20, reference.candidateItemPaddingPx)
        assertEquals(21, reference.candidateItemGapPx)
        assertEquals(96, reference.candidateHighlightHeightPx)
        assertEquals(56f, reference.candidateLabelPx, 1e-4f)
        assertEquals(20.16f, reference.candidateBaselineOffsetPx, 1e-4f)
        assertEquals(6, reference.candidateDividerGapPx)
        assertEquals(1, reference.candidateDividerWidthPx)
        assertEquals(56, reference.candidateDividerHeightPx)
        assertEquals(50, reference.candidateMoreGapPx)
        assertEquals(44, reference.candidateMoreWidthPx)
        assertEquals(26, reference.candidateMoreHeightPx)
        assertEquals(6f, reference.candidateMoreStrokePx, 1e-4f)
    }

    /** 候选行整体跟着宽度/本体高缩放；分隔线是发丝线，横向缩一半后也不掉到 0。 */
    @Test
    fun candidateRowScalesWithTheFrame() {
        val wide = resolveReplicaGeometry(widthPx = 2160, totalHeightPx = 2020, systemInsetPx = 144)!!
        assertEquals(25, wide.candidateRowLeftPx)
        assertEquals(304, wide.candidateRowHeightPx)
        assertEquals(0, wide.candidateContentDropPx)
        assertEquals(40, wide.candidateItemPaddingPx)
        assertEquals(192, wide.candidateHighlightHeightPx)
        assertEquals(88, wide.candidateMoreWidthPx)
        assertEquals(112f, wide.candidateLabelPx, 1e-4f)
        assertEquals(40.32f, wide.candidateBaselineOffsetPx, 1e-4f)

        val narrow = resolveReplicaGeometry(widthPx = 540, totalHeightPx = 1010, systemInsetPx = 72)!!
        assertEquals(6, narrow.candidateRowLeftPx)
        assertEquals(0, narrow.candidateContentDropPx)
        assertEquals(1, narrow.candidateDividerWidthPx)
        assertEquals(22, narrow.candidateMoreWidthPx)
        assertEquals(3f, narrow.candidateMoreStrokePx, 1e-4f)
        assertEquals(28f, narrow.candidateLabelPx, 1e-4f)
    }

    /**
     * 「候选背景左边距」是加在候选行左沿上的额外像素：设置值 × density。默认 6 档要给回真机实测的
     * 30.66（行基础左沿 12.72 取整成 13，再加 6 × 3.0）。
     */
    @Test
    fun candidateRowLeftAddsTheMarginSettingInDensity() {
        assertEquals(31f, replicaCandidateRowLeftPx(13, 6, 3f), 1e-3f)
        assertEquals(13f, replicaCandidateRowLeftPx(13, 0, 3f), 1e-4f)
        assertEquals(73f, replicaCandidateRowLeftPx(13, 20, 3f), 1e-3f)
        // 同一份设置在不同 density 上换算成不同的像素。
        assertEquals(32.4f, replicaCandidateRowLeftPx(12, 6, 3.4f), 1e-3f)
    }

    /** 选中底圆角是像素值：不缩放，只被块高一半截断。 */
    @Test
    fun candidateCornerIsPixelValueClampedToHalfTheBlock() {
        assertEquals(0f, replicaCandidateCornerPx(0f, 96), 1e-4f)
        assertEquals(20f, replicaCandidateCornerPx(20f, 96), 1e-4f)
        assertEquals(40f, replicaCandidateCornerPx(40f, 96), 1e-4f)
        assertEquals(48f, replicaCandidateCornerPx(60f, 96), 1e-4f)
        assertEquals(0f, replicaCandidateCornerPx(-5f, 96), 1e-4f)
        assertEquals(24f, replicaCandidateCornerPx(40f, 48), 1e-4f)
    }

    /** 选中底的不透明度：设置值直接除以 255，超界夹住。 */
    @Test
    fun candidateFillAlphaIsTheSettingOverFullRange() {
        assertEquals(1f, replicaCandidateFillAlpha(255), 1e-6f)
        assertEquals(0f, replicaCandidateFillAlpha(0), 1e-6f)
        assertEquals(150f / 255f, replicaCandidateFillAlpha(150), 1e-6f)
        assertEquals(1f, replicaCandidateFillAlpha(300), 1e-6f)
        assertEquals(0f, replicaCandidateFillAlpha(-1), 1e-6f)
    }

    /** 选中底的基色是宿主自己的颜色，浅/深各一档，跟键帽色无关。 */
    @Test
    fun candidateBaseColorFollowsTheHostNotTheKeyColor() {
        assertEquals(0xFFFCFCFC.toInt(), replicaCandidateBaseColor(isDark = false))
        assertEquals(0xFF5F5F5F.toInt(), replicaCandidateBaseColor(isDark = true))
        assertTrue(replicaCandidateBaseColor(isDark = false) != 0xFFFFFFFF.toInt())
    }

    /**
     * ⇧/⌫ 的外沿 = 路径盒 + 一个描边宽（圆角接头，不再有斜接尖角外溢）。
     * 这里直接对真机墨迹框：⇧ 50×43、⌫ 55.5×41（真机实测 50×43 与 57×42，半像素级）。
     */
    @Test
    fun glyphOuterInkMatchesDeviceMeasurement() {
        assertEquals(50f, reference.shiftPathWidthPx + reference.shiftStrokePx, 0.01f)
        assertEquals(43f, reference.shiftPathHeightPx + reference.shiftStrokePx, 0.01f)
        assertEquals(55.5f, reference.backspacePathWidthPx + reference.backspaceStrokePx, 0.01f)
        assertEquals(41f, reference.backspacePathHeightPx + reference.backspaceStrokePx, 0.01f)
    }

    @Test
    fun glyphMetricsFollowTheReferenceFrame() {
        assertEquals(46f, reference.shiftPathWidthPx, 1e-4f)
        assertEquals(39f, reference.shiftPathHeightPx, 1e-4f)
        assertEquals(4f, reference.shiftStrokePx, 1e-4f)
        assertEquals(51.5f, reference.backspacePathWidthPx, 1e-4f)
        assertEquals(37f, reference.backspacePathHeightPx, 1e-4f)
        assertEquals(4f, reference.backspaceStrokePx, 1e-4f)
        assertEquals(4f, reference.backspaceCrossStrokePx, 1e-4f)

        val narrow = resolveReplicaGeometry(widthPx = 540, totalHeightPx = 1010, systemInsetPx = 72)!!
        assertEquals(23f, narrow.shiftPathWidthPx, 1e-4f)
        assertEquals(2f, narrow.shiftStrokePx, 1e-4f)
    }

    /** 键面表必须与键位表逐键对齐，否则字号/图标会串到隔壁键上。 */
    @Test
    fun keyFacesAlignWithEveryKeySlot() {
        assertEquals(reference.rows.size, REPLICA_KEY_FACES.size)
        reference.rows.forEachIndexed { index, row ->
            assertEquals(row.keys.size, REPLICA_KEY_FACES[index].size)
        }
        assertEquals(
            "QWERTYUIOP".map { ReplicaKeyFace.Letter(it.toString()) },
            REPLICA_KEY_FACES[0]
        )
        assertEquals(ReplicaKeyFace.Shift, REPLICA_KEY_FACES[2].first())
        assertEquals(ReplicaKeyFace.Backspace, REPLICA_KEY_FACES[2].last())
        assertEquals(List(6) { ReplicaKeyFace.Blank }, REPLICA_KEY_FACES[3])
    }

    /**
     * 键帽圆角必须复现 216（density 480）上的五点实测。
     *
     * 真机键帽是宿主自己 `canvas.drawRoundRect` 画的正圆弧角矩形，半径就是它从
     * `KeyData.bgCorner` 拿到的那个像素值 —— 也就是 hook 注入的 `设置 + 10`。
     * 实测 0/10/20/30/40 档分别是 8.88 / 18.92 / 28.92 / 38.96 / 44.98px：
     * 斜率正好 1px/单位，绝对值小约 1.1px 是宿主键帽描边压出来的内缩，复刻件不描那条边。
     */
    @Test
    fun keyCornerRadiusMatchesDeviceMeasurement() {
        assertEquals(10f, replicaKeyCornerPx(0, 90), 1e-4f)
        assertEquals(20f, replicaKeyCornerPx(10, 90), 1e-4f)
        assertEquals(30f, replicaKeyCornerPx(20, 90), 1e-4f)
        assertEquals(40f, replicaKeyCornerPx(30, 90), 1e-4f)
        assertEquals(10f, REPLICA_KEY_CORNER_OFFSET_PX, 1e-4f)
    }

    /** 真机半径与键宽无关：90px 的字母键和 332px 的空格键在同一档下半径相同。 */
    @Test
    fun keyCornerRadiusIgnoresKeyWidth() {
        assertEquals(replicaKeyCornerPx(10, 90), replicaKeyCornerPx(10, 141), 1e-4f)
        assertEquals(replicaKeyCornerPx(10, 90), replicaKeyCornerPx(10, 332), 1e-4f)
        assertEquals(replicaKeyCornerPx(30, 90), replicaKeyCornerPx(30, 210), 1e-4f)
    }

    /**
     * 半径被截到键宽一半：设置 40 时字母键停在 45px（实测 44.98），
     * 而空格键同一档还有 50px（实测 48.58），说明截断阈值是键宽一半而不是某个固定值。
     */
    @Test
    fun keyCornerRadiusStopsAtHalfTheKeyWidth() {
        assertEquals(45f, replicaKeyCornerPx(40, 90), 1e-4f)
        assertEquals(50f, replicaKeyCornerPx(40, 332), 1e-4f)
        assertEquals(105f, replicaKeyCornerPx(200, 210), 1e-4f)
        assertEquals(0f, replicaKeyCornerPx(-50, 90), 1e-4f)
    }

    /** 四行键位里最窄的键在最大档位下也不能被圆角吃掉半张脸。 */
    @Test
    fun noKeyExceedsItsOwnHalfWidthAtTheMaximumSetting() {
        reference.rows.flatMap { it.keys }.forEach { key ->
            val radius = replicaKeyCornerPx(WeTypeSettings.MAX_KEY_CORNER_RADIUS, key.widthPx)
            assertTrue(radius <= key.widthPx / 2f)
            assertTrue(radius <= WeTypeSettings.MAX_KEY_CORNER_RADIUS + REPLICA_KEY_CORNER_OFFSET_PX)
        }
    }

    /**
     * 两个 Logo 开关 → 预览画哪颗 Logo。
     *
     * 真机的顺序是「先过总开关、再读显示开关」（`WeTypeResourceHooks.hookKeyboardLogo`）：
     * 总开关关掉时 hook 直接 return，宿主原生 Logo 原样显示，此时「显示 Logo」开关根本不会被读到 ——
     * 所以总开关关 + 显示开、总开关关 + 显示关这两种组合都必须落到原生 Logo，
     * 不能把两个开关当成并列条件（那样总开关关 + 显示关会算成「不画」，而真机上 Logo 是在的）。
     */
    @Test
    fun logoModeFollowsTheMasterSwitchOrder() {
        assertEquals(ReplicaLogoMode.Custom, replicaLogoMode(true, true))
        assertEquals(ReplicaLogoMode.Hidden, replicaLogoMode(true, false))
        assertEquals(ReplicaLogoMode.Native, replicaLogoMode(false, true))
        assertEquals(ReplicaLogoMode.Native, replicaLogoMode(false, false))
    }

    /** 三个档位互不相同，别把某一档写成另一档的别名。 */
    @Test
    fun logoModeCoversThreeDistinctStates() {
        val modes = listOf(true to true, true to false, false to true)
            .map { (enabled, show) -> replicaLogoMode(enabled, show) }
        assertEquals(3, modes.toSet().size)
    }
}

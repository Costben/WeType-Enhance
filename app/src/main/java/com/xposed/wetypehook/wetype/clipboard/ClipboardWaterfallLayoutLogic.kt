package com.xposed.wetypehook.wetype.clipboard

/**
 * 剪贴板双列瀑布流的纯布局算术（不引用宿主类，供单测与 Hook 层共用）。
 *
 * 列间距全部由条目左右外边距给（列表水平内边距归零）：中缝 = 2 × 半间距，
 * 屏幕外侧 = 列表相对屏幕的偏移 + 半间距，不需要 ItemDecoration 去查 spanIndex。
 * 行间距同理，由条目上下外边距给。
 */
internal object ClipboardWaterfallLayoutLogic {

    const val SPAN_COUNT = 2

    /** 列表两侧各留的间距。 */
    fun columnHalfGapPx(columnGapPx: Int): Int = (columnGapPx / 2).coerceAtLeast(1)

    /** 条目上下各留的间距。 */
    fun rowHalfGapPx(rowGapPx: Int): Int = (rowGapPx / 2).coerceAtLeast(1)

    /** 单列可用宽：列表实测宽扣掉左右 padding 后按列数均分；取不到有效宽返回 0。 */
    fun columnWidthPx(
        listWidthPx: Int,
        paddingLeftPx: Int,
        paddingRightPx: Int,
        spanCount: Int = SPAN_COUNT
    ): Int {
        if (spanCount <= 0) return 0
        val usable = listWidthPx - paddingLeftPx - paddingRightPx
        if (usable <= 0) return 0
        return usable / spanCount
    }

    /** 点 ⋮ 展开的操作栏里常驻的按钮数：常用语、选词、删除。 */
    const val ACTION_BUTTON_COUNT = 3

    /**
     * 文本卡片最多排几行，让整张卡片不超过「卡片最大高度」。
     *
     * 高度只能靠行数封：`TextView.maxHeight` 只裁剪不出省略号，超出的行会被静默吞掉；
     * `maxLines` + `ellipsize=END` 才会在最后一行末尾显示「…」。
     * [chromePx] 是卡片上不属于文本行的固定占位（上下内边距、药丸标签行）；
     * 至少留 1 行，不会因为上限设得太小把文本整个挤没。
     * 任一输入无效（上限 ≤ 0 或行高 ≤ 0）按「不封顶」处理。
     */
    fun textMaxLines(maxCardPx: Int, chromePx: Int, lineHeightPx: Int): Int {
        if (maxCardPx <= 0 || lineHeightPx <= 0) return Int.MAX_VALUE
        val budget = (maxCardPx - chromePx.coerceAtLeast(0)).coerceAtLeast(0)
        return (budget / lineHeightPx).coerceAtLeast(1)
    }

    /**
     * 布局完成后的纠偏：卡片实际高度仍超过上限时，按实测行距再少排几行。
     *
     * [textMaxLines] 只能按 `TextView.lineHeight` 估，而它只反映主字体的行距；含中文 / emoji 的行会回落到
     * 更高的字体，实测每行比它大一截（实机 62px 对 72px），按估算排出来的卡片会超过上限。
     * 所以布局后再量一次：超出 [overflowPx] 就按 [linePitchPx] 折算要少排几行，向上取整。
     * **只减不增**（结果不会大于 [currentMaxLines]）：每次绑定会重新估一次，布局期内只往下收，
     * 这样不会因为各行高度不一在两个行数之间来回抖。至少留 1 行；只有 1 行可减时不动。
     */
    fun correctedMaxLines(
        currentMaxLines: Int,
        displayedLines: Int,
        overflowPx: Int,
        linePitchPx: Int
    ): Int {
        if (overflowPx <= 0 || linePitchPx <= 0 || displayedLines <= 1) return currentMaxLines
        val drop = (overflowPx + linePitchPx - 1) / linePitchPx
        return minOf(currentMaxLines, (displayedLines - drop).coerceAtLeast(1))
    }

    /**
     * 展开后操作栏里每颗按钮的边长。
     *
     * 宿主展开动画是把条目横向滚动「操作栏宽度」那么远，视口就是列宽。原生三颗带文字的按钮合计
     * 约 726px，列宽只有 456px，滚到头会把最左边的按钮整个挤出视口。这里反过来算：
     * 视口里要给卡片尾部留出 ⋮（[moreButtonPx]）加 [tailPaddingPx] 的位置，否则用户没法再点 ⋮ 收起，
     * 剩下的宽度由 [count] 颗按钮和它们前面的间隙 [gapPx] 平分，结果夹在
     * [[minButtonPx], [maxButtonPx]] 之间（上限是原生图标按钮宽，列宽富裕时不放大）。
     * 列宽未知（≤ 0）返回 0，调用方应保持原生。
     */
    fun actionButtonPx(
        columnWidthPx: Int,
        moreButtonPx: Int,
        tailPaddingPx: Int,
        gapPx: Int,
        minButtonPx: Int,
        maxButtonPx: Int,
        count: Int = ACTION_BUTTON_COUNT
    ): Int {
        if (columnWidthPx <= 0 || count <= 0) return 0
        val menuBudget = columnWidthPx - moreButtonPx.coerceAtLeast(0) - tailPaddingPx.coerceAtLeast(0)
        val raw = (menuBudget - count * gapPx.coerceAtLeast(0)) / count
        return raw.coerceIn(minButtonPx, maxOf(minButtonPx, maxButtonPx))
    }

    /** 操作栏总宽：每颗按钮前都有一段间隙。 */
    fun actionMenuWidthPx(buttonPx: Int, gapPx: Int, count: Int = ACTION_BUTTON_COUNT): Int =
        count * (buttonPx + gapPx.coerceAtLeast(0))
}

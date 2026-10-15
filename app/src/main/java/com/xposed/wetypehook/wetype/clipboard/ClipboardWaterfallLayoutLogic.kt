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

    /** 展开操作栏上面一行并排的按钮数：常用语、选词；删除单独占下面一整行。 */
    const val ACTION_COLUMN_COUNT = 2

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
     * 文本折行视口（px）：滚动容器的实测宽扣掉它随卡片链一起虚高的部分与文本自身左右内边距。
     *
     * 卡片链的宽度由 Hook 层按条目实测宽写死，但那一轮布局量出来的滚动容器宽度可能还是宿主原生口径
     * （整块面板宽）—— 卡片比条目宽多少，它就比真实视口宽多少，减掉才是这一列真正的视口。
     * 滚动容器还没排版（[scrollWidthPx] ≤ 0）时，退回用已知的固定占位 [knownInsetPx]
     * （卡片宽 - 滚动容器宽，与列宽无关的布局常量）从条目宽 [itemWidthPx] 反推。
     * 结果 ≤ 0 表示算不出来，调用方应跳过（保持原样，等下一次布局）。
     */
    fun textViewportPx(
        scrollWidthPx: Int,
        cardWidthPx: Int,
        itemWidthPx: Int,
        knownInsetPx: Int,
        paddingPx: Int
    ): Int {
        val chrome = paddingPx.coerceAtLeast(0)
        if (scrollWidthPx > 0) {
            return scrollWidthPx - (cardWidthPx - itemWidthPx).coerceAtLeast(0) - chrome
        }
        if (knownInsetPx >= 0 && itemWidthPx > 0) return itemWidthPx - knownInsetPx - chrome
        return 0
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
     * 展开后操作栏上面一行里每颗按钮的宽度。
     *
     * 宿主展开动画是把条目横向滚动「操作栏宽度」那么远，视口就是列宽。操作栏太宽会把卡片整个顶出视口，
     * 用户也就没法再点 ⋮ 收起，所以视口里要给卡片尾部留出 ⋮（[moreButtonPx]）加 [tailPaddingPx] 的位置，
     * 剩下的宽度由 [count] 颗按钮和它们前面的间隙 [gapPx] 平分，结果夹在 [[minButtonPx], [maxButtonPx]]
     * 之间（上限是想要的紧凑宽度，列宽富裕时不放大）。列宽未知（≤ 0）返回 0，调用方应保持原生。
     */
    fun actionButtonPx(
        columnWidthPx: Int,
        moreButtonPx: Int,
        tailPaddingPx: Int,
        gapPx: Int,
        minButtonPx: Int,
        maxButtonPx: Int,
        count: Int = ACTION_COLUMN_COUNT
    ): Int {
        if (columnWidthPx <= 0 || count <= 0) return 0
        val menuBudget = columnWidthPx - moreButtonPx.coerceAtLeast(0) - tailPaddingPx.coerceAtLeast(0)
        val raw = (menuBudget - count * gapPx.coerceAtLeast(0)) / count
        return raw.coerceIn(minButtonPx, maxOf(minButtonPx, maxButtonPx))
    }

    /** 操作栏总宽：每列按钮前都有一段间隙。 */
    fun actionMenuWidthPx(buttonPx: Int, gapPx: Int, count: Int = ACTION_COLUMN_COUNT): Int =
        count * (buttonPx + gapPx.coerceAtLeast(0))

    /** 文本卡片的最小行数：单行卡片也撑到这个行数的高度，展开后的按钮才不会小得没法点。 */
    const val CARD_MIN_LINES = 2

    /** 展开操作栏最高只到这么多行文本的卡片高度，内容再长也不会继续长高。 */
    const val ACTION_MAX_LINES = 3

    /** 读不到卡片圆角时，按钮圆角取行高的这个比例。 */
    const val ACTION_FALLBACK_RADIUS_RATIO = 0.3f

    /**
     * [lines] 行文本的卡片有多高：卡片上不属于文本行的固定占位 [chromePx]（上下内边距）加 [lines] 个行距。
     * 行距 ≤ 0（还没排出版）或行数 ≤ 0 返回 0，表示「不知道」。
     */
    fun linesCardHeightPx(chromePx: Int, linePitchPx: Int, lines: Int): Int {
        if (linePitchPx <= 0 || lines <= 0) return 0
        return chromePx.coerceAtLeast(0) + lines * linePitchPx
    }

    /**
     * 展开后三个按钮组合起来的总高度：与盖住它们的卡片一样高，最多到 [maxBlockPx]（三行文本的卡片高度）。
     * 卡片高度未知（≤ 0）返回 0，调用方应保持原生；[maxBlockPx] ≤ 0 表示不封顶。
     */
    fun actionBlockHeightPx(cardHeightPx: Int, maxBlockPx: Int): Int {
        if (cardHeightPx <= 0) return 0
        return if (maxBlockPx > 0) minOf(cardHeightPx, maxBlockPx) else cardHeightPx
    }

    /**
     * 把总高 [blockHeightPx] 拆成「上排高度、下排高度」：两排之间隔 [gapPx]，
     * 上排取整，余数留给下排，两排加间隙正好等于总高（奇数像素也不会比卡片矮一截）。
     */
    fun actionRowHeightsPx(blockHeightPx: Int, gapPx: Int): Pair<Int, Int> {
        if (blockHeightPx <= 0) return 0 to 0
        val gap = gapPx.coerceAtLeast(0)
        val top = ((blockHeightPx - gap) / 2).coerceAtLeast(1)
        val bottom = (blockHeightPx - gap - top).coerceAtLeast(1)
        return top to bottom
    }

    /**
     * 按钮圆角：与卡片一致，但不超过最矮一排高度的一半（再大就不是圆角矩形而是胶囊了）。
     * 读不到卡片圆角（[cardRadiusPx] ≤ 0）时退回行高的 [ACTION_FALLBACK_RADIUS_RATIO]。
     */
    fun actionCornerRadiusPx(cardRadiusPx: Int, shortestRowPx: Int): Int {
        if (shortestRowPx <= 0) return 0
        if (cardRadiusPx <= 0) return (shortestRowPx * ACTION_FALLBACK_RADIUS_RATIO).toInt().coerceAtLeast(1)
        return minOf(cardRadiusPx, shortestRowPx / 2).coerceAtLeast(1)
    }
}

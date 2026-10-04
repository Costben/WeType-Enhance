package com.xposed.wetypehook.wetype.settings

/**
 * 手势标签的垂直几何。
 *
 * 标签的墨迹中心由键帽**中线**沿垂直方向偏移得到，偏移量来自用户设置的标签边距（dp）。
 * 边距是按竖屏全尺寸键帽标定的绝对值，而悬浮键盘与横屏键盘会把键帽压矮：直接照搬
 * 绝对值会让 `偏移 + 墨迹半高` 越过键帽半高，文字穿出键帽画到底板上（issue #3）。
 *
 * 键帽相对参考高度的比例 [fitScale] 是这里唯一的输入：边距与字号都按它收缩，再按它
 * 夹进键帽。比例小于 [HIDE_BELOW_FIT_SCALE] 时键位已经塞不下「字母 + 副文本」，
 * 由用户选择的策略决定缩小还是隐藏。
 *
 * 压缩键位（[isCompressed]）的落点由调用方分两步决定：先按 [COMPRESSED_NUDGE_DP] 往下让，
 * 夹紧后仍贴着字母的宽扁键位（[isWideKey]）再靠右，放进字母右侧的空档。
 *
 * 纯数学、无 Android 依赖，便于单测直接喂数值。
 */
internal object GestureLabelLayout {

    /** 竖屏全尺寸键帽高度（dp）：边距与缩放的标定基准。 */
    const val REFERENCE_KEY_HEIGHT_DP = 48f

    /** 墨迹到键帽上下边缘的最小呼吸量（dp）。 */
    const val MIN_EDGE_GAP_DP = 2f

    /**
     * 「隐藏标签」策略的门槛：键帽矮到参考高度的这个比例以下时，键位放不下
     * 字母加副文本，再画必然越界或压住字母。
     */
    const val HIDE_BELOW_FIT_SCALE = 0.75f

    /** 缩放策略的下限，避免键帽极矮时字号退化到不可读。 */
    const val MIN_SHRINK_SCALE = 0.5f

    /** 压缩键位在缩放后的边距之外再往下让的距离（dp）。 */
    const val COMPRESSED_NUDGE_DP = 2f

    /**
     * 「被压矮」的门槛：键帽矮到参考高度的这个比例以下才额外下移。
     *
     * 取 0.9 而不是 1.0，是为了不被测量噪声误伤：竖屏全尺寸键帽实测 ~145px、比例本就
     * 贴着 1，若按「小于 1 即压缩」判定，1~2px 的浮动就会把竖屏调好的基线也推下去。
     */
    const val COMPRESSED_BELOW_FIT_SCALE = 0.9f

    /** 键帽宽高比超过它就算「宽扁键位」：字母居中后右边会留出一整块空档。 */
    const val WIDE_KEY_ASPECT = 1.2f

    /**
     * 宽扁键位右对齐时离键帽右下角的留白（dp），**不随标签缩放**。
     *
     * [MIN_EDGE_GAP_DP] 只保证墨迹不压到边，正好落在右下角仍然太挤；键帽尺寸不随标签
     * 字号变，留白也该是键帽上的固定距离。
     */
    const val WIDE_KEY_EDGE_PADDING_DP = 4f

    /**
     * 键帽高度相对参考高度的比例，上限 1。
     *
     * 只缩不放：键帽比参考高时仍取 1，避免大屏键盘上标签被推得过远。
     */
    fun fitScale(density: Float, keyHeightPx: Float): Float {
        if (density <= 0f || keyHeightPx <= 0f) return 1f
        return (keyHeightPx / (REFERENCE_KEY_HEIGHT_DP * density)).coerceIn(0f, 1f)
    }

    /** 缩放策略下标签字号要乘的系数。 */
    fun shrinkTextScale(fitScale: Float): Float = fitScale.coerceAtLeast(MIN_SHRINK_SCALE)

    /** 隐藏策略下该键位是否已经小到不该再画标签。 */
    fun hidesAtThisSize(fitScale: Float): Boolean = fitScale < HIDE_BELOW_FIT_SCALE

    /** 宽扁键位：宽度显著大于高度，字母居中后右侧留有空档。 */
    fun isWideKey(keyWidthPx: Float, keyHeightPx: Float): Boolean =
        keyHeightPx > 0f && keyWidthPx / keyHeightPx > WIDE_KEY_ASPECT

    /** 键位是否被明显压矮（悬浮/横屏），需要额外下移。 */
    fun isCompressed(fitScale: Float): Boolean = fitScale < COMPRESSED_BELOW_FIT_SCALE

    /** 压缩键位的额外下移量（px），随标签一起缩放。 */
    fun compressedNudgePx(density: Float, shrinkScale: Float): Float =
        COMPRESSED_NUDGE_DP * density * shrinkScale

    /**
     * 夹紧墨迹中心的偏移量，使墨迹边缘不越过键帽边缘且保留 [gapPx]。
     *
     * 键帽矮到放不下时 `maxOffset` 会小于 0，此时退回 0（墨迹中心压中线），
     * 至少不会朝反方向偏移。
     */
    fun clampOffsetPx(offsetPx: Float, keyHeightPx: Float, inkHalfPx: Float, gapPx: Float): Float {
        val maxOffset = keyHeightPx / 2f - inkHalfPx - gapPx
        return offsetPx.coerceIn(0f, maxOffset.coerceAtLeast(0f))
    }
}

package com.xposed.wetypehook.wetype.hook

internal data class WeTypeBackgroundLayout(
    val windowTop: Int,
    val height: Int,
    val isShown: Boolean,
    val isLaidOut: Boolean,
    val isLayoutRequested: Boolean
)

internal data class WeTypeBackgroundBounds(val top: Int, val height: Int)

/** 与 `InputMethodService.Insets.TOUCHABLE_INSETS_REGION` 同值；纯规则文件不引 Android 类。 */
internal const val TOUCHABLE_INSETS_REGION = 3

/**
 * 宿主切进硬件键盘模式时（微信输入法只要收到字母键码就会切，ADB 注入的每个字母都算），
 * 软键盘收成一条悬浮候选栏：它把可触区换成自定义矩形，而这块矩形明显高于「可见区域顶边」
 * —— 按键区根本不在窗口底部。
 *
 * 正常键盘不会满足这两条：微信输入法正常键盘报 `touchableInsets=2`；Gboard 虽然也用
 * 自定义矩形，但矩形顶边与可见顶边重合（实测 991 / 991，差值为 0）。
 *
 * 命中时窗口几何已经代表不了键盘区域，任何照它铺开的背板都会盖住整屏或大半屏。
 */
internal fun isCandidateOnlyInsets(
    visibleTopInsets: Int,
    touchableInsets: Int,
    touchableRegionTop: Int,
    minGapPx: Int
): Boolean {
    if (touchableInsets != TOUCHABLE_INSETS_REGION) return false
    if (touchableRegionTop <= 0) return false
    return visibleTopInsets - touchableRegionTop >= minGapPx
}

/**
 * Returns decor-local bounds only after all visible content has finished layout.
 *
 * `isLayoutRequested` 只表示「宿主已把下一次布局排进队列」，并不代表当前几何无效。
 * 微信输入法在键盘显示后沿这条链路持续 `requestLayout()`，实测 `decor.isLayoutRequested`
 * 在整个键盘生命周期内恒为 true；若把它当作硬失败，背板会在一次都拿不到有效 bounds 的
 * 情况下被永久隐藏（实测键盘底色回落到 WeType 自身的 14.67 而不是模块的 5.87）。
 *
 * 因此这里只校验真正决定几何是否可用的 `isShown` / `isLaidOut` / 正值高度：
 * `isLayoutRequested` 不再参与判定，对 decor 与内容视图都是如此。
 */
internal fun resolveWeTypeBackgroundBounds(
    decor: WeTypeBackgroundLayout,
    contents: List<WeTypeBackgroundLayout>
): WeTypeBackgroundBounds? {
    if (!decor.isShown || !decor.isLaidOut || decor.height <= 0) return null
    var top = decor.height
    for (content in contents) {
        if (!content.isShown) continue
        if (!content.isLaidOut) return null
        if (content.height <= 0) continue
        val relativeTop = content.windowTop - decor.windowTop
        // 相对顶端为 0 的内容视图不是键盘内容：键盘永远贴在窗口底部，而宿主把某个
        // 全窗口高度的视图（实测 IME 稳定后 `getInputView()` 会给出 windowTop=0）混进
        // 候选列表时，会算出「背板占满整屏」的脏 bounds（h=2631 而不是 1230）。
        if (relativeTop > 0 && relativeTop < top) top = relativeTop
    }
    if (top == decor.height) return null
    return WeTypeBackgroundBounds(top, decor.height - top)
}

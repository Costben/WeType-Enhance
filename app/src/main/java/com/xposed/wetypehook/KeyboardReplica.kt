package com.xposed.wetypehook

import kotlin.math.roundToInt

/**
 * 手绘键盘复刻件的几何。
 *
 * 只做纯比例换算，不碰任何 Android API —— 这样它能在 JVM 单测里逐条对上真机实测值。
 * 基准档是 1080×2376 / density 450 那台机器上量到的真机键盘：宽 1080，IME 窗口高 1010，
 * 其中键盘本体 938、系统抬高（导航栏 inset）72。
 *
 * 竖直方向按「本体高 / 938」缩放，水平方向按「宽度 / 1080」缩放。宿主改键盘高度时，
 * 变的是本体高，四行按键与工具栏一起等比伸缩，横向键位不动 —— 与真机行为一致。
 */
private const val REFERENCE_WIDTH_PX = 1080f

/** 实测：IME 窗口 1010px = 本体 938px + 系统抬高 72px。 */
private const val REFERENCE_BODY_HEIGHT_PX = 938f

/** 取不到宿主真实键盘高度时的兜底比例：1010 / 2376。 */
internal const val REPLICA_FALLBACK_HEIGHT_RATIO = 1010f / 2376f

/** 本体内部条带，基准档实测值（相对本体顶边）。工具栏那一行占满窗口宽、高 140，紧贴窗口顶边。 */
private const val TOOLBAR_TOP_PX = 0f
private const val TOOLBAR_HEIGHT_PX = 140f
private const val KEY_ROW_HEIGHT_PX = 143f

/** 基准档普通键的宽度；键面大字形按它定字号，所以宽键与窄键的字号一致，与真机相同。 */
private const val REFERENCE_KEY_WIDTH_PX = 90f

/** 工具栏左侧那个 logo 图标在基准档的位置与边长（相对面板顶边）。 */
private const val TOOLBAR_LOGO_LEFT_PX = 36f
private const val TOOLBAR_LOGO_TOP_PX = 31f
private const val TOOLBAR_LOGO_SIZE_PX = 90f

/**
 * 工具栏右侧七个图标格的位置与尺寸（相对面板顶边）。
 *
 * 每个图标格 = 一个 90px 的圆形底 + 正中一颗 64px 的矢量图标，四周各内缩 13px。
 * 第一格圆左沿 198，之后等距 126；七格正好顶到右边缘（198 + 126×6 + 90 = 1044，再加右内边距）。
 */
private const val TOOLBAR_ICON_CIRCLE_LEFT_PX = 198f
private const val TOOLBAR_ICON_CIRCLE_PITCH_PX = 126f
private const val TOOLBAR_ICON_INSET_PX = 13f
private const val TOOLBAR_ICON_SIZE_PX = 64f

/**
 * 工具栏里「收起键盘」那一格的下标（最右一格，字形是一枚向下的折角）。
 *
 * 216 实测：工具栏正常显示图标时点这一格，键盘就收起来 —— 窗口回到无键盘态，与「键盘从未弹出」
 * 的截图逐像素一致。它右边没有别的格（198 + 126×6 + 90 = 1044，离右边缘还剩 36）。
 *
 * 注意别把这一格跟「工具栏被剪贴板粘贴建议顶掉」的退化态混了：那种时候整行是建议内容、
 * 没有图标，点哪一格都只是把建议消掉。
 */
internal const val TOOLBAR_COLLAPSE_SLOT_INDEX = 6

/** 键面大字形占键宽的比例（em）。实测 'A' 字面高 43px、键宽 90px，WE-Regular 字面高/em = 0.7175 → 43 / 0.7175 / 90。 */
private const val KEY_LABEL_RATIO = 0.666f

/**
 * 预览里「点键 → 出候选词」的那套假数据。
 *
 * 不接词库：只按点击次数在两个状态之间来回切，用来验候选条的字号、间距与高亮对不对。
 * 每条都拼上 [CANDIDATE_TEST_SUFFIX]，一眼能看出这是预览、不是真输入。
 */
internal const val CANDIDATE_TEST_SUFFIX = "TEST"

/**
 * 候选条（相对面板左边 / 顶边）的实测几何。
 *
 * 真机的候选条跟工具栏是**同一个宿主视图** `ImeCandidateView`：没在组词时画工具栏图标，
 * 一进组词就整行换成候选词 —— 而且候选词占的正是 logo 那一格，组词期间 logo 不画。
 * 所以第一个候选的选中底从 x=31 起（logo 位就在 x=36），不是排在 logo 后面。
 *
 * 这里只是**基础**左沿；「候选背景左边距」是加在第一项上的额外左内边距，见
 * [replicaCandidateRowLeftPx]。
 */
private const val CANDIDATE_ROW_LEFT_PX = 12.72f

/** 候选行高：面板顶边到第一行键帽顶边，实测 152。 */
private const val CANDIDATE_ROW_HEIGHT_PX = 152f

/**
 * 候选条内容的竖直中心相对这一行几何中心的下沉量：**0，内容就是居中**。
 *
 * 真机这一行是 1366–1518（几何中心 1442），选中底实测 y 1393.62–1489.49（高 95.87、中心 1441.56），
 * 也就是整块正落在行的几何中心上。这里曾经写 11 —— 那来自一次把选中底量成 1406–1500 的测量，
 * 后来用「逐列 50% 交叉点 + 亚像素」复量否掉了：1393.6 与 1489.5 两个边沿都稳定复现。
 */
private const val CANDIDATE_CONTENT_DROP_PX = 0f

/** 每个候选自己的左右内边距，实测 20；相邻两个之间再留 21。 */
private const val CANDIDATE_ITEM_PADDING_PX = 20f
private const val CANDIDATE_ITEM_GAP_PX = 21f

/**
 * 选中态底：高 96 的连续圆角块，宽度跟着候选字数走。
 *
 * 实测一个字时是 96×96 —— 半径取半高就是那个圆；两三个字时自然长成胶囊，与真机一致。
 */
private const val CANDIDATE_HIGHLIGHT_HEIGHT_PX = 96f

/**
 * 候选词字号：真机候选（CJK）字面高 53、字距 56，CJK 一个字的步进就是 1em → 字号取 56。
 *
 * 别拿键面大字形那套「字面高 / 0.7175」反推：0.7175 是 WE-Regular 的**拉丁**大写高比例，
 * CJK 走的是系统回退字体，字面高 / em ≈ 0.946，用拉丁的比例算会大出三成（74 vs 56）。
 */
private const val CANDIDATE_LABEL_PX = 56f

/**
 * 候选词基线落在候选行内容中心下方多少 em，实测 0.36。
 *
 * 真机是按基线摆字的。Compose 的 Text 按行盒居中，而 CJK 回退字体的行盒上边约 1.16em、
 * 下边约 0.10em，严重不对称，会把 CJK 墨迹压到行盒中心**下方** 0.17em（em 74 时实测 11px）。
 * 所以候选词不走 Text 居中，改成自己摆基线；顺带让拉丁与 CJK 落在同一个基准上，
 * 与真机一致（真机上首候选「test」的墨迹中心就比「特斯汀」低 2.5px）。
 */
private const val CANDIDATE_BASELINE_RATIO = 0.36f

/** 末尾那根竖分隔线：宽 1、高 56，距最后一个候选 6。 */
private const val CANDIDATE_DIVIDER_GAP_PX = 6f
private const val CANDIDATE_DIVIDER_WIDTH_PX = 1f
private const val CANDIDATE_DIVIDER_HEIGHT_PX = 56f

/** 分隔线右边那个展开箭头 ⌄：墨迹框 44×26、笔画 6，左沿距分隔线 50。 */
private const val CANDIDATE_MORE_GAP_PX = 50f
private const val CANDIDATE_MORE_WIDTH_PX = 44f
private const val CANDIDATE_MORE_HEIGHT_PX = 26f
private const val CANDIDATE_MORE_STROKE_PX = 6f

/**
 * ⇧ 键帽字形的路径盒与描边，基准档实测（键宽 128px）。
 *
 * 真机 ⇧ 的墨迹框是 50×43、笔画 4px；路径盒就是墨迹框每边内缩半个描边。描边按圆角接头画，
 * 外沿恰好落在「路径盒 + 描边」上。**不要改成斜接（miter）**：⇧ 两肩是 45° 尖角，斜接会让
 * 两侧各外溢约 8px，整个字形胖一圈（实测 66×54，真机 50×43）。
 */
private const val SHIFT_PATH_WIDTH_PX = 46f
private const val SHIFT_PATH_HEIGHT_PX = 39f
private const val SHIFT_STROKE_PX = 4f

/** ⌫ 的路径盒、外框描边与内部 × 的笔画，基准档实测（键宽 128px，墨迹框 57×42）。 */
private const val BACKSPACE_PATH_WIDTH_PX = 51.5f
private const val BACKSPACE_PATH_HEIGHT_PX = 37f
private const val BACKSPACE_STROKE_PX = 4f
private const val BACKSPACE_CROSS_STROKE_PX = 4f

/** 四行按键的顶边。相邻两行相差 176–177，不是整数等距，所以逐行写死。 */
private val KEY_ROW_TOPS_PX = floatArrayOf(154f, 331f, 507f, 683f)

/** 键位：左边缘 + 宽度，基准档实测值。 */
private val REFERENCE_KEY_ROWS: List<List<Pair<Float, Float>>> = listOf(
    keyRun(firstLeft = 14f, count = 10, keyWidth = 90f, pitch = 107f),
    keyRun(firstLeft = 67f, count = 9, keyWidth = 90f, pitch = 107f),
    listOf(
        14f to 128f,
        174f to 90f,
        281f to 90f,
        388f to 90f,
        495f to 90f,
        602f to 90f,
        709f to 90f,
        816f to 90f,
        939f to 128f
    ),
    listOf(
        14f to 143f,
        174f to 90f,
        281f to 90f,
        388f to 332f,
        736f to 103f,
        855f to 212f
    )
)

private fun keyRun(firstLeft: Float, count: Int, keyWidth: Float, pitch: Float): List<Pair<Float, Float>> =
    List(count) { index -> (firstLeft + index * pitch) to keyWidth }

/**
 * 键面上画什么。
 *
 * 只有普通字母是可靠识别出来的；[Shift] / [Backspace] 是照实测字形描的几何；
 * [Blank] 是「真机上没能可靠辨认、宁可留空也不画错」的键（第四行整行都是它）。
 */
internal sealed class ReplicaKeyFace {
    data class Letter(val text: String) : ReplicaKeyFace()
    data object Shift : ReplicaKeyFace()
    data object Backspace : ReplicaKeyFace()
    data object Blank : ReplicaKeyFace()
}

/** 四行键面，顺序与 [ReplicaGeometry.rows] 逐键对齐。 */
internal val REPLICA_KEY_FACES: List<List<ReplicaKeyFace>> = listOf(
    "QWERTYUIOP".map { ReplicaKeyFace.Letter(it.toString()) },
    "ASDFGHJKL".map { ReplicaKeyFace.Letter(it.toString()) },
    listOf(
        ReplicaKeyFace.Shift,
        ReplicaKeyFace.Letter("Z"),
        ReplicaKeyFace.Letter("X"),
        ReplicaKeyFace.Letter("C"),
        ReplicaKeyFace.Letter("V"),
        ReplicaKeyFace.Letter("B"),
        ReplicaKeyFace.Letter("N"),
        ReplicaKeyFace.Letter("M"),
        ReplicaKeyFace.Backspace
    ),
    List(REFERENCE_KEY_ROWS[3].size) { ReplicaKeyFace.Blank }
)

/** 复刻件里一个键位的实际像素位置。 */
internal data class ReplicaKey(val leftPx: Int, val widthPx: Int)

/** 复刻件里一行按键。 */
internal data class ReplicaRow(val topPx: Int, val heightPx: Int, val keys: List<ReplicaKey>)

/**
 * 复刻件的完整竖直/水平几何。
 *
 * [systemInsetPx] 是底部那条**不放任何按键**的系统抬高；[bodyHeightPx] 是键盘本体。
 */
internal data class ReplicaGeometry(
    val widthPx: Int,
    val bodyHeightPx: Int,
    val systemInsetPx: Int,
    val toolbarTopPx: Int,
    val toolbarHeightPx: Int,
    val rows: List<ReplicaRow>
) {
    val totalHeightPx: Int get() = bodyHeightPx + systemInsetPx

    /** 工具栏条带的底边，用来给按键区做上边界。 */
    val toolbarBottomPx: Int get() = toolbarTopPx + toolbarHeightPx

    /** 横向缩放：键位与图片素材的宽度都按它算。 */
    val widthScale: Float get() = widthPx / REFERENCE_WIDTH_PX

    /** 纵向缩放：本体内部每一条带的高度都按它算。 */
    val heightScale: Float get() = bodyHeightPx / REFERENCE_BODY_HEIGHT_PX

    /** 键面大字号（px），同一份几何里所有键共用。 */
    val keyLabelPx: Float get() = REFERENCE_KEY_WIDTH_PX * widthScale * KEY_LABEL_RATIO

    /** ⇧ 字形的路径盒与描边（px）。 */
    val shiftPathWidthPx: Float get() = SHIFT_PATH_WIDTH_PX * widthScale
    val shiftPathHeightPx: Float get() = SHIFT_PATH_HEIGHT_PX * widthScale
    val shiftStrokePx: Float get() = SHIFT_STROKE_PX * widthScale

    /** ⌫ 字形的路径盒、外框描边与内部 × 的笔画（px）。 */
    val backspacePathWidthPx: Float get() = BACKSPACE_PATH_WIDTH_PX * widthScale
    val backspacePathHeightPx: Float get() = BACKSPACE_PATH_HEIGHT_PX * widthScale
    val backspaceStrokePx: Float get() = BACKSPACE_STROKE_PX * widthScale
    val backspaceCrossStrokePx: Float get() = BACKSPACE_CROSS_STROKE_PX * widthScale

    /** 工具栏左侧 logo 的位置与边长（px）。logo 那一格同时也是圆底那一格。 */
    val toolbarLogoLeftPx: Int get() = (TOOLBAR_LOGO_LEFT_PX * widthScale).roundToInt()
    val toolbarLogoTopPx: Int get() = toolbarTopPx + (TOOLBAR_LOGO_TOP_PX * heightScale).roundToInt()
    val toolbarLogoSizePx: Int get() = (TOOLBAR_LOGO_SIZE_PX * widthScale).roundToInt()

    /** 工具栏圆形底的直径与顶边（px）；logo 格用 [toolbarLogoLeftPx]，图标格用 [toolbarIconCircleLeftPx]。 */
    val toolbarCircleSizePx: Int get() = toolbarLogoSizePx
    val toolbarCircleTopPx: Int get() = toolbarLogoTopPx

    /** 第 [index] 个工具栏图标格的圆左沿（px）。 */
    fun toolbarIconCircleLeftPx(index: Int): Int =
        ((TOOLBAR_ICON_CIRCLE_LEFT_PX + TOOLBAR_ICON_CIRCLE_PITCH_PX * index) * widthScale)
            .roundToInt()

    /** 图标字形的边长，以及在圆形底里的内缩（px）。 */
    val toolbarIconSizePx: Int get() = (TOOLBAR_ICON_SIZE_PX * widthScale).roundToInt()
    val toolbarIconInsetPx: Int get() = (TOOLBAR_ICON_INSET_PX * widthScale).roundToInt()

    /** 候选条那一行的几何（px）。 */
    val candidateRowLeftPx: Int get() = (CANDIDATE_ROW_LEFT_PX * widthScale).roundToInt()
    val candidateRowHeightPx: Int get() = (CANDIDATE_ROW_HEIGHT_PX * heightScale).roundToInt()
    val candidateContentDropPx: Int
        get() = (CANDIDATE_CONTENT_DROP_PX * heightScale).roundToInt()
    val candidateItemPaddingPx: Int get() = (CANDIDATE_ITEM_PADDING_PX * widthScale).roundToInt()
    val candidateItemGapPx: Int get() = (CANDIDATE_ITEM_GAP_PX * widthScale).roundToInt()
    val candidateHighlightHeightPx: Int
        get() = (CANDIDATE_HIGHLIGHT_HEIGHT_PX * heightScale).roundToInt()
    val candidateLabelPx: Float get() = CANDIDATE_LABEL_PX * widthScale

    /** 候选词基线的落点：候选行内容中心往下 [CANDIDATE_BASELINE_RATIO] em。 */
    val candidateBaselineOffsetPx: Float get() = CANDIDATE_BASELINE_RATIO * candidateLabelPx
    val candidateDividerGapPx: Int get() = (CANDIDATE_DIVIDER_GAP_PX * widthScale).roundToInt()
    val candidateDividerWidthPx: Int
        get() = (CANDIDATE_DIVIDER_WIDTH_PX * widthScale).roundToInt().coerceAtLeast(1)
    val candidateDividerHeightPx: Int
        get() = (CANDIDATE_DIVIDER_HEIGHT_PX * heightScale).roundToInt()
    val candidateMoreGapPx: Int get() = (CANDIDATE_MORE_GAP_PX * widthScale).roundToInt()
    val candidateMoreWidthPx: Int get() = (CANDIDATE_MORE_WIDTH_PX * widthScale).roundToInt()
    val candidateMoreHeightPx: Int get() = (CANDIDATE_MORE_HEIGHT_PX * heightScale).roundToInt()
    val candidateMoreStrokePx: Float get() = CANDIDATE_MORE_STROKE_PX * widthScale
}

/**
 * 取不到宿主真实键盘高度时的兜底：屏幕高 × [REPLICA_FALLBACK_HEIGHT_RATIO]。
 *
 * 调用方要额外把它标成「比例估算」，别让用户以为这是实测值。
 */
internal fun resolveReplicaFallbackHeightPx(screenHeightPx: Int): Int =
    (screenHeightPx * REPLICA_FALLBACK_HEIGHT_RATIO).roundToInt()

/** 点了几次键 → 该显示哪几个候选词（不含 [CANDIDATE_TEST_SUFFIX]）。0 表示还没点过。 */
internal fun replicaCandidateWords(clickCount: Int): List<String> = when {
    clickCount <= 0 -> emptyList()
    clickCount % 2 == 1 -> listOf("test s")
    else -> listOf("你好", "测试")
}

/** 候选条上真正要画的文字：每个候选词后面都挂上 [CANDIDATE_TEST_SUFFIX]。 */
internal fun replicaCandidateLabels(clickCount: Int): List<String> =
    replicaCandidateWords(clickCount).map { it + CANDIDATE_TEST_SUFFIX }

/**
 * 点一下 [face] 之后该停在第几个候选态。
 *
 * ⌫ 是「退出组词」：真机按一次退格把正在组的那串输入删空，候选条随即让回工具栏条带与 logo，
 * 所以这里直接归零，而不是往前推一格。其余键照旧往前推。
 */
internal fun replicaClickCountAfter(face: ReplicaKeyFace, current: Int): Int =
    if (face is ReplicaKeyFace.Backspace) 0 else current + 1

/**
 * 预览里 Logo 该画什么。
 *
 * 真机行为（见 `WeTypeResourceHooks.hookKeyboardLogo`）：
 *
 * - 总开关关：hook 直接 return，宿主自己那颗 Logo 原样显示 → [Native]
 * - 总开关开 + 显示关：往 ImageView 写一个透明 drawable，整格不画 → [Hidden]
 * - 总开关开 + 显示开：模块接管（自定义图片，或矢量回退）→ [Custom]
 *
 * 「显示 Logo」是在通过总开关之后才被读取的，所以总开关关着时，无论「显示」是开是关都
 * 落到 [Native] —— 预览也必须照这个顺序判断，不能把两个开关当成并列条件。
 */
internal enum class ReplicaLogoMode { Custom, Native, Hidden }

/** 由两个开关算出预览要画的 Logo 形态。 */
internal fun replicaLogoMode(logoEnabled: Boolean, logoShowEnabled: Boolean): ReplicaLogoMode =
    when {
        !logoEnabled -> ReplicaLogoMode.Native
        !logoShowEnabled -> ReplicaLogoMode.Hidden
        else -> ReplicaLogoMode.Custom
    }

/**
 * 真机键帽圆角：宿主拿到的 `KeyData.bgCorner` = 用户设置 + 这个偏移。
 *
 * 见 `WeTypeResourceHooks.hookKeyboardKeyCorner()`，它把 `getBgCorner()` 的返回值强制成
 * `设置值 + 10`。
 */
internal const val REPLICA_KEY_CORNER_OFFSET_PX = 10f

/**
 * 真机键帽的像素圆角半径。
 *
 * 宿主 `drawmethod` 包用 `canvas.drawRoundRect(rect, r, r, paint)` 画键帽，`r` 就是上面那个
 * `bgCorner` 值，与 Canvas 同一坐标空间 —— 所以它是**像素**，既不随按键尺寸缩放，也不按
 * density 换算。圆角被截到短边一半，实测截断点落在键宽一半上：216 上 90px 宽的字母键在
 * 设置 40 时停在 44.98px，而 332px 宽的空格键同一档还有 48.58px。
 *
 * 216（density 480）五点实测：设置 0/10/20/30/40 → 8.88/18.92/28.92/38.96/44.98px，
 * 斜率正好 1px/单位。绝对值比这里算出来的小约 1.1px，那是宿主键帽描边造成的（描边居中
 * 压在路径上，填充边界内缩半个线宽）；复刻件不描那条边，所以按几何值取。
 */
internal fun replicaKeyCornerPx(keyCornerRadius: Int, keyWidthPx: Int): Float =
    (keyCornerRadius + REPLICA_KEY_CORNER_OFFSET_PX)
        .coerceAtLeast(0f)
        .coerceAtMost(keyWidthPx / 2f)

/**
 * 候选条第一项左沿的像素位置 = 行基础左沿 + 「候选背景左边距」设置 × density。
 *
 * 那个设置是**第一项自己的额外左内边距**，不是整行的：见
 * `WeTypeResourceHooks.applyCandidateBackgroundLeftMargin`，它只对 `position == 0` 那一项做
 * `baseLeftPadding + applyDimension(DIP, 设置值)`。第一项在流式布局里，加宽它会把后面的候选
 * 一起往右推 —— 所以预览也是整行右移，不是只有第一项动。
 *
 * 216（density 3.0）三点实测：左沿 = 12.72 + 2.99 × 设置值，斜率就是 density；默认 6 档得 30.66。
 */
internal fun replicaCandidateRowLeftPx(baseLeftPx: Int, marginDp: Int, density: Float): Float =
    baseLeftPx + marginDp * density

/**
 * 候选选中底的像素圆角半径。
 *
 * 宿主 `cornerSize`（被 `hookCandidateBackgroundCorner` 强制成设置值）是当**像素**用的：
 * 不随键盘尺寸缩放，也不按 density 换算，只有「截到块高一半」这一条。216 实测设置 20/40 →
 * 20.145/39.790px（斜率 0.997），设置 60 → 47.885px ≈ 48 = 块高 96 的一半。
 */
internal fun replicaCandidateCornerPx(cornerSetting: Float, highlightHeightPx: Int): Float =
    cornerSetting.coerceIn(0f, highlightHeightPx / 2f)

/**
 * 候选选中底的基色。
 *
 * `hookCandidateBackgroundAlpha` 只把宿主那个背景色解析方法返回的颜色的 **alpha** 换掉
 * （`withForcedAlpha`），基色完全是宿主自己的 —— 它既不是用户配的键帽色，也不随面板色走。
 * 216 上实测（把 alpha 拉到 255 直接读基色）：浅色主题 (252,252,252)、深色主题 (95,95,95)，
 * 而同一台机器上键帽色是 0x82FFFFFF / 0x2BEDEDED、面板是 222 / 49 —— 三个数互不相干。
 */
internal const val CANDIDATE_BACKGROUND_LIGHT_ARGB = 0xFFFCFCFC.toInt()
internal const val CANDIDATE_BACKGROUND_DARK_ARGB = 0xFF5F5F5F.toInt()

/** 当前主题下候选选中底该用的基色。 */
internal fun replicaCandidateBaseColor(isDark: Boolean): Int =
    if (isDark) CANDIDATE_BACKGROUND_DARK_ARGB else CANDIDATE_BACKGROUND_LIGHT_ARGB

/**
 * 候选选中底的不透明度。
 *
 * `hookCandidateBackgroundAlpha` 只把宿主返回的**背景基色**强制换成这个 alpha
 * （`withForcedAlpha`），基色本身不动、文字也不受影响。所以预览要画的是「宿主基色按 alpha 叠在
 * 键盘面板上」，而不是先合成好的实色 —— 216 浅色主题下实测 alpha 255/150/0 → (252,252,252) /
 * (240,239,240) / (222,221,223)，最后一档与面板逐通道相同、完全看不见。
 */
internal fun replicaCandidateFillAlpha(alphaSetting: Int): Float =
    alphaSetting.coerceIn(0, 255) / 255f

/**
 * 真机深色键盘的键面字色。
 *
 * 216 深色主题实测：一颗字母键里亮度最高的 546 个像素**全部**停在 (235,235,235)，
 * 再往上一个都没有 —— 所以字色就是这个 235 灰，不是纯白。
 */
internal const val REPLICA_KEY_LABEL_DARK_ARGB = 0xFFEBEBEB.toInt()

/**
 * 把半透明色按 source-over 合成到背板上，返回不透明结果。
 *
 * 键帽色是半透明的（浅色 0x2BEDEDED / 深色 0x2BEEEEED），它的 RGB 分量本身是**浅**的，
 * 直接按它判明暗会得出「黑字」；真正决定可读性的是它叠在面板上的合成色。216 深色主题实测
 * 0x2BEEEEED 叠在面板 (10,10,13) 上 = (48,48,51)，与真机键帽实测 (49,49,51) 差 1。
 */
internal fun replicaCompositeOver(color: Int, backdrop: Int): Int {
    val alpha = (color ushr 24) and 0xFF
    val rest = 255 - alpha
    fun blend(channel: Int, back: Int) = (channel * alpha + back * rest + 127) / 255
    return (0xFF shl 24) or
        (blend((color shr 16) and 0xFF, (backdrop shr 16) and 0xFF) shl 16) or
        (blend((color shr 8) and 0xFF, (backdrop shr 8) and 0xFF) shl 8) or
        blend(color and 0xFF, backdrop and 0xFF)
}

/**
 * 按宽度、宿主键盘总高与系统抬高算出复刻件几何；参数不合法返回 null。
 *
 * [systemInsetPx] 会被夹在 `0..总高-1`：inset 等于或大于总高时本体就成了零高度，没有意义。
 */
internal fun resolveReplicaGeometry(
    widthPx: Int,
    totalHeightPx: Int,
    systemInsetPx: Int
): ReplicaGeometry? {
    if (widthPx <= 0 || totalHeightPx <= 0) return null
    val inset = systemInsetPx.coerceIn(0, totalHeightPx - 1)
    val bodyHeightPx = totalHeightPx - inset
    if (bodyHeightPx <= 0) return null
    val heightScale = bodyHeightPx / REFERENCE_BODY_HEIGHT_PX
    val widthScale = widthPx / REFERENCE_WIDTH_PX
    return ReplicaGeometry(
        widthPx = widthPx,
        bodyHeightPx = bodyHeightPx,
        systemInsetPx = inset,
        toolbarTopPx = (TOOLBAR_TOP_PX * heightScale).roundToInt(),
        toolbarHeightPx = (TOOLBAR_HEIGHT_PX * heightScale).roundToInt(),
        rows = REFERENCE_KEY_ROWS.mapIndexed { index, referenceKeys ->
            ReplicaRow(
                topPx = (KEY_ROW_TOPS_PX[index] * heightScale).roundToInt(),
                heightPx = (KEY_ROW_HEIGHT_PX * heightScale).roundToInt(),
                keys = referenceKeys.map { (left, width) ->
                    ReplicaKey(
                        leftPx = (left * widthScale).roundToInt(),
                        widthPx = (width * widthScale).roundToInt()
                    )
                }
            )
        }
    )
}

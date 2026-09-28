/**
 * ColorOS 材质预览 —— 预设表与 uniform 换算链。
 *
 * 全部数值来自 artifacts/coloros-material/parameter-evidence/ 的逐字摘录，没有一处目测：
 *  - 06 §4  COUI `GradientStrokeStylePresets` / `InnerShadowStylePresets` / `*Spec.default()`
 *  - 06 §2  逐槽位语义  06 §3  换算链
 *  - 03 §3  launcher `BlurServiceEffectType` 16 个场景
 *  - 07 §3  Settings 圆形返回键 `TYPE_FRAMEWORK_CIRCLE_1`
 *  - 08 §4  SystemUI 通知卡描边 / 内阴影，浅深两套
 *
 * 两条写入路径必须分清（见 06 §3.6）：
 *  - "coui"    ：COUI 侧 `toEdgeArray16` / `toShadowArray16`，含 fMax 缩放、lineWidth 归一化、
 *                2.0 下限与 `+=` 累加。
 *  - "native"  ：框架 / SystemUI 侧直接按槽位语义写（08 §3.1/§3.2 的系统自带中文注释即槽位真源），
 *                不做 COUI 那套归一化。
 * 两条路径写的是同一个 shader 的同一组 uniform，只是产生方式不同，所以页面上分开标注。
 */

/* ------------------------------------------------------------------ 基础工具 */

export function at(arr, index, fallback) {
  return Array.isArray(arr) && index < arr.length ? arr[index] : fallback;
}

function coerceAtLeast(v, min) {
  return v < min ? min : v;
}

/** 06 §3.4 —— `$SRC/GradientStrokeStyleKt.java:14-34` 逐行搬运。 */
export function rectLineFadeForSize(baseFadeX, baseFadeY, baseW, baseH, w, h, out, i, i2) {
  const f7 = (baseH + baseW) * 2.0;
  const f8 = (h + w) * 2.0;
  const f9 = baseFadeX + baseFadeY;
  const f10 = f9 * f7;
  if (f10 > baseW) {
    const f11 = baseFadeY * f7;
    out[i] = ((f10 - baseW) + w - f11) / f8;
    out[i2] = f11 / f8;
    return;
  }
  const f12 = baseW - f10;
  if (f9 <= 0.0) {
    out[i] = baseFadeX;
    out[i2] = baseFadeY;
  } else {
    const f13 = (w - f12) / f8;
    out[i] = (baseFadeX * f13) / f9;
    out[i2] = (f13 * baseFadeY) / f9;
  }
}

/* ------------------------------------------------- 06 §3.3 / §3.5 描边与内阴影链 */

/** `$SRC/GradientStrokeStyleMapper.java:27-60` —— business 路径。 */
export function toEdgeArray16(style, width, height, lineWidth, lineAlpha, lineAngle, density) {
  const fMax = Math.max(1.0, density) * 0.34;
  const fAt = at(style.rectLineNear, 0, 1.0);
  const fAt2 = at(style.rectLineNear, 1, 6.0);
  const fAt3 = at(style.rectLineFar, 0, 1.0);
  const fAt4 = at(style.rectLineFar, 1, 4.0);
  const fMax2 = Math.max(1.0, width) / fMax;
  const fMax3 = Math.max(1.0, height) / fMax;
  const c1 = coerceAtLeast(at(style.rectLineSize, 0, fMax2), 1.0);
  const c2 = coerceAtLeast(at(style.rectLineSize, 1, fMax3), 1.0);
  const c3 = coerceAtLeast(Math.max(fAt + fAt2, fAt3 + fAt4), 1e-4);
  const f = ((!Number.isNaN(lineWidth) && lineWidth > 0.0) ? lineWidth : c3) / c3;

  const out = new Array(16).fill(0);
  out[0] = at(style.rectLineColor, 0, 1.0);
  out[1] = at(style.rectLineColor, 1, 0.0);
  out[2] = at(style.rectLineColor, 2, 0.0);
  out[3] = coerceAtLeast(fAt * f, 2.0);
  out[4] = coerceAtLeast(fAt2 * f, 2.0);
  out[5] = coerceAtLeast(fAt3 * f, 2.0);
  out[6] = coerceAtLeast(fAt4 * f, 2.0);
  out[7] = at(style.rectLineAlpha, 0, 1.0) * lineAlpha;
  out[8] = at(style.rectLineAlpha, 1, 0.8) * lineAlpha;
  rectLineFadeForSize(at(style.rectLineFade, 0, 0.0), at(style.rectLineFade, 1, 0.0), c1, c2, fMax2, fMax3, out, 9, 10);
  rectLineFadeForSize(at(style.rectLineFade, 2, 1.0), at(style.rectLineFade, 3, 0.0), c1, c2, fMax2, fMax3, out, 11, 12);
  out[4] += out[3];
  out[6] += out[5];
  out[10] += out[9];
  out[12] += out[11];
  out[13] = lineAngle;
  out[14] = style.rectLinePara1;
  out[15] = style.rectLinePara2;
  return out;
}

/** `$SRC/GradientStrokeStyleMapper.java:62-89` —— 直通路径。 */
export function toEdgeArray16Direct(style, width, height, density) {
  const fMax = Math.max(1.0, density) * 0.34;
  const fMax2 = Math.max(1.0, width) / fMax;
  const fMax3 = Math.max(1.0, height) / fMax;
  const c1 = coerceAtLeast(at(style.rectLineSize, 0, fMax2), 1.0);
  const c2 = coerceAtLeast(at(style.rectLineSize, 1, fMax3), 1.0);
  const out = new Array(16).fill(0);
  out[0] = at(style.rectLineColor, 0, 1.0);
  out[1] = at(style.rectLineColor, 1, 0.0);
  out[2] = at(style.rectLineColor, 2, 0.0);
  out[3] = at(style.rectLineNear, 0, 1.0) * fMax;
  out[4] = at(style.rectLineNear, 1, 6.0) * fMax;
  out[5] = at(style.rectLineFar, 0, 1.0) * fMax;
  out[6] = at(style.rectLineFar, 1, 4.0) * fMax;
  out[7] = at(style.rectLineAlpha, 0, 1.0);
  out[8] = at(style.rectLineAlpha, 1, 0.8);
  rectLineFadeForSize(at(style.rectLineFade, 0, 0.0), at(style.rectLineFade, 1, 0.0), c1, c2, fMax2, fMax3, out, 9, 10);
  rectLineFadeForSize(at(style.rectLineFade, 2, 1.0), at(style.rectLineFade, 3, 0.0), c1, c2, fMax2, fMax3, out, 11, 12);
  out[4] += out[3];
  out[6] += out[5];
  out[10] += out[9];
  out[12] += out[11];
  out[13] = style.angle;
  out[14] = style.rectLinePara1;
  out[15] = style.rectLinePara2;
  return out;
}

/** `$SRC/InnerShadowStyleMapper.java:26-51`。 */
export function toShadowArray16(style, width, height, fadeWidthScale, fadeAlphaInScale, fadeAlphaOutScale, density) {
  const fMax = Math.max(1.0, density) * 0.34;
  const fMax2 = Math.max(1.0, width) / fMax;
  const fMax3 = Math.max(1.0, height) / fMax;
  const out = new Array(16).fill(0);
  out[0] = at(style.fadeColor, 0, 1.0);
  out[1] = at(style.fadeColor, 1, 0.0);
  out[2] = at(style.fadeColor, 2, 0.0);
  out[3] = at(style.fadeAlpha, 0, 0.0) * fadeAlphaInScale;
  out[4] = at(style.fadeAlpha, 1, 0.0) * fadeAlphaInScale;
  out[5] = at(style.fadeAlpha, 2, 0.0) * fadeAlphaOutScale;
  out[6] = style.fadeIn * fadeWidthScale * fMax;
  out[7] = at(style.fadeOffset, 0, 0.0) * fMax;
  out[8] = at(style.fadeOffset, 1, 0.0) * fMax;
  out[9] = ((at(style.fadeScale, 0, 1.0) - 1.0) * at(style.fadeSize, 0, 1.0)) / fMax2 + 1.0;
  out[10] = ((at(style.fadeScale, 1, 1.0) - 1.0) * at(style.fadeSize, 1, 1.0)) / fMax3 + 1.0;
  out[11] = style.fadeInPara1;
  out[12] = style.fadeInPara2;
  out[13] = style.fadeOut * fadeWidthScale * fMax;
  out[14] = style.fadeOutPara1;
  out[15] = style.fadeOutPara2;
  return out;
}

/** `$SRC/InnerShadowStyleMapper.java:53-78` —— 直通路径。 */
export function toShadowArray16Direct(style, width, height, density) {
  const fMax = Math.max(1.0, density) * 0.34;
  const fMax2 = Math.max(1.0, width) / fMax;
  const fMax3 = Math.max(1.0, height) / fMax;
  const out = new Array(16).fill(0);
  out[0] = at(style.fadeColor, 0, 1.0);
  out[1] = at(style.fadeColor, 1, 0.0);
  out[2] = at(style.fadeColor, 2, 0.0);
  out[3] = at(style.fadeAlpha, 0, 0.0);
  out[4] = at(style.fadeAlpha, 1, 0.0);
  out[5] = at(style.fadeAlpha, 2, 0.0);
  out[6] = style.fadeIn * fMax;
  out[7] = at(style.fadeOffset, 0, 0.0) * fMax;
  out[8] = at(style.fadeOffset, 1, 0.0) * fMax;
  out[9] = ((at(style.fadeScale, 0, 1.0) - 1.0) * at(style.fadeSize, 0, 1.0)) / fMax2 + 1.0;
  out[10] = ((at(style.fadeScale, 1, 1.0) - 1.0) * at(style.fadeSize, 1, 1.0)) / fMax3 + 1.0;
  out[11] = style.fadeInPara1;
  out[12] = style.fadeInPara2;
  out[13] = style.fadeOut * fMax;
  out[14] = style.fadeOutPara1;
  out[15] = style.fadeOutPara2;
  return out;
}

/* ------------------------------------- 框架 / SystemUI 直写（08 §3.1 / §3.2 槽位表）*/

/**
 * `GradientStrokeLineParams`（14 字段，构造序即槽位序）→ `u_edgeArray[16]`。
 * 槽位语义真源：`StrokeLineGroup.java:30-44` 的系统自带中文注释（08 §3.1）。
 *
 * **`[4] [6] [10] [12]` 必须写成「实部 + 虚部」（外沿），不是虚部本身。**
 * `GradientStrokeLineParamsKt.java:12` 写入 uniform 时就是
 * `getStrokeLineVerticalNearFade() + getStrokeLineVerticalNearSolid()` 这样的加法；
 * 着色器 `BlurDrawableShaderStrokeStringKt.java:9` 拿 `u_edgeArray[3]` 当实部、
 * `u_edgeArray[4]` 当外沿做 `smoothstep(-near.y, -near.x, d)`。
 * 这与本文件 `toEdgeArray16` / `toEdgeArray16Direct` 的 `out[4] += out[3]` 是同一条规则。
 * 另外颜色只取 rgb —— `GradientStrokeLineParams` 构造器里那个 `Color` 的 alpha 不生效。
 */
export function strokeLineParamsToEdgeArray(p) {
  const out = [
    p.strokeLineColor[0], p.strokeLineColor[1], p.strokeLineColor[2],
    p.strokeLineVerticalNearSolid,
    p.strokeLineVerticalNearFade,
    p.strokeLineVerticalFarSolid,
    p.strokeLineVerticalFarFade,
    p.strokeLineAlphaNear,
    p.strokeLineAlphaFar,
    p.strokeLineTransverseNearSolid,
    p.strokeLineTransverseNearFade,
    p.strokeLineTransverseFarSolid,
    p.strokeLineTransverseFarFade,
    p.ratio,
    p.strokeLinePow,
    p.strokeLineMix,
  ];
  out[4] += out[3];
  out[6] += out[5];
  out[10] += out[9];
  out[12] += out[11];
  return out;
}

/**
 * `InnerShadowParams`（14 字段）→ `u_shadowArray[16]`。
 * 槽位语义真源：`InnerShadowGroup.java:30-44`（08 §3.2）。
 */
export function innerShadowParamsToShadowArray(p) {
  return [
    p.shadowColor[0], p.shadowColor[1], p.shadowColor[2],
    p.offsetShadowMaxAlpha,
    p.offsetShadowMinAlpha,
    p.noOffsetShadowAlpha,
    p.offsetShadowRange,
    p.offsetShadowX,
    p.offsetShadowY,
    p.offsetShadowScaleX,
    p.offsetShadowScaleY,
    p.offsetShadowPow,
    p.offsetShadowMix,
    p.noOffsetShadowRange,
    p.noOffsetShadowPow,
    p.noOffsetShadowMix,
  ];
}

/* -------------------------------------------------------------- 06 §4 预设本体 */

const WHITE3 = [1.0, 1.0, 1.0];

/**
 * 真机密度。PLK110：`wm size` 1272×2772、Physical density 560、**Override density 620**
 * → density = 620 / 160 = 3.875。
 *
 * 凡是「按 dp 换算的 px」与「COUI 换算链的 density 入参」都必须用这个值，
 * 否则预览与实机不同尺度。注意 `ShadowEdgeShader` 自带的默认值是 `3.0f`
 * （06 §3.1 `$SRC/ShadowEdgeShader.java:52`），运行时才被
 * `DisplayMetrics.density` 覆盖（06 §3.1 `COUIShadowEdgeDrawable.java:188-196`）——
 * 所以 3.0 只是「拿不到 Context 时的兜底」，不是本机真值。
 */
export const DEVICE_DENSITY = 3.875;

function strokeSpec(size, near, far, alpha, fade, para1, para2) {
  return { rectLineSize: size, rectLineColor: WHITE3, rectLineNear: near, rectLineFar: far,
           rectLineAlpha: alpha, rectLineFade: fade, angle: 0.0,
           rectLinePara1: para1, rectLinePara2: para2 };
}

/** 06 §4.1 —— LIGHT 与 DARK 逐值完全相同（`:13-15` vs `:16-18`）。 */
export const EDGE_STYLE = {
  EDGE_STYLE_0: strokeSpec([400.0, 100.0], [2.0, 6.0], [2.0, 3.0], [0.7, 1.0], [0.2, 0.4, 0.15, 0.35], 4.0, 0.8),
  EDGE_STYLE_1: strokeSpec([160.0, 160.0], [2.0, 6.0], [2.0, 3.0], [0.7, 1.0], [0.06, 0.4, 0.01, 0.28], 4.0, 0.8),
  EDGE_STYLE_2: strokeSpec([600.0, 200.0], [2.0, 8.0], [2.0, 2.0], [0.7, 0.6], [0.18, 0.35, 0.25, 0.25], 4.0, 0.8),
};

function shadowSpec(size, alpha, offset, scale, fadeIn, inP1, inP2, fadeOut, outP1, outP2) {
  return { fadeSize: size, fadeColor: WHITE3, fadeAlpha: alpha, fadeOffset: offset,
           fadeScale: scale, fadeIn, fadeInPara1: inP1, fadeInPara2: inP2,
           fadeOut, fadeOutPara1: outP1, fadeOutPara2: outP2 };
}

/** 06 §4.2 —— 同样 LIGHT == DARK。 */
export const SHADOW_STYLE = {
  SHADOW_STYLE_0: shadowSpec([400.0, 100.0], [1.0, 0.0, 0.0], [0.0, 20.0], [1.3, 1.15], 120.0, 4.0, 0.9, 20.0, 4.0, 0.7),
  SHADOW_STYLE_1: shadowSpec([160.0, 160.0], [0.4, 0.0, 0.0], [0.0, 20.0], [1.4, 1.35], 140.0, 4.0, 0.9, 20.0, 4.0, 0.7),
  SHADOW_STYLE_2: shadowSpec([600.0, 600.0], [0.6, 0.0, 0.0], [0.0, 10.0], [1.1, 1.07], 86.0, 4.0, 0.7, 1.0, 1.0, 0.0),
};

/** 06 §4.4 —— 两个 Holder 的默认 spec；阴影那个 `fadeAlpha[2]=0.1` 是唯一会点亮 PART3 的。 */
export const SPEC_DEFAULT = {
  edge: strokeSpec([400.0, 400.0], [1.0, 6.0], [1.0, 4.0], [1.0, 0.8], [0.2, 0.5, 0.0, 0.35], 2.0, 0.0),
  shadow: shadowSpec([400.0, 400.0], [0.25, 0.0, 0.1], [0.0, 15.0], [1.005, 1.035], 50.0, 2.0, 0.0, 15.0, 2.0, 0.0),
};

/* ------------------------------------------------------- 03 §3 launcher 16 场景 */

/**
 * 每行 10 个数值，列序见 03 §3。
 * 两个全行常量：`strokeLineColor = (1,1,1,0.35)`、`shadowColor = -1`（不透明白）。
 */
const LAUNCHER_ROWS = [
  ["PAGE_INDICATOR", "page_indicator", 2.0, 2.5, 2.0, 0.3, 0.4, 0.5, 0.45, 0.1, 40.0, 30.0],
  ["DOCK", "dock", 2.5, 2.0, 2.0, 0.3, 0.4, 0.5, 0.55, 0.1, 30.0, 80.0],
  ["CARD", "card", 2.0, 2.0, 2.0, 0.3, 0.4, 0.3, 0.25, 0.1, 40.0, 120.0],
  ["GROUP_CARD", "group_card", 2.0, 2.0, 2.0, 0.3, 0.4, 0.3, 0.25, 0.1, 40.0, 120.0],
  ["WIDGET", "widget", 2.0, 2.0, 2.0, 0.3, 0.4, 0.5, 0.55, 0.1, 30.0, 60.0],
  ["MIDDLE_FOLDER", "middle_folder", 2.5, 2.5, 1.5, 0.35, 0.35, 0.5, 0.42, 0.25, 40.0, 176.0],
  ["SMALL_FOLDER", "small_folder", 2.5, 2.0, 2.0, 0.3, 0.4, 0.3, 0.3, 0.1, 20.0, 75.0],
  ["BIG_FOLDER", "big_folder", 2.0, 2.0, 2.0, 0.3, 0.4, 0.3, 0.25, 0.1, 40.0, 120.0],
  ["MIDDLE_1_2_FOLDER", "middle_1_2_folder", 2.0, 2.0, 2.0, 0.2, 0.4, 0.3, 0.2, 0.1, 40.0, 176.0],
  ["MIDDLE_2_1_FOLDER", "middle_2_1_folder", 2.0, 2.0, 2.0, 0.3, 0.4, 0.4, 0.4, 0.1, 40.0, 60.0],
  ["PRESS_FEEDBACK", "press_feedback", 2.0, 2.5, 2.0, 0.3, 0.4, 0.45, 0.5, 0.1, 30.0, 50.0],
  ["TOGGLE_TOP_BUTTON", "toggle_top_button", 2.0, 2.5, 2.0, 0.35, 0.4, 0.5, 0.35, 0.1, 40.0, 30.0],
  ["TOGGLE_BOTTOM_BUTTON", "toggle_press_feedback", 2.0, 2.0, 2.0, 0.3, 0.4, 0.45, 0.3, 0.1, 40.0, 50.0],
  ["ALL_APPS_CATEGORY", "all_apps_category", 2.0, 2.0, 2.0, 0.3, 0.4, 0.3, 0.25, 0.1, 40.0, 120.0],
  ["ALL_APPS_SUGGESTION", "all_apps_suggestion", 2.0, 2.0, 2.0, 0.3, 0.4, 0.5, 0.55, 0.1, 30.0, 80.0],
  ["PREVIEW_PAGE_EFFECT", "preview_page_effect", 2.5, 2.0, 2.0, 0.3, 0.4, 0.3, 0.3, 0.1, 20.0, 75.0],
];

/** 03 §2.1 —— `getStrokeParams()` 里 16 个硬常量，不随场景变化。 */
const LAUNCHER_STROKE_CONSTS = {
  strokeLineVerticalNearFade: 4.5,
  strokeLineTransverseNearSolid: 0.05,
  strokeLineTransverseFarSolid: 0.05,
  ratio: 0.0,
  strokeLinePow: 0.8,
  strokeLineMix: 0.5,
};

/** 03 §2.2 —— `getInnerShadowParams()` 的硬常量。 */
const LAUNCHER_SHADOW_CONSTS = {
  offsetShadowMinAlpha: 0.0,
  noOffsetShadowAlpha: 0.04,
  offsetShadowX: 0.0,
  offsetShadowScaleX: 1.2,
  offsetShadowScaleY: 1.8,
  offsetShadowPow: 10.0,
  offsetShadowMix: 0.5,
  noOffsetShadowRange: 9.0,
  noOffsetShadowPow: 4.0,
  noOffsetShadowMix: 0.5,
};

const LAUNCHER_STROKE_COLOR = [1.0, 1.0, 1.0];
const LAUNCHER_STROKE_ALPHA = 0.35;
const LAUNCHER_SHADOW_COLOR = [1.0, 1.0, 1.0];

/** 每个场景在桌面上出现时的典型尺寸（px@density 2.75，用于页面排版，非证据值）。 */
const LAUNCHER_SIZES = {
  PAGE_INDICATOR: [180, 24], DOCK: [1032, 200], CARD: [340, 340], GROUP_CARD: [340, 340],
  WIDGET: [340, 200], MIDDLE_FOLDER: [340, 340], SMALL_FOLDER: [168, 168], BIG_FOLDER: [340, 340],
  MIDDLE_1_2_FOLDER: [340, 168], MIDDLE_2_1_FOLDER: [168, 340], PRESS_FEEDBACK: [168, 168],
  TOGGLE_TOP_BUTTON: [168, 168], TOGGLE_BOTTOM_BUTTON: [168, 168], ALL_APPS_CATEGORY: [340, 340],
  ALL_APPS_SUGGESTION: [340, 200], PREVIEW_PAGE_EFFECT: [168, 168],
};

export const LAUNCHER_SCENES = LAUNCHER_ROWS.map((r) => {
  const [name, scene, nearSolid, farSolid, farFade, alphaNear, alphaFar, transNearFade, transFarFade,
    shadowMaxAlpha, shadowRange, shadowY] = r;
  const edgeParams = {
    strokeLineColor: LAUNCHER_STROKE_COLOR,
    strokeLineVerticalNearSolid: nearSolid,
    strokeLineVerticalNearFade: LAUNCHER_STROKE_CONSTS.strokeLineVerticalNearFade,
    strokeLineVerticalFarSolid: farSolid,
    strokeLineVerticalFarFade: farFade,
    strokeLineAlphaNear: alphaNear * LAUNCHER_STROKE_ALPHA,
    strokeLineAlphaFar: alphaFar * LAUNCHER_STROKE_ALPHA,
    strokeLineTransverseNearSolid: LAUNCHER_STROKE_CONSTS.strokeLineTransverseNearSolid,
    strokeLineTransverseNearFade: transNearFade,
    strokeLineTransverseFarSolid: LAUNCHER_STROKE_CONSTS.strokeLineTransverseFarSolid,
    strokeLineTransverseFarFade: transFarFade,
    ratio: LAUNCHER_STROKE_CONSTS.ratio,
    strokeLinePow: LAUNCHER_STROKE_CONSTS.strokeLinePow,
    strokeLineMix: LAUNCHER_STROKE_CONSTS.strokeLineMix,
  };
  const shadowParams = {
    shadowColor: LAUNCHER_SHADOW_COLOR,
    offsetShadowMaxAlpha: shadowMaxAlpha,
    offsetShadowMinAlpha: LAUNCHER_SHADOW_CONSTS.offsetShadowMinAlpha,
    noOffsetShadowAlpha: LAUNCHER_SHADOW_CONSTS.noOffsetShadowAlpha,
    offsetShadowRange: shadowRange,
    offsetShadowX: LAUNCHER_SHADOW_CONSTS.offsetShadowX,
    offsetShadowY: shadowY,
    offsetShadowScaleX: LAUNCHER_SHADOW_CONSTS.offsetShadowScaleX,
    offsetShadowScaleY: LAUNCHER_SHADOW_CONSTS.offsetShadowScaleY,
    offsetShadowPow: LAUNCHER_SHADOW_CONSTS.offsetShadowPow,
    offsetShadowMix: LAUNCHER_SHADOW_CONSTS.offsetShadowMix,
    noOffsetShadowRange: LAUNCHER_SHADOW_CONSTS.noOffsetShadowRange,
    noOffsetShadowPow: LAUNCHER_SHADOW_CONSTS.noOffsetShadowPow,
    noOffsetShadowMix: LAUNCHER_SHADOW_CONSTS.noOffsetShadowMix,
  };
  return {
    id: `launcher:${name}`,
    label: `${name}（${scene}）`,
    group: "launcher",
    source: "03 §3",
    path: "native",
    edgeArray: strokeLineParamsToEdgeArray(edgeParams),
    shadowArray: innerShadowParamsToShadowArray(shadowParams),
    size: LAUNCHER_SIZES[name],
    corner: "min(w,h)*0.25",
    weight: 2.0,
  };
});

/* ------------------------------------------------------- 07 §3 Settings 圆返回键 */

/**
 * 真机逐像素剖面实测拟合（PLK110 / 1272×2772 @620dpi / 深色模式 / 按钮 155×155 r=77.5）。
 *
 * 实测（亮度相对按钮内部填充 40.5，圆心 r=0）：
 *   上弧  峰值 +38.5，半高宽 ≈2px，弧宽 ≈60°（70°..110°）
 *   下弧  峰值 +36.5，半高宽 ≈11px（连外溢共 ≈29px），弧宽 ≈80°（230°..310°）
 *   左右  0 —— 完全没有发光
 * 即「上细下宽、左右无」，不是完整一圈均匀内发光。
 *
 * 槽位语义（在本 AGSL 变体上逐槽实测，不是 08 §3.1 的字段名）：
 *   [3][4] 下弧 solid / fade 宽；[5][6] 上弧 solid / fade 宽；
 *   [7][8] 下弧 / 上弧 alpha；[9..12] 沿周向的渐变分数；[13] angle。
 * 07 §3.1 的 `edgeAlpha`（浅 0.2 / 深 0.6）与实测 alpha 0.18 对不上：0.6 会把
 * 上弧渲到 +127（真机 +38.5）。实测值取自深色模式；浅色模式顶部实测饱和到 255，
 * 分辨不出 alpha，故浅深共用同一组槽位。
 *
 * 未建模：下弧在按钮外的外溢（r=+2..+16 约 16px）与按钮外的暗阴影。
 * 本 shader 在 `shape < 0.001` 处直接 `return`，画不出形状边界以外；
 * 那一段在真机上由框架的 outer shadow / caustic 层绘制，本页不覆盖。
 */
export const SETTINGS_BACK = {
  id: "settings:back-circle-light",
  label: "设置返回键 · 浅色（拟合档）",
  group: "settings",
  source: "07 §3.1 + 真机逐像素剖面",
  path: "native",
  edgeArray: [1, 1, 1, 2, 40, 2, 8, 0.18, 0.17, 0.0, 0.4, 0.0, 0.3, 0, 3, 0.8],
  // 真机按钮内部是平的（整条竖线 40..42 无梯度），即没有内阴影层：全零 = shader 里三个
  // 分支都不进（[3][4] 与 [5] 都 ≤ EFFECT_PARAM_THRESHOLD），逐像素等于不画。
  shadowArray: new Array(16).fill(0),
};

export const SETTINGS_BACK_DARK = {
  ...SETTINGS_BACK,
  id: "settings:back-circle-dark",
  label: "设置返回键 · 深色（拟合档）",
};

/* --------------------------------------------- 08 §4 SystemUI 通知卡（浅 / 深两套）*/

const NOTIF_EDGE_CONST = {
  strokeLineVerticalNearSolid: 2.0,
  strokeLineVerticalNearFade: 6.0,
  strokeLineVerticalFarSolid: 2.0,
  strokeLineVerticalFarFade: 4.0,
  ratio: 0.0,
};

function notificationEdge({ color, alphaNear, alphaFar, transNearSolid, transNearFade, transFarSolid, transFarFade, pow, mix }) {
  return {
    ...NOTIF_EDGE_CONST,
    strokeLineColor: color,
    strokeLineAlphaNear: alphaNear,
    strokeLineAlphaFar: alphaFar,
    strokeLineTransverseNearSolid: transNearSolid,
    strokeLineTransverseNearFade: transNearFade,
    strokeLineTransverseFarSolid: transFarSolid,
    strokeLineTransverseFarFade: transFarFade,
    strokeLinePow: pow,
    strokeLineMix: mix,
  };
}

export const SYSTEMUI_NOTIFICATION = [
  {
    id: "systemui:notif-light",
    label: "通知卡 · 浅色",
    group: "systemui",
    source: "08 §4.1 / §4.3",
    path: "native",
    edgeArray: strokeLineParamsToEdgeArray(notificationEdge({
      color: [1, 1, 1], alphaNear: 0.5 * 0.8, alphaFar: 0.7 * 0.8,
      transNearSolid: 0.2, transNearFade: 0.4, transFarSolid: 0.2, transFarFade: 0.35,
      pow: 3.0, mix: 1.0,
    })),
    shadowArray: innerShadowParamsToShadowArray({
      shadowColor: WHITE3,
      offsetShadowMaxAlpha: 0.15 * 0.8,
      offsetShadowMinAlpha: 0.0,
      noOffsetShadowAlpha: 0.4 * 0.8,
      offsetShadowRange: 70.0,
      offsetShadowX: 0.0,
      offsetShadowY: 90.0,
      offsetShadowScaleX: 1.3,
      offsetShadowScaleY: 1.8,
      offsetShadowPow: 10.0,
      offsetShadowMix: 0.4,
      noOffsetShadowRange: 0.5,
      noOffsetShadowPow: 8.0,
      noOffsetShadowMix: 0.5,
    }),
    size: [328 * DEVICE_DENSITY, 60 * DEVICE_DENSITY], // 基准 328dp × 60dp（08 §4.5）
    corner: [16 * DEVICE_DENSITY, 16 * DEVICE_DENSITY, 16 * DEVICE_DENSITY, 16 * DEVICE_DENSITY], // 16dp（08 §4.5）
    weight: 2.0,
  },
  {
    id: "systemui:notif-dark",
    label: "通知卡 · 深色",
    group: "systemui",
    source: "08 §4.2 / §4.4",
    path: "native",
    edgeArray: strokeLineParamsToEdgeArray(notificationEdge({
      color: [1, 1, 1], alphaNear: 0.7 * 0.5, alphaFar: 1.0 * 0.5,
      transNearSolid: 0.15, transNearFade: 0.4, transFarSolid: 0.2, transFarFade: 0.4,
      pow: 4.46, mix: 1.1,
    })),
    shadowArray: innerShadowParamsToShadowArray({
      shadowColor: WHITE3,
      offsetShadowMaxAlpha: 1.0 * 0.05,
      offsetShadowMinAlpha: 0.0,
      noOffsetShadowAlpha: 0.06 * 0.05,
      offsetShadowRange: 36.0,
      offsetShadowX: 0.0,
      offsetShadowY: 24.0,
      offsetShadowScaleX: 1.22,
      offsetShadowScaleY: 1.22,
      offsetShadowPow: 20.0,
      offsetShadowMix: 0.47,
      noOffsetShadowRange: 36.0,
      noOffsetShadowPow: 20.0,
      noOffsetShadowMix: 0.47,
    }),
    size: [328 * DEVICE_DENSITY, 60 * DEVICE_DENSITY],
    corner: [16 * DEVICE_DENSITY, 16 * DEVICE_DENSITY, 16 * DEVICE_DENSITY, 16 * DEVICE_DENSITY],
    weight: 2.0,
  },
];

/* --------------------------------------- 11 §4 SystemUI 亮度滑块（竖向轨道） */

/**
 * 亮度条 / 音量条共用的四档，对应 `GradientStrokeLineAdapter` 的四个 seekbar 模板
 * （11 §4.1）：
 *   case 13 `seekBarBGTemplate`           未填充 · 亮
 *   case 20 `seekBarBGTemplateDark`       未填充 · 暗
 *   case 5  `seekBarProgressTemplate`     已填充 · 亮
 *   case 6  `seekBarProgressTemplateDark` 已填充 · 暗
 *
 * 与通知卡（`SYSTEMUI_NOTIFICATION`）的三点差异：
 *  1. 内阴影**没有**「组整体 alpha」那一层，下面的值就是生效值；
 *  2. 已填充档的浅色与深色逐值相同，深浅只体现在未填充档与混色上；
 *  3. 有第三张表 `u_opticsArray`（`OpticsParamsKt.java:14`），当前 app.js 不消费，留档。
 *
 * 几何（11 §3）：轨道 66dp 宽（填充 / 未填充同宽，`couiVerticalSeekBarProgressFull=true`）、
 * 圆角 31dp = `floor(62 × density) / 2`、weight 1.0（`OplusRoundParams.fullCornerParams`）。
 * 可视条长 = `viewH − 2 × 31dp`，随运行时 tile 高度变，这里用 200dp 当占位长度。
 */
const SEEKBAR_EDGE_BASE = {
  strokeLineColor: WHITE3,
  strokeLineVerticalNearSolid: 2.0,
  strokeLineVerticalFarSolid: 2.0,
  ratio: 0.0,
};

const SEEKBAR_SHADOW_BASE = {
  shadowColor: WHITE3,
  offsetShadowMinAlpha: 0.0,
  noOffsetShadowAlpha: 0.0,
  offsetShadowRange: 40.0,
  offsetShadowX: 0.0,
  offsetShadowScaleX: 1.3,
  offsetShadowScaleY: 1.3,
  offsetShadowPow: 20.0,
  offsetShadowMix: 0.5,
  noOffsetShadowRange: 6.0,
  noOffsetShadowPow: 20.0,
  noOffsetShadowMix: 0.3,
};

/**
 * 尺寸取自**实机 dump 的材质宿主视图**，不是布局里写的 dp。
 *
 * 亮度滑块 / 音量滑块的宿主是 `OplusQsVerticalSeekBar`。布局
 * `qs_toggle_slider_container_layout.xml` 写 `couiVerticalSeekBarBackgroundWidth = 66dp`，
 * 但 `OplusQsVerticalSeekBar.java:244-247` 的 `onMeasure` 里有一句
 * `setBackgroundWidth(getMeasuredWidth())` —— **布局值被实测宽度覆盖**。
 * 实机 dump（`brightness_slider` / `qs_volume_slider_layout` 下的 `vertical_seekbar`）：
 *   [660,1001][877,1484] → **217 × 483 px**
 * 圆角：`getRadius()` = `dpToPxWithInitialDensity(62.0f) / 2`，QS 网格用
 * `persist.sys.display.density = 560`（→ 3.5），故 62 × 3.5 / 2 = **108.5 px**，
 * 恰为 217 的一半 —— 全胶囊。
 *
 * ⚠ 未闭合：`getSeekBarActiveDrawable()` 把 `mClipProgressRect` 传给 drawable 当 bounds
 * （`OplusQsVerticalSeekBar.java:199`），所以 shader 真正看到的矩形可能比视图小
 * （端部内缩 = 圆角半径）。这里给的是**视图**尺寸，不是 `mClipProgressRect`。
 */
const SEEKBAR_SIZE = [217, 483];
const SEEKBAR_CORNER = [108.5, 108.5, 108.5, 108.5];

export const SYSTEMUI_SEEKBAR = [
  {
    id: "systemui:seekbar-bg-light",
    label: "亮度条 · 未填充（浅色）",
    group: "systemui",
    source: "11 §4.2 / §4.3",
    path: "native",
    edgeArray: strokeLineParamsToEdgeArray({
      ...SEEKBAR_EDGE_BASE,
      strokeLineVerticalNearFade: 7.0,
      strokeLineVerticalFarFade: 4.0,
      strokeLineAlphaNear: 0.25,
      strokeLineAlphaFar: 0.6,
      strokeLineTransverseNearSolid: 0.25,
      strokeLineTransverseNearFade: 0.35,
      strokeLineTransverseFarSolid: 0.15,
      strokeLineTransverseFarFade: 0.35,
      strokeLinePow: 10.0,
      strokeLineMix: 0.5,
    }),
    shadowArray: innerShadowParamsToShadowArray({
      ...SEEKBAR_SHADOW_BASE,
      offsetShadowMaxAlpha: 0.15,
      offsetShadowY: 65.0,
    }),
    opticsArray: [1, 1, 1, 0.05, 0, 0, 0.65, 0.85, 60, 0, 0, 0],
    size: SEEKBAR_SIZE,
    corner: SEEKBAR_CORNER,
    weight: 1.0,
  },
  {
    id: "systemui:seekbar-bg-dark",
    label: "亮度条 · 未填充（深色）",
    group: "systemui",
    source: "11 §4.2 / §4.3",
    path: "native",
    edgeArray: strokeLineParamsToEdgeArray({
      ...SEEKBAR_EDGE_BASE,
      strokeLineVerticalNearFade: 7.0,
      strokeLineVerticalFarFade: 4.0,
      strokeLineAlphaNear: 0.7 * 0.5, // withAlpha(…, 0.5f)
      strokeLineAlphaFar: 0.8 * 0.5,
      strokeLineTransverseNearSolid: 0.25,
      strokeLineTransverseNearFade: 0.35,
      strokeLineTransverseFarSolid: 0.15,
      strokeLineTransverseFarFade: 0.35,
      strokeLinePow: 10.0,
      strokeLineMix: 0.5,
    }),
    shadowArray: innerShadowParamsToShadowArray({
      ...SEEKBAR_SHADOW_BASE,
      offsetShadowMaxAlpha: 0.07,
      offsetShadowY: 65.0,
    }),
    opticsArray: [1, 1, 1, 0.01, 0, 0, 0.65, 0.85, 60, 0, 0, 0],
    size: SEEKBAR_SIZE,
    corner: SEEKBAR_CORNER,
    weight: 1.0,
  },
  {
    id: "systemui:seekbar-pg-light",
    label: "亮度条 · 已填充（浅色）",
    group: "systemui",
    source: "11 §4.2 / §4.3",
    path: "native",
    edgeArray: strokeLineParamsToEdgeArray({
      ...SEEKBAR_EDGE_BASE,
      strokeLineVerticalNearSolid: 1.0,
      strokeLineVerticalNearFade: 8.0,
      strokeLineVerticalFarFade: 1.0,
      strokeLineAlphaNear: 0.5,
      strokeLineAlphaFar: 0.5,
      strokeLineTransverseNearSolid: 0.3,
      strokeLineTransverseNearFade: 0.3,
      strokeLineTransverseFarSolid: 0.3,
      strokeLineTransverseFarFade: 0.3,
      strokeLinePow: 10.0,
      strokeLineMix: 0.5,
    }),
    shadowArray: innerShadowParamsToShadowArray({
      ...SEEKBAR_SHADOW_BASE,
      offsetShadowMaxAlpha: 0.15,
      offsetShadowY: 80.0,
    }),
    opticsArray: [1, 1, 1, 0.1, 0, 0, 0.65, 0.85, 60, 0, 0, 0],
    size: SEEKBAR_SIZE,
    corner: SEEKBAR_CORNER,
    weight: 1.0,
  },
  {
    id: "systemui:seekbar-pg-dark",
    label: "亮度条 · 已填充（深色）",
    group: "systemui",
    source: "11 §4.2 / §4.3",
    path: "native",
    edgeArray: strokeLineParamsToEdgeArray({
      ...SEEKBAR_EDGE_BASE,
      strokeLineVerticalNearSolid: 1.0,
      strokeLineVerticalNearFade: 8.0,
      strokeLineVerticalFarFade: 1.0,
      strokeLineAlphaNear: 0.5,
      strokeLineAlphaFar: 0.5,
      strokeLineTransverseNearSolid: 0.3,
      strokeLineTransverseNearFade: 0.3,
      strokeLineTransverseFarSolid: 0.3,
      strokeLineTransverseFarFade: 0.3,
      strokeLinePow: 10.0,
      strokeLineMix: 0.5,
    }),
    shadowArray: innerShadowParamsToShadowArray({
      ...SEEKBAR_SHADOW_BASE,
      offsetShadowMaxAlpha: 0.15,
      offsetShadowY: 80.0,
    }),
    opticsArray: [1, 1, 1, 0.1, 0, 0, 0.65, 0.85, 80, 0, 0, 0],
    size: SEEKBAR_SIZE,
    corner: SEEKBAR_CORNER,
    weight: 1.0,
  },
];

/* --------------------------------------------------------------- COUI 预设选项 */

export const COUI_PRESETS = [
  ...["0", "1", "2"].map((n) => ({
    id: `coui:edge-${n}`,
    label: `COUI 边缘光 ${n}`,
    group: "coui",
    source: "06 §4.1",
    path: "coui",
    edgeStyle: `EDGE_STYLE_${n}`,
    shadowStyle: `SHADOW_STYLE_${n}`,
    density: DEVICE_DENSITY, lineWidthDp: 2.5, lineAlpha: 0.3, lineAngle: 0.0,
    fadeWidthScale: 1.0, fadeAlphaInScale: 1.0, fadeAlphaOutScale: 1.0,
    fadeAlpha: [0.2, 0.0],
    size: [200, 200],
    corner: 48,
    weight: 2.0,
  })),
  {
    id: "coui:spec-default",
    label: "COUI 默认 Spec（唯一点亮外侧淡出）",
    group: "coui",
    source: "06 §4.4",
    path: "coui",
    edgeStyle: null,
    shadowStyle: null,
    useSpecDefault: true,
    density: DEVICE_DENSITY, lineWidthDp: 2.5, lineAlpha: 0.3, lineAngle: 0.0,
    fadeWidthScale: 1.0, fadeAlphaInScale: 1.0, fadeAlphaOutScale: 1.0,
    fadeAlpha: null,
    size: [300, 300],
    corner: 60,
    weight: 2.0,
  },
];

/* ------------------------------------------ 12 §5 状态栏区域 · 锁屏胶囊（16 槽）*/

/**
 * ⚠ 口径：**状态栏条本体没有任何 AGSL 材质、也没有自己的圆角**。
 * 它的背景是 `OplusBarBackgroundDrawable`（9-patch 竖向渐变 + `drawRect` 纯色），
 * 全程没有 RuntimeShader（12 §2）；状态栏是 1272×141 px 的矩形窗口，
 * 只做「避让屏幕圆角」的左右内边距（12 §3）。所以这里**没有**「状态栏条」的数组。
 *
 * 下面两条是状态栏区域里唯一带完整描边 + 内阴影的表面：**锁屏胶囊**
 * （`ViewBlurManager.CardType.CAPSULE`）。宿主是 `NotificationPanelView`
 * （`NotificationShade` 窗口），**不是 `StatusBar` 窗口** —— 唯一 include 点在
 * `res/layout/status_bar_expanded.xml:212`（12 §4.2）。它只是视觉上与状态栏同高。
 */
export const STATUSBAR_CAPSULE = [
  {
    id: "systemui:statusbar-capsule",
    label: "锁屏胶囊 · 单卡",
    group: "statusbar",
    source: "12 §5.2 / §5.3 / §5.4",
    path: "native",
    edgeArray: [1, 1, 1, 2.0, 8.0, 2.0, 5.0, 0.28, 0.56, 0.2, 0.6, 0.15, 0.55, 0.0, 4.5, 0.5],
    shadowArray: [1, 1, 1, 0.1, 0.0, 0.1, 80.0, 0.0, 36.0, 1.5, 1.5, 20.0, 0.4, 0.0, 20.0, 0.5],
    size: [176 * DEVICE_DENSITY, 48 * DEVICE_DENSITY],
    corner: 24 * DEVICE_DENSITY,
    weight: 2.0,
    note:
      "size = capsule_single_item_width 176dp × 3.875；corner = capsule_item_height/2 = 24dp × 3.875。" +
      "edgeArray[7][8] = 0.4×0.7 / 0.8×0.7，已被描边组的整体 alpha 0.7 乘过；" +
      "内阴影组整体 alpha = 1.0，故 [3][5] 取原值。weight = 2.0、type = G2" +
      "（SmoothRoundEx.createCapsuleCornerParams）。",
  },
  {
    id: "systemui:statusbar-capsule-multi",
    label: "锁屏胶囊 · 多卡",
    group: "statusbar",
    source: "12 §5.4 / §5.5",
    path: "native",
    edgeArray: [1, 1, 1, 2.0, 8.0, 2.0, 5.0, 0.28, 0.56, 0.2, 0.6, 0.15, 0.55, 0.0, 4.5, 0.5],
    shadowArray: [1, 1, 1, 0.1, 0.0, 0.1, 80.0, 0.0, 36.0, 1.5, 1.5, 20.0, 0.4, 0.0, 20.0, 0.5],
    size: [163 * DEVICE_DENSITY, 48 * DEVICE_DENSITY],
    corner: 24 * DEVICE_DENSITY,
    weight: 2.0,
    note: "size = capsule_multi_item_width 163dp × 3.875（sw600dp 为 187dp）。数组与单卡逐值相同：" +
      "描边 / 内阴影是静态单例，不随卡数或尺寸变化。",
  },
];

/* -------------------------------------- 09 §4 快捷开关 QS tile（16 槽，四档）*/

/**
 * 宿主是 `OplusQSTileViewImpl.mBg`（`qs_tile_bg` 这个 ImageView）→ `MixColorTileDrawable`
 * → `PlatformBlurDrawable` 的 AGSL RuntimeShader。数组是
 * `GradientStrokeLineParamsKt` 打包后的**最终值**（槽 4/6/10/12 已含 fade + solid）。
 *
 * 几何：`mBg = qs_quick_tile_size = 52dp`；圆角 = `bounds.height() / 2` = 26dp，
 * **由尺寸派生**（默认圆形形状 → `OvalOutlineProvider`），`CornerParams(type=FULL, weight=1.0)`。
 * weight 是 1.0 不是 2.0 —— `PlatformBlurDrawable` 里「G2 且 weight<2 → 抬到 2.0」
 * 的钳位对本档（type=FULL）不生效（09 §5.2）。
 */
/**
 * 尺寸取自**实机 dump 的材质宿主视图** `qs_tile_bg`（ImageView），不是布局里的 dp。
 *
 * ⚠ 本机控制中心用的是**分离式**磁贴（每个 tile 容器 id 都是
 * `oplus_separate_qs_resizeable_tile_container`，见 14 §3.2(B)），
 * 布局 dp 与 `qs_quick_tile_size = 52dp` **都不适用**。实机 dump：
 *   1×1 tile `qs_tile_bg` [128,1553][345,1770] → **217 × 217 px**
 *   2×1 tile `qs_tile_bg` [128,469][611,686]  → **483 × 217 px**
 * 半径 = 高度/2 = **108.5 px**（全胶囊）。
 *
 * 注意 QS 网格跑的是 **3.5**（`persist.sys.display.density = 560`），不是覆盖值
 * `wm density = 620`（3.875）—— 网格单元 76dp × 3.5 = 266px 与 dump 的 tile 间距吻合。
 * 同一屏上通知面板用的是 3.875，两套密度并存。
 */
const QS_TILE_SIZE = 217;
const QS_TILE_CORNER = 108.5;

export const QS_TILE = [
  {
    id: "systemui:qs-tile-inactive-light",
    label: "快捷开关 · 未激活（浅色）",
    group: "qs",
    source: "09 §4.1 / §4.2（MixColorTileStrokeLineAdapter:34）",
    path: "native",
    edgeArray: [1, 1, 1, 2.0, 9.0, 2.0, 6.0, 0.25, 0.6, 0.25, 0.6, 0.15, 0.5, 0.0, 10.0, 0.5],
    shadowArray: [1, 1, 1, 0.15, 0.0, 0.0, 40.0, 0.0, 36.0, 1.3, 1.3, 20.0, 0.5, 6.0, 20.0, 0.3],
    size: [QS_TILE_SIZE, QS_TILE_SIZE],
    corner: QS_TILE_CORNER,
    weight: 1.0,
    note: "描边色常量 #D8FFFFFF、内阴影色常量 #FFFFFFFF；tile 描边**没有**「组整体 alpha」，" +
      "生效值 = 常量原值（只有「未激活 · 深色」档经 withAlpha 把 alphaNear/alphaFar ×0.5）。",
  },
  {
    id: "systemui:qs-tile-active-light",
    label: "快捷开关 · 激活（浅色）",
    group: "qs",
    source: "09 §4.1 / §4.2（MixColorTileStrokeLineAdapter:35）",
    path: "native",
    edgeArray: [1, 1, 1, 1.0, 9.0, 2.0, 3.0, 0.5, 0.5, 0.3, 0.6, 0.3, 0.6, 0.0, 10.0, 0.5],
    shadowArray: [1, 1, 1, 0.15, 0.0, 0.0, 40.0, 0.0, 36.0, 1.3, 1.3, 20.0, 0.5, 6.0, 20.0, 0.3],
    size: [QS_TILE_SIZE, QS_TILE_SIZE],
    corner: QS_TILE_CORNER,
    weight: 1.0,
    note: "描边色常量 #C4FFFFFF。与「激活 · 深色」逐值相同。",
  },
  {
    id: "systemui:qs-tile-inactive-dark",
    label: "快捷开关 · 未激活（深色）",
    group: "qs",
    source: "09 §4.1 / §4.2（MixColorTileStrokeLineAdapter:36）",
    path: "native",
    edgeArray: [1, 1, 1, 2.0, 9.0, 2.0, 6.0, 0.35, 0.4, 0.25, 0.6, 0.15, 0.5, 0.0, 10.0, 0.5],
    shadowArray: [1, 1, 1, 0.07, 0.0, 0.0, 40.0, 0.0, 25.0, 1.3, 1.3, 20.0, 0.5, 6.0, 20.0, 0.3],
    size: [QS_TILE_SIZE, QS_TILE_SIZE],
    corner: QS_TILE_CORNER,
    weight: 1.0,
    note: "描边色常量 #A5FFFFFF；[7][8] = 0.7×0.5 / 0.8×0.5（withAlpha 只乘两个 alpha）。" +
      "内阴影是四档里唯一 [3]=0.07、[8]=25.0 的。",
  },
  {
    id: "systemui:qs-tile-active-dark",
    label: "快捷开关 · 激活（深色）",
    group: "qs",
    source: "09 §4.1 / §4.2（MixColorTileStrokeLineAdapter:37）",
    path: "native",
    edgeArray: [1, 1, 1, 1.0, 9.0, 2.0, 3.0, 0.5, 0.5, 0.3, 0.6, 0.3, 0.6, 0.0, 10.0, 0.5],
    shadowArray: [1, 1, 1, 0.15, 0.0, 0.0, 40.0, 0.0, 36.0, 1.3, 1.3, 20.0, 0.5, 6.0, 20.0, 0.3],
    size: [QS_TILE_SIZE, QS_TILE_SIZE],
    corner: QS_TILE_CORNER,
    weight: 1.0,
    note: "描边色常量 #C4FFFFFF；数值与「激活 · 浅色」逐字相同。",
  },
];

/* --------------------------------------------- 11 §3 音量调节条（16 槽，两档）*/

/**
 * ⚠ 这一条与其余 SystemUI 组件**不是同一条管线**：音量滑块没有
 * `u_edgeArray[16]`，它走音量专用的 AGSL `VolumeGradientStrokeShader`，
 * 写的是**命名 uniform**（`u_strokeLineColor` / `u_strokeLineWidthNear` / …），
 * 只复用 `GradientStrokeLineParams` 这个数据类（11 §11.3.1）。
 * 下面的 16 槽是按打包规则做的**等价映射 —— 推断，非逐字命中**。
 *
 * 本组件**没有内阴影**：整条音量路径无 `InnerShadowParams`，两处 `BlurConfig`
 * 的 stroke / innerShadow 显式传 null，故 shadowArray 全 0（11 §11.1）。
 * 浅色 / 深色数值完全相同：描边配置是静态单例 `OplusVolumeStrokeLine.defaultConfig()`，
 * 颜色恒为不透明白。两档真正的分叉在**触摸聚光**（endIntensity 0.4 → 0.2），不属于本 schema。
 *
 * 几何：**以实机 dump 的材质宿主视图为准**。`qs_volume_slider_layout` 下的
 * `vertical_seekbar` = [926,1001][1143,1484] → **217 × 483 px**（与亮度滑块同尺寸，
 * 都是一个 QS 网格单元宽）。圆角 = 全胶囊 = 217/2 = 108.5 px。
 *
 * ⚠ 两个未闭合点：
 *  1. 音量**内部胶囊**的资源值是 `volume_vertical_seek_bar_height = 46dp`
 *     （圆角 `volume_vertical_row_radius_os17 = 23dp`，outset 3px），
 *     与实测视图宽 217px 对不上 —— 和亮度条一样，
 *     `OplusQsVerticalSeekBar.onMeasure` 的 `setBackgroundWidth(getMeasuredWidth())`
 *     会覆盖布局值，46dp 可能只是内层视觉胶囊。本预设取**视图**尺寸。
 *  2. QS 区域跑的是 3.5（`persist.sys.display.density = 560`），不是 3.875。
 */
const VOL_SIZE = [217, 483];
const VOL_CORNER = 108.5;
/** 周向淡出分数 = 2r / (w + h)（11 §11.3.2）。 */
const VOL_TRANSVERSE_FADE = (2 * VOL_CORNER) / (VOL_SIZE[0] + VOL_SIZE[1]);

export const VOLUME_SLIDER = [
  {
    id: "systemui:volume-slider-light",
    label: "音量调节条 · 浅色（等价映射）",
    group: "volume",
    source: "11 §11.2 / §11.3",
    path: "native",
    edgeArray: [1, 1, 1, 0, 0, 0, 0, 0.24, 0.3, 0, VOL_TRANSVERSE_FADE, 0, VOL_TRANSVERSE_FADE, 0.0, 1, 1],
    shadowArray: new Array(16).fill(0),
    size: VOL_SIZE,
    corner: VOL_CORNER,
    weight: 0.0,
    note: "逐字命中值：白 #FFFFFFFF、alphaNear 0.24、alphaFar 0.3、" +
      "strokeLineWidth 6（裸 int 当 px 用）。[3][4][5][6] 全 0 = 无纵向描边带。" +
      "[9..12] 是周向淡出分数 = 2r/(w+h)，语义与 AGSL 变体的 uRectLineFade 一致。" +
      "⚠ baseRatio 0.054 是音量 shader 的 `u_ratio`（**进度**），而 16 槽变体里 [13] 是 " +
      "`uAngle`（相位），两者同名不同义 —— 该变体没有 u_ratio 槽，故 [13] 记 0.0。" +
      "weight 记 0.0：运行时 u_weight = 1.0 且 cornerType = FULL（普通圆角矩形，非 squircle）。",
  },
  {
    id: "systemui:volume-slider-dark",
    label: "音量调节条 · 深色（等价映射）",
    group: "volume",
    source: "11 §11.2 / §11.3",
    path: "native",
    edgeArray: [1, 1, 1, 0, 0, 0, 0, 0.24, 0.3, 0, VOL_TRANSVERSE_FADE, 0, VOL_TRANSVERSE_FADE, 0.0, 1, 1],
    shadowArray: new Array(16).fill(0),
    size: VOL_SIZE,
    corner: VOL_CORNER,
    weight: 0.0,
    note: "与浅色逐值相同：描边配置无 night 变体，颜色恒为 #FFFFFFFF。" +
      "[13] 同样记 0.0 —— baseRatio 是 u_ratio（进度），本 schema 无对应槽。",
  },
];

export const PRESET_GROUPS = [
  ["settings", "设置 · 圆形返回键"],
  ["qs", "控制中心 · 快捷开关"],
  ["systemui", "控制中心 · 通知卡 / 亮度条"],
  ["volume", "控制中心 · 音量条"],
  ["statusbar", "状态栏 · 锁屏胶囊"],
  ["launcher", "桌面 · 图标 / 文件夹 / 卡片"],
  ["coui", "COUI 自绘组件 · 边缘光"],
];

export const ALL_PRESETS = [
  ...COUI_PRESETS,
  ...LAUNCHER_SCENES,
  SETTINGS_BACK,
  SETTINGS_BACK_DARK,
  ...SYSTEMUI_NOTIFICATION,
  ...SYSTEMUI_SEEKBAR,
  ...STATUSBAR_CAPSULE,
  ...QS_TILE,
  ...VOLUME_SLIDER,
];

/**
 * 浅色 / 深色是同一档的两个变体。06 §4 的两个 COUI 预设表 LIGHT 与 DARK 逐值相同，
 * 真正的深浅差异来自调用方（07 §3.1 的 edgeAlpha / fadeIn、08 §4 的组整体 alpha、
 * 11 §4 的未填充档 alphaNear/alphaFar 与混色），所以只有这几组有可切换的兄弟预设。
 *
 * QS tile 的四档**不设兄弟**：未激活 / 激活与浅 / 深是两个独立维度，
 * 且「激活 · 浅」与「激活 · 深」逐值相同（见 09 §4）。
 * 音量条两档也不设兄弟：它们逐值相同，浅深分叉在触摸聚光，不在本 schema。
 */
export const THEME_SIBLING = {
  "settings:back-circle-light": "settings:back-circle-dark",
  "settings:back-circle-dark": "settings:back-circle-light",
  "systemui:notif-light": "systemui:notif-dark",
  "systemui:notif-dark": "systemui:notif-light",
  "systemui:seekbar-bg-light": "systemui:seekbar-bg-dark",
  "systemui:seekbar-bg-dark": "systemui:seekbar-bg-light",
  "systemui:seekbar-pg-light": "systemui:seekbar-pg-dark",
  "systemui:seekbar-pg-dark": "systemui:seekbar-pg-light",
};

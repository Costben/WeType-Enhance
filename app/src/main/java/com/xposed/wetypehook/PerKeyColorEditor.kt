package com.xposed.wetypehook

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.capsule.ContinuousRoundedRectangle
import com.xposed.wetypehook.wetype.settings.WeTypeKeyColorOverrides
import com.xposed.wetypehook.wetype.settings.PerKeyColorOverrides
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme


/** 键帽网格里的一颗键：写着存储用的 token，显示用 [label]。 */
private data class EditorKey(val token: String, val label: String, val weight: Float = 1f)

/** 一行键帽：左右缩进模仿真键盘的错位。 */
private data class EditorRow(val insetDp: Int, val keys: List<EditorKey>)

private fun letter(token: String): EditorKey =
    EditorKey(token, token.uppercase())

private fun fn(token: String, label: String, weight: Float = 1f): EditorKey =
    EditorKey(token, label, weight)

/**
 * 与宿主实际布局对齐的两套键位。
 *
 * token 必须与 [WeTypeKeyColorOverrides.tokenOf] 的输出逐字一致 —— 宿主键 id 里的布局前缀
 * （`S2_` / `S3_` / `S1_`）在归一化时已经被剥掉，所以这里直接写剥完的结果。
 *
 * 最下面那行按**真机渲染顺序**排，不是布局 JSON 的声明顺序：中文 26 键（S2）声明的是
 * `num, emoji, dot, enter, chSwitch, space`，真机上 `space` 排在 `chSwitch` 之前、`enter`
 * 落到最右，所以这里画成 `num, emoji, dot, space, lang, enter`，宽度权重取真机像素实测比值。
 * 标签也照真机：`dot` 是「，」、`enter` 是「换行」。英文 26 键（S3）在这行还多一颗 `@`，
 * 中文布局没有 —— 为了跟面前这台机器的键盘逐格对齐，这里不画。
 */
private val QWERTY_ROWS = listOf(
    EditorRow(
        0, listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p").map(::letter)
    ),
    EditorRow(
        14, listOf("a", "s", "d", "f", "g", "h", "j", "k", "l").map(::letter)
    ),
    EditorRow(
        0,
        listOf(
            fn("shift", "⇧", 1.5f), letter("z"), letter("x"), letter("c"), letter("v"),
            letter("b"), letter("n"), letter("m"), fn("delete", "⌫", 1.5f)
        )
    ),
    EditorRow(
        0,
        listOf(
            fn("num", "123", 1.6f), fn("emoji", "表情"), fn("dot", "，"),
            fn("space", "空格", 3.68f), fn("lang", "中/英", 1.15f), fn("enter", "换行", 2.37f)
        )
    )
)

private val T9_ROWS = listOf(
    EditorRow(
        0,
        listOf(
            fn("recycle", "还原", 1.3f), fn("1", "1"), fn("2", "2"), fn("3", "3"),
            fn("delete", "⌫", 1.3f)
        )
    ),
    EditorRow(
        0, listOf(fn("4", "4"), fn("5", "5"), fn("6", "6"), fn("newline_clear", "清空", 1.3f))
    ),
    EditorRow(
        0, listOf(fn("7", "7"), fn("8", "8"), fn("9", "9"), fn("emoji", "表情", 1.3f))
    ),
    EditorRow(
        0,
        listOf(
            fn("symbol", "符号", 1.3f), fn("num", "123", 1.3f), fn("space", "空格", 2f),
            fn("lang", "中/英", 1.4f), fn("enter", "回车", 1.3f)
        )
    )
)

private val TOKEN_LABELS: Map<String, String> =
    (QWERTY_ROWS + T9_ROWS).flatMap { it.keys }.associate { it.token to it.label }

/**
 * 逐键颜色编辑器。「更多样的按键颜色」开关打开后就地嵌在「按键颜色」卡片里，
 * 跟着当前键盘布局（QWERTY / T9）画出整张键盘，点任意一颗键帽改它自己的颜色。
 */
@Composable
internal fun PerKeyColorEditor(
    overridesJson: String,
    brandColor: Int,
    onOverridesChange: (String) -> Unit
) {
    var selectedKeyboardTab by rememberSaveable { mutableIntStateOf(0) }
    var editingToken by remember { mutableStateOf<String?>(null) }
    val overrides = remember(overridesJson) { WeTypeKeyColorOverrides.decode(overridesJson) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf("QWERTY", "T9").forEachIndexed { index, label ->
                val selected = selectedKeyboardTab == index
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (selected) MiuixTheme.colorScheme.primary.copy(alpha = 0.15f)
                            else MiuixTheme.colorScheme.surfaceContainerHigh
                        )
                        .clickable { selectedKeyboardTab = index }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        style = MiuixTheme.textStyles.main,
                        color = if (selected) MiuixTheme.colorScheme.primary
                        else MiuixTheme.colorScheme.onSurface,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        val rows = if (selectedKeyboardTab == 0) QWERTY_ROWS else T9_ROWS
        rows.forEachIndexed { index, row ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = row.insetDp.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                row.keys.forEach { key ->
                    Box(modifier = Modifier.weight(key.weight)) {
                        PerKeyColorButton(
                            key = key,
                            value = overrides.colors[key.token],
                            brandColor = brandColor,
                            onClick = { editingToken = key.token }
                        )
                    }
                }
            }
            if (index < rows.size - 1) {
                Spacer(modifier = Modifier.height(6.dp))
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MiuixTheme.colorScheme.surfaceContainerHigh)
                    .clickable {
                        onOverridesChange(
                            WeTypeKeyColorOverrides.encode(
                                PerKeyColorOverrides(enabled = overrides.enabled)
                            )
                        )
                    }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "清空全部",
                    style = MiuixTheme.textStyles.main,
                    color = ComposeColor(0xFFE53935),
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }

    editingToken?.let { token ->
        SetPerKeyColorDialog(
            keyLabel = TOKEN_LABELS[token] ?: token,
            currentValue = overrides.colors[token],
            brandColor = brandColor,
            onDismiss = { editingToken = null },
            onApply = { value ->
                onOverridesChange(WeTypeKeyColorOverrides.encode(overrides.withColor(token, value)))
                editingToken = null
            },
            onApplyLive = { value ->
                onOverridesChange(WeTypeKeyColorOverrides.encode(overrides.withColor(token, value)))
            }
        )
    }
}

/**
 * 单颗键帽。未设定时用与其它设置项一致的面板色，设定了就按色值铺满 —— 编辑时看到的就是
 * 键盘上会出现的底色，不需要额外图例。键面字色走 [perKeyLabelColor]，与真机上那条自动
 * 反色是同一个函数，所以这里看到的就是按下去之后键盘上的样子。
 */
@Composable
private fun PerKeyColorButton(
    key: EditorKey,
    value: String?,
    brandColor: Int,
    onClick: () -> Unit
) {
    val resolved = resolveOverrideColor(value, brandColor)
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(shape)
            .border(0.5.dp, MiuixTheme.colorScheme.outline.copy(alpha = 0.35f), shape)
            .background(resolved?.let { ComposeColor(it) } ?: MiuixTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = key.label,
            style = MiuixTheme.textStyles.body2,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = resolved?.let { ComposeColor(perKeyLabelColor(it)) }
                ?: MiuixTheme.colorScheme.onSurface,
            maxLines = 1
        )
    }
}

/** `null` = 跟随默认；其余返回实际会画上去的 ARGB。 */
private fun resolveOverrideColor(value: String?, brandColor: Int): Int? = when {
    value == null -> null
    value == WeTypeKeyColorOverrides.BRAND -> brandColor
    else -> parseHexColor(value)
}

@Composable
private fun SetPerKeyColorDialog(
    keyLabel: String,
    currentValue: String?,
    brandColor: Int,
    onDismiss: () -> Unit,
    onApply: (String?) -> Unit,
    onApplyLive: (String?) -> Unit
) {
    var customInput by rememberSaveable(keyLabel) {
        mutableStateOf(
            currentValue?.takeIf { it.startsWith("#") }?.let { formatArgb(parseHexColor(it) ?: brandColor) }
                ?: formatArgb(brandColor)
        )
    }

    OverlayDialog(
        show = true,
        title = "设置按键 [$keyLabel] 颜色",
        summary = "点键帽只改这一颗；未设定的键继续用键盘默认按键色",
        onDismissRequest = onDismiss
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            DialogOptionRow(
                text = "跟随默认",
                summary = "使用键盘默认按键色",
                selected = currentValue == null,
                onClick = { onApply(null) }
            )
            DialogOptionRow(
                text = "品牌色",
                summary = "跟随「品牌强调色」，改品牌色时这颗键跟着变",
                selected = currentValue == WeTypeKeyColorOverrides.BRAND,
                onClick = { onApply(WeTypeKeyColorOverrides.BRAND) }
            )
            DialogOptionRow(
                text = "自定义颜色",
                summary = "在下面输入 #RRGGBB 或 #AARRGGBB",
                selected = currentValue?.startsWith("#") == true,
                onClick = { }
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(ContinuousRoundedRectangle(999.dp))
                        .background(ComposeColor(parseHexColor(customInput) ?: brandColor))
                        .border(
                            1.dp,
                            MiuixTheme.colorScheme.outline,
                            ContinuousRoundedRectangle(999.dp)
                        )
                )
                Spacer(modifier = Modifier.width(12.dp))
                TextField(
                    value = customInput,
                    onValueChange = { raw ->
                        val sanitized = sanitizeHexColorInput(raw) ?: return@TextField
                        customInput = sanitized
                        parseHexColor(sanitized)?.let { color ->
                            onApplyLive(String.format("#%08X", color))
                        }
                    },
                    label = "#RRGGBB",
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(text = "完成", onClick = onDismiss)
            }
        }
    }
}

@Composable
private fun DialogOptionRow(
    text: String,
    summary: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (selected) MiuixTheme.colorScheme.primary.copy(alpha = 0.12f)
                else ComposeColor.Transparent
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = text,
                style = MiuixTheme.textStyles.main,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                color = if (selected) MiuixTheme.colorScheme.primary
                else MiuixTheme.colorScheme.onSurface
            )
            Text(
                text = summary,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
        }
        if (selected) {
            Text(
                text = "✓",
                style = MiuixTheme.textStyles.title4,
                color = MiuixTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

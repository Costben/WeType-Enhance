package com.xposed.wetypehook

import android.widget.Toast
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xposed.wetypehook.wetype.gesture.GestureAction
import com.xposed.wetypehook.wetype.settings.WeTypeGestureSettings
import com.xposed.wetypehook.wetype.settings.WeTypeSettings
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme


@Composable
internal fun GestureKeyBindingEditor(
    bindingsJson: String,
    onBindingsChange: (String) -> Unit
) {
    var selectedKeyboardTab by rememberSaveable { mutableIntStateOf(0) }
    var editingKey by remember { mutableStateOf<Char?>(null) }
    val bindings = remember(bindingsJson) { WeTypeGestureSettings.parseBindings(bindingsJson) }
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        // Tab 切换：26键 (QWERTY) 与 九宫格 (T9)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (selectedKeyboardTab == 0) MiuixTheme.colorScheme.primary.copy(alpha = 0.15f)
                        else MiuixTheme.colorScheme.surfaceContainerHigh
                    )
                    .clickable { selectedKeyboardTab = 0 }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "QWERTY",
                    style = MiuixTheme.textStyles.main,
                    color = if (selectedKeyboardTab == 0) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface,
                    fontWeight = if (selectedKeyboardTab == 0) FontWeight.Bold else FontWeight.Normal
                )
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (selectedKeyboardTab == 1) MiuixTheme.colorScheme.primary.copy(alpha = 0.15f)
                        else MiuixTheme.colorScheme.surfaceContainerHigh
                    )
                    .clickable { selectedKeyboardTab = 1 }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "T9",
                    style = MiuixTheme.textStyles.main,
                    color = if (selectedKeyboardTab == 1) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface,
                    fontWeight = if (selectedKeyboardTab == 1) FontWeight.Bold else FontWeight.Normal
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        if (selectedKeyboardTab == 0) {
            val row1 = listOf('q', 'w', 'e', 'r', 't', 'y', 'u', 'i', 'o', 'p')
            val row2 = listOf('a', 's', 'd', 'f', 'g', 'h', 'j', 'k', 'l')
            val row3 = listOf('z', 'x', 'c', 'v', 'b', 'n', 'm')

            // Row 1
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                row1.forEach { char ->
                    Box(modifier = Modifier.weight(1f)) {
                        GestureKeyButton(
                            keyLabel = char.uppercaseChar().toString(),
                            action = bindings[char] ?: GestureAction.None,
                            onClick = { editingKey = char }
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(6.dp))

            // Row 2
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                row2.forEach { char ->
                    Box(modifier = Modifier.weight(1f)) {
                        GestureKeyButton(
                            keyLabel = char.uppercaseChar().toString(),
                            action = bindings[char] ?: GestureAction.None,
                            onClick = { editingKey = char }
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(6.dp))

            // Row 3
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                row3.forEach { char ->
                    Box(modifier = Modifier.weight(1f)) {
                        GestureKeyButton(
                            keyLabel = char.uppercaseChar().toString(),
                            action = bindings[char] ?: GestureAction.None,
                            onClick = { editingKey = char }
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(6.dp))

            // Row 4: Space
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 40.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    GestureKeyButton(
                        keyLabel = "空格 (Space)",
                        action = bindings[' '] ?: GestureAction.None,
                        onClick = { editingKey = ' ' }
                    )
                }
            }
        } else {
            val t9Rows = listOf(
                listOf('1', '2', '3'),
                listOf('4', '5', '6'),
                listOf('7', '8', '9')
            )
            t9Rows.forEachIndexed { index, row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    row.forEach { char ->
                        Box(modifier = Modifier.weight(1f)) {
                            GestureKeyButton(
                                keyLabel = char.toString(),
                                action = bindings[char] ?: GestureAction.None,
                                onClick = { editingKey = char }
                            )
                        }
                    }
                }
                if (index < t9Rows.size - 1) {
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // 快捷操作按钮：恢复默认 / 清空全部
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
                        onBindingsChange(WeTypeSettings.DEFAULT_GESTURE_BINDINGS_JSON)
                        Toast.makeText(context, "已恢复默认预设 (Z=全选 / X=剪切 / C=复制 / V=粘贴)", Toast.LENGTH_SHORT).show()
                    }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "恢复默认预设",
                    style = MiuixTheme.textStyles.main,
                    color = MiuixTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium
                )
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MiuixTheme.colorScheme.surfaceContainerHigh)
                    .clickable {
                        onBindingsChange(WeTypeGestureSettings.serializeBindings(emptyMap()))
                        Toast.makeText(context, "已清空所有按键手势绑定", Toast.LENGTH_SHORT).show()
                    }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "清空全部按键",
                    style = MiuixTheme.textStyles.main,
                    color = ComposeColor(0xFFE53935),
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }

    // 动作选择对话框
    editingKey?.let { targetChar ->
        val currentAction = bindings[targetChar] ?: GestureAction.None
        val keyName = if (targetChar == ' ') "空格 (Space)" else targetChar.uppercaseChar().toString()
        val scrollState = rememberScrollState()

        OverlayDialog(
            show = true,
            title = "设置按键 [$keyName] 下滑动作",
            summary = "选择下滑此按键时触发的操作 (共 25 种动作)",
            onDismissRequest = { editingKey = null }
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                        .verticalScroll(scrollState)
                ) {
                    GestureAction.entries.forEach { action ->
                        val isSelected = action == currentAction
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (isSelected) MiuixTheme.colorScheme.primary.copy(alpha = 0.12f)
                                    else ComposeColor.Transparent
                                )
                                .clickable {
                                    val newMap = bindings.toMutableMap()
                                    if (action == GestureAction.None) {
                                        newMap.remove(targetChar)
                                    } else {
                                        newMap[targetChar] = action
                                    }
                                    onBindingsChange(WeTypeGestureSettings.serializeBindings(newMap))
                                    editingKey = null
                                    Toast.makeText(context, "[$keyName] 已绑定: ${action.title}", Toast.LENGTH_SHORT).show()
                                }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "${action.id}. ${action.title}",
                                    style = MiuixTheme.textStyles.main,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSelected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface
                                )
                                if (action.shortTitle.isNotEmpty() && action.shortTitle != "\\") {
                                    Text(
                                        text = "按键标签: ${action.shortTitle}",
                                        style = MiuixTheme.textStyles.body2,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                    )
                                }
                            }
                            if (isSelected) {
                                Text(
                                    text = "✓",
                                    style = MiuixTheme.textStyles.title4,
                                    color = MiuixTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        text = "取消",
                        onClick = { editingKey = null }
                    )
                }
            }
        }
    }
}

@Composable
internal fun GestureKeyButton(
    keyLabel: String,
    action: GestureAction,
    onClick: () -> Unit
) {
    val isBound = action != GestureAction.None && action != GestureAction.Disable
    val isDisable = action == GestureAction.Disable

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .border(
                width = if (isBound) 1.5.dp else 0.5.dp,
                color = when {
                    isBound -> MiuixTheme.colorScheme.primary
                    isDisable -> ComposeColor(0xFFE53935)
                    else -> MiuixTheme.colorScheme.outline.copy(alpha = 0.35f)
                },
                shape = RoundedCornerShape(6.dp)
            )
            .background(
                when {
                    isBound -> MiuixTheme.colorScheme.primary.copy(alpha = 0.12f)
                    isDisable -> ComposeColor(0xFFE53935).copy(alpha = 0.08f)
                    else -> MiuixTheme.colorScheme.surfaceContainerHigh
                }
            )
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = keyLabel,
                style = MiuixTheme.textStyles.body2,
                fontWeight = FontWeight.Bold,
                color = when {
                    isBound -> MiuixTheme.colorScheme.primary
                    isDisable -> ComposeColor(0xFFE53935)
                    else -> MiuixTheme.colorScheme.onSurface
                },
                maxLines = 1
            )
            Text(
                text = when {
                    isBound -> action.shortTitle
                    isDisable -> "禁用"
                    else -> "-"
                },
                fontSize = 9.sp,
                fontWeight = if (isBound) FontWeight.SemiBold else FontWeight.Normal,
                color = when {
                    isBound -> MiuixTheme.colorScheme.primary
                    isDisable -> ComposeColor(0xFFE53935)
                    else -> MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.6f)
                },
                maxLines = 1
            )
        }
    }
}

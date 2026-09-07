package com.xposed.wetypehook.wetype.gesture

/**
 * 完整 24 种按键下滑触发动作
 * 逆向还原自 WeType-Tool EnumC0089
 */
enum class GestureAction(
    val id: Int,
    val title: String,
    val shortTitle: String,
    val systemActionId: Int? = null
) {
    None(0, "未绑定", "\\"),
    SelectAll(1, "全选", "全选", android.R.id.selectAll),
    Cut(2, "剪切", "剪切", android.R.id.cut),
    Copy(3, "复制", "复制", android.R.id.copy),
    Paste(4, "粘贴", "粘贴", android.R.id.paste),
    Disable(5, "禁用下滑", ""),
    ParagraphStart(6, "段首", "段首"),
    ParagraphEnd(7, "段尾", "段尾"),
    SelectToParagraphStart(8, "选至段首", "选段首"),
    SelectToParagraphEnd(9, "选至段尾", "选段尾"),
    OpenClipboard(10, "剪切板", "剪切板"),
    OpenQuickPhrase(11, "常用语", "常用语"),
    CopyAll(12, "全复制", "全复制"),
    CutAll(13, "全剪切", "全剪切"),
    Undo(14, "撤销", "撤销", android.R.id.undo),
    Redo(15, "重做", "重做", android.R.id.redo),
    DocumentStart(16, "文首", "文首"),
    DocumentEnd(17, "文尾", "文尾"),
    SelectToDocumentStart(18, "选至文首", "选文首"),
    SelectToDocumentEnd(19, "选至文尾", "选文尾"),
    OpenSettings(20, "设置", "设置"),
    OpenFindWord(21, "手写找字", "找字"),
    MoveCursor(22, "滑移", "滑移"),
    MoveSelect(23, "滑选", "滑选");

    companion object {
        fun fromId(id: Int): GestureAction = entries.firstOrNull { it.id == id } ?: None

        /**
         * 默认的 QWERTY 键位绑定预设 (经典的 Ctrl+Z/X/C/V)
         */
        val defaultQwertyBindings: Map<Char, GestureAction> = mapOf(
            'z' to SelectAll,
            'x' to Cut,
            'c' to Copy,
            'v' to Paste
        )
    }
}

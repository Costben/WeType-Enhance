package com.xposed.wetypehook.wetype.gesture

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import com.xposed.wetypehook.xposed.Log
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * 手势动作全量执行器
 * 逆向完整还原自 WeType-Tool RunnableC0005
 */
object GestureActionExecutor {

    private const val TAG = "GestureActionExecutor"
    private val LINE_BREAK_CHARS = charArrayOf('\n', '\r', '\u2028', '\u2029')

    fun execute(action: GestureAction, view: View) {
        if (action == GestureAction.None || action == GestureAction.Disable) return

        val context = view.context ?: return
        val ims = resolveInputMethodService(context)

        // 1. 设置页直接唤起，无需依赖输入连接
        if (action == GestureAction.OpenSettings) {
            runCatching {
                val intent = Intent().apply {
                    setClassName(
                        "com.tencent.wetype",
                        "com.tencent.wetype.plugin.hld.reactnative.activity.ImeMainSettingActivity"
                    )
                    putExtra("com.wetype.tool.OPEN_HOSTED_SETTINGS", true)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                Log.i("[$TAG] Opened WeType hosted settings")
            }.onFailure {
                Log.e("[$TAG] Failed to open settings: ${it.message}")
            }
            return
        }

        if (ims == null) {
            Log.e("[$TAG] InputMethodService not found in Context hierarchy")
            return
        }

        // 2. 密码输入框安全保护 (避免在密码框触发复制/剪切等)
        val editorInfo = runCatching { ims.currentInputEditorInfo }.getOrNull()
        if (editorInfo != null && isPasswordField(editorInfo)) {
            Log.i("[$TAG] Ignored action $action on password field")
            return
        }

        // 3. 滑移 / 滑选控制
        if (action == GestureAction.MoveCursor || action == GestureAction.MoveSelect) {
            handleMoveCursorOrSelect(ims, view, isSelectMode = (action == GestureAction.MoveSelect))
            return
        }

        // 4. 面板拉起类 (剪贴板 / 常用语 / 手写找字)
        if (action == GestureAction.OpenClipboard || action == GestureAction.OpenQuickPhrase || action == GestureAction.OpenFindWord) {
            handleOpenPanel(view, action)
            return
        }

        // 5. 文本与选区编辑动作 (需要有效 InputConnection)
        val inputConnection = runCatching { ims.currentInputConnection }.getOrNull()
        if (inputConnection == null) {
            Log.e("[$TAG] Current InputConnection is null")
            return
        }

        when (action) {
            GestureAction.SelectAll -> {
                runCatching { inputConnection.finishComposingText() }
                val success = inputConnection.performContextMenuAction(android.R.id.selectAll)
                if (!success) {
                    val extracted = inputConnection.getExtractedText(ExtractedTextRequest(), 0)
                    val len = extracted?.text?.length ?: 0
                    if (len > 0) {
                        inputConnection.setSelection(0, len)
                    }
                }
                Log.i("[$TAG] Performed SelectAll (native=$success)")
            }

            GestureAction.Cut -> {
                runCatching { inputConnection.finishComposingText() }
                val success = inputConnection.performContextMenuAction(android.R.id.cut)
                if (!success) {
                    val selectedText = inputConnection.getSelectedText(0)?.toString()
                    if (!selectedText.isNullOrEmpty()) {
                        val cm = ims.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        cm?.setPrimaryClip(ClipData.newPlainText("text", selectedText))
                        inputConnection.commitText("", 1)
                    }
                }
                Log.i("[$TAG] Performed Cut (native=$success)")
            }

            GestureAction.Copy -> {
                runCatching { inputConnection.finishComposingText() }
                val success = inputConnection.performContextMenuAction(android.R.id.copy)
                if (!success) {
                    val selectedText = inputConnection.getSelectedText(0)?.toString()
                    if (!selectedText.isNullOrEmpty()) {
                        val cm = ims.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        cm?.setPrimaryClip(ClipData.newPlainText("text", selectedText))
                    }
                }
                Log.i("[$TAG] Performed Copy (native=$success)")
            }

            GestureAction.Paste -> {
                runCatching { inputConnection.finishComposingText() }
                val success = inputConnection.performContextMenuAction(android.R.id.paste)
                if (!success) {
                    val cm = ims.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    val clip = cm?.primaryClip
                    if (clip != null && clip.itemCount > 0) {
                        val text = clip.getItemAt(0)?.coerceToText(ims)?.toString()
                        if (!text.isNullOrEmpty()) {
                            inputConnection.commitText(text, 1)
                        }
                    }
                }
                Log.i("[$TAG] Performed Paste (native=$success)")
            }

            GestureAction.Undo,
            GestureAction.Redo -> {
                runCatching { inputConnection.finishComposingText() }
                val systemId = action.systemActionId
                val success = if (systemId != null) inputConnection.performContextMenuAction(systemId) else false
                if (!success) {
                    if (action == GestureAction.Undo) {
                        val down = KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_Z, 0, KeyEvent.META_CTRL_ON)
                        val up = KeyEvent(0, 0, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_Z, 0, KeyEvent.META_CTRL_ON)
                        inputConnection.sendKeyEvent(down)
                        inputConnection.sendKeyEvent(up)
                    } else {
                        val down = KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_Z, 0, KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON)
                        val up = KeyEvent(0, 0, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_Z, 0, KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON)
                        inputConnection.sendKeyEvent(down)
                        inputConnection.sendKeyEvent(up)
                    }
                }
                Log.i("[$TAG] Performed ${action.title} (native=$success)")
            }

            // 全选后延迟执行复制/剪切
            GestureAction.CopyAll,
            GestureAction.CutAll -> {
                runCatching { inputConnection.finishComposingText() }
                val selected = inputConnection.performContextMenuAction(android.R.id.selectAll)
                val targetAction = if (action == GestureAction.CopyAll) GestureAction.Copy else GestureAction.Cut
                view.postDelayed({
                    execute(targetAction, view)
                    Log.i("[$TAG] Delayed executed: ${action.title}")
                }, 32L)
            }

            // 段首 / 段尾 / 选至段首 / 选至段尾
            GestureAction.ParagraphStart,
            GestureAction.ParagraphEnd,
            GestureAction.SelectToParagraphStart,
            GestureAction.SelectToParagraphEnd -> {
                handleParagraphNavigation(inputConnection, action)
            }

            // 文首 / 文尾 / 选至文首 / 选至文尾
            GestureAction.DocumentStart,
            GestureAction.DocumentEnd,
            GestureAction.SelectToDocumentStart,
            GestureAction.SelectToDocumentEnd -> {
                handleDocumentNavigation(inputConnection, action)
            }

            else -> {}
        }
    }

    @Volatile
    var activeInputMethodService: WeakReference<InputMethodService>? = null

    /**
     * 多层解析 InputMethodService：
     * 1. 优先使用全局缓存的活跃实例 (由 onStartInputView 注入)
     * 2. 回溯 ContextWrapper 树
     * 3. 反射 ActivityThread.mServices 兜底
     */
    internal fun resolveInputMethodService(context: Context): InputMethodService? {
        activeInputMethodService?.get()?.let { return it }
        var current: Context? = context
        for (i in 0 until 12) {
            if (current is InputMethodService) {
                activeInputMethodService = WeakReference(current)
                return current
            }
            current = (current as? ContextWrapper)?.baseContext ?: break
        }
        val fromThread = resolveFromActivityThread()
        if (fromThread != null) {
            activeInputMethodService = WeakReference(fromThread)
            return fromThread
        }
        return null
    }

    private fun resolveFromActivityThread(): InputMethodService? = runCatching {
        val activityThreadClass = Class.forName("android.app.ActivityThread")
        val currentActivityThread = activityThreadClass
            .getDeclaredMethod("currentActivityThread")
            .apply { isAccessible = true }
            .invoke(null) ?: return@runCatching null
        val services = activityThreadClass
            .getDeclaredField("mServices")
            .apply { isAccessible = true }
            .get(currentActivityThread) as? Map<*, *> ?: return@runCatching null
        services.values.filterIsInstance<InputMethodService>().firstOrNull()
    }.getOrNull()

    /**
     * 密码输入框判定
     */
    private fun isPasswordField(info: EditorInfo): Boolean {
        val inputType = info.inputType
        val classType = inputType and EditorInfo.TYPE_MASK_CLASS
        val variation = inputType and EditorInfo.TYPE_MASK_VARIATION

        if (classType == EditorInfo.TYPE_CLASS_TEXT) {
            if (variation == EditorInfo.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == EditorInfo.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == EditorInfo.TYPE_TEXT_VARIATION_WEB_PASSWORD
            ) {
                return true
            }
        } else if (classType == EditorInfo.TYPE_CLASS_NUMBER) {
            if (variation == EditorInfo.TYPE_NUMBER_VARIATION_PASSWORD) {
                return true
            }
        }
        return false
    }

    /**
     * 段首/段尾/选至段首/选至段尾计算
     */
    private fun handleParagraphNavigation(ic: InputConnection, action: GestureAction) {
        runCatching { ic.finishComposingText() }
        val textBefore = ic.getTextBeforeCursor(2048, 0)?.toString() ?: ""
        val textAfter = ic.getTextAfterCursor(2048, 0)?.toString() ?: ""

        // 计算段落起始位置
        val lastBreakIndexBefore = textBefore.lastIndexOfAny(LINE_BREAK_CHARS)
        val charsToLineStart = if (lastBreakIndexBefore != -1) {
            textBefore.length - lastBreakIndexBefore - 1
        } else {
            textBefore.length
        }

        // 计算段落结束位置
        val firstBreakIndexAfter = textAfter.indexOfAny(LINE_BREAK_CHARS)
        val charsToLineEnd = if (firstBreakIndexAfter != -1) {
            firstBreakIndexAfter
        } else {
            textAfter.length
        }

        val currentStart = 0 // relative
        val lineStart = -charsToLineStart
        val lineEnd = charsToLineEnd

        // 转为输入框全局绝对偏移
        val surrounding = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { ic.getSurroundingText(65536, 65536, 0) }.getOrNull()
        } else null
        val extracted = ic.getExtractedText(ExtractedTextRequest(), 0)
        val selStart = surrounding?.selectionStart ?: extracted?.selectionStart ?: 0
        val selEnd = surrounding?.selectionEnd ?: extracted?.selectionEnd ?: 0

        val targetStart = selStart - charsToLineStart
        val targetEnd = selEnd + charsToLineEnd

        when (action) {
            GestureAction.ParagraphStart -> ic.setSelection(targetStart, targetStart)
            GestureAction.ParagraphEnd -> ic.setSelection(targetEnd, targetEnd)
            GestureAction.SelectToParagraphStart -> ic.setSelection(targetStart, selEnd)
            GestureAction.SelectToParagraphEnd -> ic.setSelection(selStart, targetEnd)
            else -> {}
        }
        Log.i("[$TAG] Executed paragraph action ${action.title}: target range ($targetStart, $targetEnd)")
    }

    /**
     * 文首/文尾/选至文首/选至文尾计算
     */
    private fun handleDocumentNavigation(ic: InputConnection, action: GestureAction) {
        runCatching { ic.finishComposingText() }
        val surrounding = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { ic.getSurroundingText(65536, 65536, 0) }.getOrNull()
        } else null
        val extracted = ic.getExtractedText(ExtractedTextRequest(), 0)
        val totalLength = surrounding?.text?.length
            ?: extracted?.text?.length
            ?: ((ic.getTextBeforeCursor(65536, 0)?.length ?: 0) + (ic.getTextAfterCursor(65536, 0)?.length ?: 0))
        if (totalLength < 0) return
        val selStart = (surrounding?.selectionStart ?: extracted?.selectionStart ?: 0).coerceIn(0, totalLength)
        val selEnd = (surrounding?.selectionEnd ?: extracted?.selectionEnd ?: 0).coerceIn(0, totalLength)

        val minSel = minOf(selStart, selEnd)
        val maxSel = maxOf(selStart, selEnd)

        when (action) {
            GestureAction.DocumentStart -> ic.setSelection(0, 0)
            GestureAction.DocumentEnd -> ic.setSelection(totalLength, totalLength)
            GestureAction.SelectToDocumentStart -> ic.setSelection(0, maxSel)
            GestureAction.SelectToDocumentEnd -> ic.setSelection(minSel, totalLength)
            else -> {}
        }
        Log.i("[$TAG] Executed document action ${action.title} on totalLength $totalLength")
    }

    /**
     * 滑移 / 滑选控制 (通过相对移动光标或选区)
     */
    private fun handleMoveCursorOrSelect(ims: InputMethodService, view: View, isSelectMode: Boolean) {
        runCatching {
            val ic = ims.currentInputConnection ?: return
            val parent = view.parent as? ViewGroup ?: return
            val height = if (view.height > 0) view.height else parent.height
            if (height <= 0) return

            val extracted = ic.getExtractedText(ExtractedTextRequest(), 0)
            val selStart = extracted?.selectionStart ?: 0
            val selEnd = extracted?.selectionEnd ?: 0

            // 移动一步光标
            if (isSelectMode) {
                ic.setSelection(selStart, (selEnd + 1).coerceAtLeast(0))
            } else {
                val newPos = (selEnd + 1).coerceAtLeast(0)
                ic.setSelection(newPos, newPos)
            }
            Log.i("[$TAG] Handled move action (selectMode=$isSelectMode)")
        }.onFailure {
            Log.e("[$TAG] Failed to move cursor: ${it.message}")
        }
    }

    /**
     * 键盘面板拉起 (剪贴板 panelId=4, 常用语 panelId=8, 找字)
     */
    private fun handleOpenPanel(view: View, action: GestureAction) {
        val targetPanelId = when (action) {
            GestureAction.OpenClipboard -> 4
            GestureAction.OpenQuickPhrase -> 8
            GestureAction.OpenFindWord -> 21
            else -> return
        }

        runCatching {
            val rootView = view.rootView ?: return
            // 递归或从常见子视图中查找包含 showPanel / setPanelType 的控制器
            val panelController = findPanelController(rootView)
            if (panelController != null) {
                invokeShowPanel(panelController, view, targetPanelId)
                Log.i("[$TAG] Opened panel $targetPanelId via controller")
            } else {
                Log.i("[$TAG] Panel controller not found, attempting fallback")
            }
        }.onFailure {
            Log.e("[$TAG] Failed to open panel: ${it.message}")
        }
    }

    private fun findPanelController(rootView: View): Any? {
        // 查找持有面板控制引用的 tag 或字段
        return rootView.tag ?: runCatching {
            val field = rootView.javaClass.declaredFields.firstOrNull {
                it.type.name.startsWith("com.tencent.wetype.plugin.hld")
            }
            field?.isAccessible = true
            field?.get(rootView)
        }.getOrNull()
    }

    private fun invokeShowPanel(controller: Any, view: View, panelId: Int) {
        val methods = controller.javaClass.declaredMethods
        val showMethod = methods.firstOrNull { it.name == "showPanel" || (it.parameterTypes.size == 2 && it.parameterTypes[0] == Int::class.javaPrimitiveType) }
            ?: methods.firstOrNull { it.parameterTypes.size == 1 && View::class.java.isAssignableFrom(it.parameterTypes[0]) }

        if (showMethod != null) {
            showMethod.isAccessible = true
            if (showMethod.parameterTypes.size == 2) {
                showMethod.invoke(controller, panelId, view)
            } else {
                showMethod.invoke(controller, view)
            }
        }
    }
}

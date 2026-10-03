package com.xposed.wetypehook.wetype.host

import android.content.Context
import android.inputmethodservice.InputMethodService
import android.media.AudioRecord
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import com.xposed.wetypehook.xposed.loadClassOrNull
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Stage 1 的契约声明。
 *
 * 解析顺序固定：**字符串/枚举锚 → 形状锚 → 名字候选**。结构锚必须唯一命中且通过语义校验，
 * 否则记进报告并让下一级接手；名字候选是最后手段，保留既有行为。
 *
 * 声明顺序即依赖顺序：后面的契约可以读取前面契约的结果（[HostContext.classOf] 等）。
 */
internal object HostContractId {
    const val PANEL_ENUM = "panel.enum"
    const val PANEL_VALUE = "panel.value"
    const val PANEL_MANAGER = "panel.manager"
    const val PANEL_MANAGER_INSTANCE = "panel.manager.instance"
    const val PANEL_SWITCH_INT = "panel.switch.int"
    const val PANEL_SWITCH_ENUM = "panel.switch.enum"
    const val PANEL_CLIPBOARD_CONSTANT = "panel.constant.clipboard"
    const val CLIPBOARD_CANDIDATE_VIEW = "clipboard.candidate.view"
    const val CLIPBOARD_CANDIDATE_GETTER = "clipboard.candidate.getter"
    const val CLIPBOARD_ENGINE = "clipboard.engine"
    const val CLIPBOARD_ENGINE_INSTANCE = "clipboard.engine.instance"
    const val CLIPBOARD_PENDING_GETTER = "clipboard.pending.getter"
    const val CLIPBOARD_PENDING_EMIT = "clipboard.pending.emit"
    const val CLIPBOARD_ACTION = "clipboard.action"
    const val CLIPBOARD_ACTION_INSTANCE = "clipboard.action.instance"
    const val CLIPBOARD_ACTION_DISPATCH = "clipboard.action.dispatch"
    const val CLIPBOARD_IME_SERVICE = "clipboard.ime.service"
    const val CLIPBOARD_IME_SERVICE_KICK = "clipboard.ime.service.kick"
    const val CLIPBOARD_IME_SERVICE_COMPANION = "clipboard.ime.service.companion"
    const val CLIPBOARD_HEIGHT_MANAGER = "clipboard.height.manager"
    const val CLIPBOARD_HEIGHT_SET_TEXT = "clipboard.height.set.text"
    const val CLIPBOARD_HEIGHT_SET_CHAR = "clipboard.height.set.char"
    const val RESOURCE_CLASS_ID = "resource.class.id"
    const val RESOURCE_CLASS_DRAWABLE = "resource.class.drawable"
    const val RESOURCE_CLASS_ATTR = "resource.class.attr"
    const val RESOURCE_CLASS_COLOR = "resource.class.color"
    const val RESOURCE_CLASS_DIMEN = "resource.class.dimen"
    const val CLIPBOARD_MANAGER = "clipboard.manager"
    const val CLIPBOARD_ITEM = "clipboard.item"
    const val CLIPBOARD_ENTRIES_HOST = "clipboard.entries.host"
    const val CLIPBOARD_ADAPTER = "clipboard.adapter"
    const val CLIPBOARD_DAO_FACTORY = "clipboard.dao.factory"
    const val CLIPBOARD_SCROLLVIEW = "clipboard.scrollview"
    const val CLIPBOARD_IMAGE_CARD = "clipboard.image.card"
    const val CLIPBOARD_PREVIEW_HOST = "clipboard.preview.host"
    const val CLIPBOARD_SCALE_INSTANCE = "clipboard.scale.instance"
    const val CLIPBOARD_SCALE_STATIC = "clipboard.scale.static"
    const val BITMAP_UTIL = "bitmap.util"
    const val VIEW_RADIUS_LAYOUT = "view.radius.layout"
    const val TRANSLATOR_TOP_VIEW = "translator.top.view"
    const val STRIP_FLOAT_PANEL = "strip.float.panel"
    const val STRIP_SKIN_BINDER = "strip.skin.binder"
    const val TRANSLATOR_DROPDOWN_ITEM = "translator.dropdown.item"
    const val IMAGE_PREVIEW_KEYBOARD = "image.preview.keyboard"
    const val IME_EDIT_TEXT = "view.ime.edit.text"
    const val TRANSLATOR_DROPDOWN_LIST = "translator.dropdown.list"
    const val GLIDE_ENTRY = "glide.entry"
    const val HOST_CRYPTO_FILE = "host.crypto.file"
    const val HOST_HASH_FILE = "host.hash.file"

    // ---- 语音（把宿主识别引擎借给系统识别服务） ----
    const val VOICE_SINGLETON = "voice.singleton"
    const val VOICE_SINGLETON_INSTANCE = "voice.singleton.instance"
    const val VOICE_TRANSCRIPT = "voice.transcript"
    const val VOICE_START = "voice.start"
    const val VOICE_RECORDER = "voice.recorder"
    const val VOICE_RECORDER_RECORD = "voice.recorder.record"
    const val VOICE_RECORDER_MODE = "voice.recorder.mode"
    const val VOICE_RECORDER_INIT = "voice.recorder.init"
    const val VOICE_RECORDER_TEARDOWN = "voice.recorder.teardown"
    const val VOICE_GATE = "voice.gate"
}

// 名字候选表：3.5.3 / 3.5.4 的实测值，仅作结构锚失效时的最后手段。
private const val PANEL_ENUM_CLASS = "com.tencent.wetype.plugin.hld.keyboard.t"
private const val PANEL_MANAGER_CLASS = "com.tencent.wetype.plugin.hld.model.N"
private const val CLIPBOARD_ENGINE_CLASS = "com.tencent.wetype.plugin.hld.model.i0"
private const val CANDIDATE_VIEW_CLASS = "com.tencent.wetype.plugin.hld.candidate.ImeCandidateView"
private const val CLIPBOARD_ACTION_CLASS = "com.tencent.wetype.plugin.hld.key.d"
private const val IME_SERVICE_CLASS = "com.tencent.wetype.plugin.hld.WxHldService"
private const val HEIGHT_MANAGER_CLASS = "com.tencent.wetype.plugin.hld.translatingwhilewriting.q"

/**
 * R 类**类名**是 R8 产物（`plugin.hld.s` 这类单字母短名），每次宿主更新都可能变；
 * 而 R 类**字段名**是源码标识符，能活过 R8。所以这几条契约按字段名锚定，短名只作兜底。
 */
private const val RESOURCE_CLASS_ID_NAME = "com.tencent.wetype.plugin.hld.s"
private const val RESOURCE_CLASS_DRAWABLE_NAME = "com.tencent.wetype.plugin.hld.r"
private const val RESOURCE_CLASS_ATTR_NAME = "com.tencent.wetype.plugin.hld.o"
private const val RESOURCE_CLASS_COLOR_NAME = "com.tencent.wetype.plugin.hld.p"
private const val RESOURCE_CLASS_DIMEN_NAME = "com.tencent.wetype.plugin.hld.q"

private const val PANEL_CUSTOM_PHRASE_AND_CLIPBOARD = "CustomPhraseAndClipboard"
private const val PANEL_FIND_WORD_T9 = "HandwriteFindWordT9"
private const val PANEL_IMAGE_PREVIEW = "ImagePreview"

/**
 * 剪贴板条目 / 图片 / 搜索链的短名候选表：3.5.3 / 3.5.4 / 4.0.0 的实测值，
 * 只在结构锚失效时接手。这些位置以前散落在各 hook 文件里直接 `Class.forName`。
 */
private const val CLIPBOARD_MANAGER_CLASS = "com.tencent.wetype.plugin.hld.clipboard.B"
private const val CLIPBOARD_ITEM_CLASS = "com.tencent.wetype.plugin.hld.clipboard.C"
private const val CLIPBOARD_ENTRIES_HOST_CLASS = "com.tencent.wetype.plugin.hld.clipboard.z"
private const val CLIPBOARD_ADAPTER_CLASS = "com.tencent.wetype.plugin.hld.clipboard.v"
private const val CLIPBOARD_DAO_FACTORY_CLASS = "com.tencent.wetype.plugin.hld.dao.c"
private const val CLIPBOARD_SCROLLVIEW_CLASS =
    "com.tencent.wetype.plugin.hld.clipboard.ImeClipboardScrollView"
private const val CLIPBOARD_IMAGE_CARD_CLASS = "com.tencent.wetype.plugin.hld.keyboard.k"
private const val CLIPBOARD_PREVIEW_HOST_CLASS = "com.tencent.wetype.plugin.hld.keyboard.l"
private const val BITMAP_UTIL_CLASS = "com.tencent.wetype.plugin.hld.utils.e"
private const val VIEW_RADIUS_LAYOUT_CLASS =
    "com.tencent.wetype.plugin.hld.view.ImeRadiusConstraintLayout"
private const val TRANSLATOR_TOP_VIEW_CLASS =
    "com.tencent.wetype.plugin.hld.translatingwhilewriting.k"

/** 浮窗管理类的历史短名（3.5.3 / 3.5.4 是 `float.f`，jadx 反编译成 `p000float.f`）。 */
private val FLOAT_PANEL_CLASSES = arrayOf(
    "com.tencent.wetype.plugin.hld.float.f",
    "com.tencent.wetype.plugin.hld.p000float.f"
)

/** 皮肤绑定工具类的历史短名。 */
private const val SKIN_BINDER_CLASS = "com.tencent.wetype.skin.utils.d"

/** 下拉语言项数据类的历史短名。 */
private const val DROPDOWN_ITEM_CLASS = "com.tencent.wetype.plugin.hld.translatingwhilewriting.m"

/** 图片预览页键盘的历史短名。 */
private const val PREVIEW_KEYBOARD_CLASS = "com.tencent.wetype.plugin.hld.keyboard.S33ImagePreviewKeyboard"

/** IME 输入框的历史短名。 */
private const val IME_EDIT_TEXT_CLASS = "com.tencent.wetype.plugin.hld.view.imeedittext.ImeEditText"

/** 翻译下拉列表（`translatingwhilewriting.d`，RecyclerView 子类）的历史短名。 */
private const val DROPDOWN_LIST_CLASS =
    "com.tencent.wetype.plugin.hld.translatingwhilewriting.d"

/** Glide 入口类与 RequestListener 接口的历史短名（Glide 自带混淆，两版一致）。 */
private const val GLIDE_ENTRY_CLASS = "com.bumptech.glide.c"
private const val GLIDE_LISTENER_INTERFACE = "q1.h"

/** 宿主文件加解密 / 摘要工具的历史短名（`k6` 包在 3.5.4 与 4.0.0 上一致）。 */
private const val CRYPTO_FILE_CLASS = "k6.a"
private const val HASH_FILE_CLASS = "k6.e"

/**
 * 整包扫描的包名。包名与成员形状一样能活过 R8 —— 混淆只改类名/成员名，不改包归属，
 * 也不改方法签名里的类型关系。
 */
private const val FLOAT_PACKAGE = "com.tencent.wetype.plugin.hld.float"
private const val SKIN_UTILS_PACKAGE = "com.tencent.wetype.skin.utils"
private const val TRANSLATING_PACKAGE = "com.tencent.wetype.plugin.hld.translatingwhilewriting"
private const val KEYBOARD_PACKAGE = "com.tencent.wetype.plugin.hld.keyboard"
private const val IME_EDIT_TEXT_PACKAGE = "com.tencent.wetype.plugin.hld.view.imeedittext"

/** Glide 用的是库自己的真实包名（不是宿主 R8 产物），包内按形状取入口类。 */
private const val GLIDE_PACKAGE = "com.bumptech.glide"

/** 宿主加解密 / 摘要工具所在的混淆包；包名比类名稳，但仍属兜底级锚点。 */
private const val CRYPTO_PACKAGE = "k6"

/** 尺寸换算：3.5.4 是实例单例 `n1`，4.0.0 换成 `m1`（形状不变，短名换了）。 */
private val CLIPBOARD_SCALE_INSTANCE_CLASSES = arrayOf(
    "com.tencent.wetype.plugin.hld.utils.n1",
    "com.tencent.wetype.plugin.hld.utils.m1"
)

/** 原始像素换算：3.5.4 是静态工具 `r1`，4.0.0 换成 `q1`。 */
private val CLIPBOARD_SCALE_STATIC_CLASSES = arrayOf(
    "com.tencent.wetype.plugin.hld.utils.r1",
    "com.tencent.wetype.plugin.hld.utils.q1"
)

/**
 * 宿主自己写进日志的 TAG / 文本（`WxIme.ImeClipboardMgr` 这类）。
 *
 * R8 不改字符串常量，而这些串在实测里只出现在各自类体内 —— 是「不写死混淆类名」的首选锚。
 * 每条给两个以上，是为了将来某条日志被删时判据整体失效并退回名字候选，而不是锚错类。
 */
private val CLIPBOARD_MANAGER_ANCHORS = listOf(
    "WxIme.ImeClipboardMgr",
    "clearExpireRecord start sort delete"
)
private val CLIPBOARD_ITEM_ANCHORS = listOf(", createTime:", ", expireTimestamp:", ", pathType:")
private val CLIPBOARD_ENTRIES_HOST_ANCHORS = listOf(
    "fillContent start uiOrder:",
    "WxIme.ImeClipboardListAdapter"
)
private val CLIPBOARD_ADAPTER_ANCHORS = listOf("clipboardList[dataPos]", "onBindViewHolder ")
private const val CLIPBOARD_DAO_FACTORY_ANCHOR = "DatabaseManager"
private const val CLIPBOARD_SCROLLVIEW_ANCHOR = "WxIme.ImeClipboardScrollView"
private const val CLIPBOARD_IMAGE_CARD_ANCHOR = "WxIme.ImagePreviewCardView"
private val CLIPBOARD_PREVIEW_HOST_ANCHORS = listOf(
    "mHeaderContainerLayout",
    "Super calls with default arguments not supported in this target, function: refreshActionBtn"
)
private const val BITMAP_UTIL_ANCHOR = "WxIme.BitmapUtil"
private val TRANSLATOR_TOP_VIEW_ANCHORS = listOf(
    "输入要翻译的内容",
    "sourceContentEditView focus changed!!!, hasFocus:"
)

/** `RecyclerView$…` 的公共前缀：Adapter 的 `onCreateViewHolder` 返回型一定落在它下面。 */
private const val RECYCLER_VIEW_PREFIX = "androidx.recyclerview.widget.RecyclerView"

/** `RecyclerView` 本身的二进制名：用来判「直接继承 RecyclerView」。 */
private const val RECYCLER_VIEW_CLASS_NAME = "androidx.recyclerview.widget.RecyclerView"

/** 原生圆角容器的父类；同形的 `ImeRadiusImageView` 不在这个子树里。 */
private const val CONSTRAINT_LAYOUT_CLASS = "androidx.constraintlayout.widget.ConstraintLayout"

/** 面板导航入口的历史候选名（3.5.3=p3、3.5.4=o3）。 */
private val PANEL_SWITCH_ENUM_NAMES = listOf("n3", "p3", "o3", "k3", "l3")

/** 切键盘入口的日志字符串：用来把同形的 `N#t3` 从面板导航候选里排除。 */
private val SWITCH_KEYBOARD_ANCHOR = listOf("switchKeyboardNoAnimation ")

/**
 * 翻译提交入口的日志字符串（3.5.4 / 4.0.0 逐字相同，两版都只在 `q#T` 一处）：
 * 该入口与另外 3 个同形方法形状完全一致，只能用方法体内的文本区分。
 */
private val TRANSLATION_COMMIT_ANCHOR = listOf("commitTranslation, isWindowShownFromWindowHidden:")

/** 待上屏文本 getter 的历史候选名（3.5.3=B2、3.5.4=D2、4.0.0=E2）。 */
private val PENDING_GETTER_NAMES = listOf("B2", "D2", "E2")

/** 强制上屏 emit 的历史候选名（3.5.3=W1、3.5.4=Y1）。 */
private val PENDING_EMIT_NAMES = listOf("W1", "Y1")

/**
 * R 类之间的分辨锚。这些资源名在两版宿主上都存在，且只被对应的那个 R 类声明。
 *
 * 判据要**至少命中两个**而不是一个：将来某个名字若被同族的另一个 R 类共用，单名判据会
 * 静默锚错类，双名判据则会退回名字兜底并在自检里报出来。
 */
internal val RESOURCE_ATTR_MARKERS = listOf(
    "Alphabet_text_color", "S10_text_color", "S11_img_type_icon_color_normal"
)
internal val RESOURCE_COLOR_MARKERS = listOf(
    "ime_about_title_color", "ime_app_panel_text_color_normal", "S9_title_color"
)
internal val RESOURCE_DIMEN_MARKERS = listOf(
    "S10_index_radius", "S10_index_margin_start", "S10_button_icon_width"
)

private fun contract(
    id: String,
    desc: String,
    resolve: (HostContext) -> HostHandle?
): HostContract = HostContract(id, desc, resolve)

/**
 * DAO 返回的条目是「十余个简单类型实例字段」的行对象；宿主引擎类（`i0`）字段全静态，
 * 形状与条目高度重合也过不了这一关。字段名会被 R8 重排，所以只按类型判。
 */
internal fun looksLikeEntityRow(clazz: Class<*>): Boolean {
    val instanceFields = clazz.declaredFields.filter { !Modifier.isStatic(it.modifiers) }
    return instanceFields.size >= 10 && instanceFields.all { field ->
        field.type.isPrimitive || field.type == String::class.java ||
            Collection::class.java.isAssignableFrom(field.type)
    }
}

/** Adapter 的 `onCreateViewHolder` 返回型落在 `androidx.recyclerview.widget.RecyclerView$…` 下。 */
internal fun bindsRecyclerViewHolder(clazz: Class<*>): Boolean =
    clazz.declaredMethods.any { it.returnType.name.startsWith(RECYCLER_VIEW_PREFIX) }

/** 声明了 `setList(List)` —— 剪贴板列表容器与其它 RecyclerView 的分别。 */
internal fun takesListSetter(clazz: Class<*>): Boolean =
    clazz.declaredMethods.any {
        it.returnType == Void.TYPE && it.parameterTypes.size == 1 &&
            it.parameterTypes[0] == List::class.java
    }

/**
 * 翻译条下拉的语言列表 adapter：RecyclerView.Adapter 子树 + `(List)` 直喂入口。
 *
 * 宿主把它的短名从 `b` 一路重排，而「Adapter 子树 + 能吃 List」这个形状不会变；
 * 同包的 `d$a` 也是 RecyclerView 家族但吃不了 List，所以判据唯一。
 */
internal fun looksLikeLanguageListAdapter(clazz: Class<*>): Boolean {
    var current: Class<*>? = clazz.superclass
    while (current != null) {
        if (current.name.startsWith(RECYCLER_VIEW_PREFIX)) return takesListSetter(clazz)
        current = current.superclass
    }
    return false
}

/**
 * 原生圆角容器是 `ConstraintLayout` 子树。
 *
 * 按父类**名字**走链，不加载 `androidx.constraintlayout.widget.ConstraintLayout`：宿主 APK 里
 * androidx 的类是被 R8 处理过的，模块侧加载同名类拿到的不是同一个 Class。
 */
internal fun isConstraintLayoutSubclass(clazz: Class<*>): Boolean {
    var current: Class<*>? = clazz
    while (current != null) {
        if (current.name == CONSTRAINT_LAYOUT_CLASS) return true
        current = current.superclass
    }
    return false
}

/**
 * 浮窗管理类的固定形状：静态自引用字段 + 静态 9 参重排入口，且首参就是本类。
 *
 * 「首参就是本类」这条必须留在 JVM 侧判 —— DexKit 的查询表达式只吃具体类型名，而本类的
 * 混淆名每次发版都会变，写进查询就等于写死。整包扫描（含同前缀的 `floatview`）后它唯一命中。
 */
internal fun looksLikeFloatPanel(clazz: Class<*>): Boolean {
    if (!hasStaticSelfField(clazz)) return false
    return clazz.declaredMethods.any { method ->
        Modifier.isStatic(method.modifiers) && method.returnType == Void.TYPE &&
            method.parameterTypes.size == 9 &&
            method.parameterTypes[0] == clazz &&
            method.parameterTypes[1] == Int::class.javaPrimitiveType &&
            method.parameterTypes[2] == Int::class.javaPrimitiveType &&
            method.parameterTypes[3] == Int::class.javaPrimitiveType &&
            method.parameterTypes[4] == Boolean::class.javaPrimitiveType &&
            method.parameterTypes[5] == Boolean::class.javaPrimitiveType &&
            method.parameterTypes[6] == Boolean::class.javaPrimitiveType &&
            method.parameterTypes[7] == Int::class.javaPrimitiveType &&
            method.parameterTypes[8] == Any::class.java
    }
}

/**
 * 皮肤绑定工具类的固定形状：没有实例字段、方法全静态，且声明了三条 View 操作形状。
 *
 * 同包的 `b` / `c` / `e` / `f` 都带实例字段或实例方法，所以「纯静态工具类 + 三条形状」
 * 在整包扫描里唯一命中。方法名会随发版重排（4.0.0 把 `h` 改名成 `j`），形状不会。
 */
internal fun looksLikeSkinBinder(clazz: Class<*>): Boolean {
    if (clazz.declaredFields.any { !Modifier.isStatic(it.modifiers) }) return false
    val methods = clazz.declaredMethods
    if (methods.isEmpty() || methods.any { !Modifier.isStatic(it.modifiers) }) return false
    fun declares(returnType: Class<*>, vararg parameters: Class<*>): Boolean =
        methods.any { method ->
            method.returnType == returnType && method.parameterTypes.size == parameters.size &&
                parameters.indices.all { method.parameterTypes[it] == parameters[it] }
        }
    return declares(
        Void.TYPE,
        ViewGroup::class.java,
        View::class.java,
        Int::class.javaPrimitiveType!!,
        ViewGroup.LayoutParams::class.java
    ) && declares(Void.TYPE, View::class.java) &&
        declares(
            Boolean::class.javaPrimitiveType!!,
            View::class.java,
            Float::class.javaPrimitiveType!!,
            Float::class.javaPrimitiveType!!
        )
}

/**
 * 下拉语言项数据类的固定形状：只有 int 与 String 两个实例字段、构造 `(int, String)`、
 * 两个零参取值方法。宿主里同形的只有这一个。
 */
internal fun looksLikeDropdownItem(clazz: Class<*>): Boolean {
    if (clazz.declaredFields.any { Modifier.isStatic(it.modifiers) }) return false
    val instance = clazz.declaredFields.filter { !Modifier.isStatic(it.modifiers) }
    if (instance.size != 2) return false
    if (instance.count { it.type == Int::class.javaPrimitiveType } != 1) return false
    if (instance.count { it.type == String::class.java } != 1) return false
    val hasConstructor = clazz.declaredConstructors.any { constructor ->
        constructor.parameterTypes.size == 2 &&
            constructor.parameterTypes[0] == Int::class.javaPrimitiveType &&
            constructor.parameterTypes[1] == String::class.java
    }
    if (!hasConstructor) return false
    val methods = clazz.declaredMethods
    return methods.any { it.parameterTypes.isEmpty() && it.returnType == Int::class.javaPrimitiveType } &&
        methods.any { it.parameterTypes.isEmpty() && it.returnType == String::class.java }
}

/**
 * 图片预览页键盘的固定形状：预览宿主（`keyboard.l`）的**直接**子类，且同时持有剪贴板条目
 * 与图片卡片两种实例字段。
 *
 * `keyboard.l` 在 3.5.4 / 4.0.0 上各有 5 个子类，只有图片预览页那个同时挂这两类字段；
 * 两个字段类型都来自契约（`clipboard.item` / `clipboard.image.card`），所以判据里没有类名。
 */
internal fun looksLikeImagePreviewKeyboard(
    clazz: Class<*>,
    previewHost: Class<*>?,
    itemType: Class<*>?,
    cardType: Class<*>?
): Boolean {
    if (previewHost == null || itemType == null || cardType == null) return false
    if (clazz.superclass != previewHost) return false
    val instanceTypes = clazz.declaredFields
        .filter { !Modifier.isStatic(it.modifiers) }
        .mapTo(mutableSetOf()) { it.type }
    return itemType in instanceTypes && cardType in instanceTypes
}

/** IME 输入框：`imeedittext` 包里唯一的 `TextView` 子树（同包的 `a` / `b` 是 InputConnection）。 */
internal fun looksLikeImeEditText(clazz: Class<*>): Boolean =
    android.widget.TextView::class.java.isAssignableFrom(clazz)

/**
 * 翻译下拉列表：`translatingwhilewriting` 包里唯一**直接**继承 `RecyclerView` 的类。
 *
 * 同包的 `a` 是 ViewHolder、`b` 是 Adapter、`d$a` 是 LayoutManager，都不是 RecyclerView 本身，
 * 所以「直接父类等于 RecyclerView」在这个包里唯一命中。
 * `androidx.recyclerview.widget.RecyclerView` 是库的真实类名（不是宿主 R8 产物），可以安全比对。
 */
internal fun looksLikeDropdownList(clazz: Class<*>): Boolean =
    clazz.superclass?.name == RECYCLER_VIEW_CLASS_NAME

/**
 * 宿主文件解密工具：同一个类里同时声明 `([B,String) -> [B` 与 `(String,String,String) -> boolean`
 * 两条静态方法。`k6` 包里只有 `a` 同时具备这两条形状。
 */
internal fun looksLikeFileCrypto(clazz: Class<*>): Boolean {
    val methods = clazz.declaredMethods.filter { Modifier.isStatic(it.modifiers) }
    fun declares(returnType: Class<*>, vararg parameters: Class<*>): Boolean =
        methods.any { method ->
            method.returnType == returnType && method.parameterTypes.size == parameters.size &&
                parameters.indices.all { method.parameterTypes[it] == parameters[it] }
        }
    return declares(ByteArray::class.java, ByteArray::class.java, String::class.java) &&
        declares(
            Boolean::class.javaPrimitiveType!!,
            String::class.java,
            String::class.java,
            String::class.java
        )
}

/**
 * 宿主文件摘要工具：同一个类里同时声明 `(File) -> String`、`(File,int) -> String` 与
 * `(String) -> String` 三条静态方法。`k6` 包里只有 `e` 同时具备这三条形状。
 */
internal fun looksLikeFileHash(clazz: Class<*>): Boolean {
    val methods = clazz.declaredMethods.filter { Modifier.isStatic(it.modifiers) }
    fun declares(vararg parameters: Class<*>): Boolean =
        methods.any { method ->
            method.returnType == String::class.java && method.parameterTypes.size == parameters.size &&
                parameters.indices.all { method.parameterTypes[it] == parameters[it] }
        }
    return declares(File::class.java) &&
        declares(File::class.java, Int::class.javaPrimitiveType!!) &&
        declares(String::class.java)
}

// ---------------------------------------------------------------------------
// 语音：把宿主的识别引擎借给系统识别服务
// ---------------------------------------------------------------------------

/** 语音单例所在的包。整包扫描是这一条契约唯一可行的入口 —— 类名每次都变。 */
private const val VOICE_PACKAGE = "com.tencent.wetype.plugin.hld.voice"

/** 名字候选：三版实测都是 `voice.j`，但它是 R8 短名，只在整包扫描失效时接手。 */
private const val VOICE_SINGLETON_CLASS = "com.tencent.wetype.plugin.hld.voice.j"

/**
 * 录音器候选短名。它在默认包 `l6` 下（不在 `plugin.hld` 里），所以包锚用不上；
 * 4.0.0 起 `l6.e` 还被同包的匿名 Runnable 占用，只能靠形状校验把错的筛掉。
 */
private val VOICE_RECORDER_CLASSES = listOf("l6.f", "l6.e")

/**
 * 读线程在窗口隐藏时打的日志，外加同一方法里的两条旁证。
 *
 * 它调用的门禁方法名每版都在变，字符串不变 —— 但**单条字符串也可能被删改**，所以按顺序
 * 备了三条：主锚不唯一（或消失）时自动退到下一条，全不中才认短名。三条都在读循环
 * `run()` 的方法体里，反查出来的调用集合因此是同一份。
 */
private val VOICE_GATE_ANCHORS = listOf(
    "mAudioRecord window hidden return",
    "startRecord failed : last record is NOT stopped now",
    "[startRecord] dumpRunningTask"
)

/** 名字候选：三版实测 3.5.3 / 3.5.4 是 `O`，4.0.0 是 `P`。 */
private val VOICE_GATE_NAMES = listOf("P", "O")

/** 输入法服务类。类名来自清单，不是混淆产物。 */
private const val VOICE_IME_SERVICE_CLASS = "com.tencent.wetype.plugin.hld.WxHldService"

/**
 * 「按键松开触发发送」方法体内的日志串，用来把与启动入口同形的那条方法排除掉。
 *
 * 那个方法与真正的启动入口**形状完全相同**（`(boolean, <场景枚举>)void`），只差语义：
 * 启动入口单语句 `launch` 一段协程，它是按状态机分支 + 收尾。名字每次发版都重排，但日志
 * 字符串活得过 R8，于是用它当排除锚。
 *
 * **只在 4.0.0 上命中**（三版实测：3.5.3 / 3.5.4 零命中，那两版同形方法本就只有一个，
 * 空排除集也能定唯一）。所以它是锦上添花 —— 真到形状不唯一那一步，兜底全靠下面的名字表。
 */
private const val VOICE_SEND_ACTION_ANCHOR = "handleVoiceSendAction allowDelay="

/**
 * 启动入口的名字候选。三版实测：4.0.0 是 `z1`，3.5.4 是 `k1`，3.5.3 是 `b1`。
 *
 * 只在排除锚与形状都没定下来时才用，且 [methodByNames] 会再校验形状。`w1` 是早先记录的
 * 3.5.4 候选、本轮三版未复现，一并留着当冗余。
 */
private val VOICE_START_NAMES = listOf("z1", "k1", "w1", "b1")

/** 启动入口的形状判据。三版一致。 */
private fun Method.isVoiceStartEntry(): Boolean =
    parameterCount == 2 && returnType == Void.TYPE &&
        parameterTypes[0] == Boolean::class.javaPrimitiveType && parameterTypes[1].isEnum

/**
 * 语音单例的判据：具体类 + 静态自引用字段（Kotlin `object` 的固定形状）+ 声明转录回调。
 *
 * 三项缺一不可：`voice` 包里有几十个带自引用字段的单例，只有会话单例声明转录回调。
 */
private fun looksLikeVoiceSingleton(clazz: Class<*>, minParams: Int = VOICE_TRANSCRIPT_PARAMS): Boolean {
    if (clazz.isInterface || clazz.isEnum || Modifier.isAbstract(clazz.modifiers)) return false
    if (clazz.declaredFields.none { Modifier.isStatic(it.modifiers) && it.type == clazz }) return false
    return clazz.declaredMethods.any { it.isVoiceTranscriptCallback(minParams) }
}

/**
 * 转录回调的参数下限。3.5.3 恰好 15 个（**正好压线，没有余量**），3.5.4 / 4.0.0 是 16。
 *
 * 压线是危险的：宿主哪版少带一个尾部字段，严格判据就整条落空。所以下方留了
 * [VOICE_TRANSCRIPT_MIN_PARAMS] 这一档兜底 —— 严格档先试，不中再放宽。
 */
private const val VOICE_TRANSCRIPT_PARAMS = 15

/** 兜底下限：只认前五参的源码签名，尾巴（会话句柄、进度字段）有几个不管。 */
private const val VOICE_TRANSCRIPT_MIN_PARAMS = 5

/**
 * 转录回调：`(String[], List, boolean, boolean, String, …)void`。
 *
 * 前五个参数是源码签名，跨版本不动；尾巴上挂着会话句柄与进度字段（3.5.3 共 15 个参数，
 * 3.5.4 起 16 个），所以只判下限，不判精确形状 —— 精确形状正是旧实现漏掉
 * 3.5.4 / 4.0.0 的原因。
 */
internal fun Method.isVoiceTranscriptCallback(minParams: Int = VOICE_TRANSCRIPT_PARAMS): Boolean {
    if (returnType != Void.TYPE || Modifier.isStatic(modifiers) || Modifier.isAbstract(modifiers)) return false
    val types = parameterTypes
    if (types.size < minParams) return false
    return types[0] == Array<String>::class.java &&
        List::class.java.isAssignableFrom(types[1]) &&
        types[2] == Boolean::class.javaPrimitiveType &&
        types[3] == Boolean::class.javaPrimitiveType &&
        types[4] == String::class.java
}

/**
 * 录音器的判据：具体类 + 恰好一个实例 `AudioRecord` 字段 + 两个以上无参 boolean 方法 +
 * 持有读线程字段。
 *
 * 读线程自己也持有 `AudioRecord`，前两项它同样满足；第三项（读线程字段）才是分水岭。
 */
internal fun looksLikePcmRecorder(clazz: Class<*>): Boolean {
    if (clazz.isInterface || clazz.isEnum || Modifier.isAbstract(clazz.modifiers)) return false
    val records = clazz.declaredFields.count {
        !Modifier.isStatic(it.modifiers) && AudioRecord::class.java.isAssignableFrom(it.type)
    }
    if (records != 1) return false
    val zeroArgBooleans = clazz.declaredMethods.count {
        !Modifier.isStatic(it.modifiers) && it.parameterCount == 0 &&
            it.returnType == Boolean::class.javaPrimitiveType
    }
    if (zeroArgBooleans < 2) return false
    return clazz.declaredFields.any { it.isVoiceReadModeHolder() }
}

/**
 * 读线程字段：声明类型是**抽象类**，且它自己声明了无参的 `()Z` 与 `()V`。
 *
 * 不能按「类型持有 AudioRecord」判 —— 那份引用在具体子类上，基类上只有这一对开关
 * （`()Z` 开录、`()V` 停录）。
 */
internal fun Field.isVoiceReadModeHolder(): Boolean {
    if (Modifier.isStatic(modifiers)) return false
    val declaredType = type
    if (declaredType.isInterface || !Modifier.isAbstract(declaredType.modifiers)) return false
    val methods = declaredType.declaredMethods
    return methods.any { it.parameterCount == 0 && it.returnType == Boolean::class.javaPrimitiveType } &&
        methods.any { it.parameterCount == 0 && it.returnType == Void.TYPE }
}

internal val HOST_CONTRACTS: List<HostContract> = listOf(

    // ---- 面板枚举与切换 ----

    contract(
        HostContractId.PANEL_ENUM,
        "面板枚举类。常量名是 dex 里的真实字符串，可用它做跨版本的字符串锚"
    ) { ctx ->
        val isPanelEnum = { clazz: Class<*> ->
            clazz.isEnum && clazz.enumConstants.orEmpty().any { constant ->
                (constant as? Enum<*>)?.name == PANEL_CUSTOM_PHRASE_AND_CLIPBOARD
            }
        }
        ctx.classByDexStrings(
            HostContractId.PANEL_ENUM,
            listOf(PANEL_CUSTOM_PHRASE_AND_CLIPBOARD, PANEL_FIND_WORD_T9, PANEL_IMAGE_PREVIEW),
            isPanelEnum
        )?.let { return@contract HostHandle(owner = it) }
        ctx.classByNames(PANEL_ENUM_CLASS)?.let { HostHandle(owner = it) }
    },

    contract(HostContractId.PANEL_VALUE, "面板常量的 int 取值 getter") { ctx ->
        val owner = ctx.classOf(HostContractId.PANEL_ENUM) ?: return@contract null
        ctx.firstMethod(HostContractId.PANEL_VALUE, owner) {
            parameterTypes.isEmpty() && returnType == Int::class.javaPrimitiveType
        }?.let { HostHandle(owner = owner, method = it) }
    },

    contract(
        HostContractId.PANEL_MANAGER,
        "面板管理器类。用两条只属于它的日志字符串做锚，再用结构校验确认"
    ) { ctx ->
        val panelEnum = ctx.classOf(HostContractId.PANEL_ENUM)
        val looksLikeManager = { clazz: Class<*> ->
            clazz.declaredFields.any { Modifier.isStatic(it.modifiers) && it.type == clazz } &&
                (panelEnum == null || clazz.declaredMethods.any { method ->
                    method.parameterTypes.size == 2 && method.parameterTypes[0] == panelEnum &&
                        method.parameterTypes[1] == Bundle::class.java && method.returnType == Void.TYPE
                })
        }
        ctx.classByDexStrings(
            HostContractId.PANEL_MANAGER,
            SWITCH_KEYBOARD_ANCHOR + "onWindowShownParam",
            looksLikeManager
        )?.let { return@contract HostHandle(owner = it) }
        ctx.classByNames(PANEL_MANAGER_CLASS)?.let { HostHandle(owner = it) }
    },

    contract(HostContractId.PANEL_MANAGER_INSTANCE, "面板管理器单例（静态自引用字段）") { ctx ->
        val owner = ctx.classOf(HostContractId.PANEL_MANAGER) ?: return@contract null
        ctx.firstSingletonField(HostContractId.PANEL_MANAGER_INSTANCE, owner)
            ?.let { HostHandle(owner = owner, hostField = it) }
    },

    contract(HostContractId.PANEL_SWITCH_INT, "按面板 int 值切换的入口 (int, Bundle) -> void") { ctx ->
        val owner = ctx.classOf(HostContractId.PANEL_MANAGER) ?: return@contract null
        ctx.firstMethod(HostContractId.PANEL_SWITCH_INT, owner) {
            parameterTypes.size == 2 && parameterTypes[0] == Int::class.javaPrimitiveType &&
                parameterTypes[1] == Bundle::class.java && returnType == Void.TYPE
        }?.let { HostHandle(owner = owner, method = it) }
    },

    contract(
        HostContractId.PANEL_SWITCH_ENUM,
        "按面板枚举切换的导航入口。3.5.4 上 `N#o3` 与切键盘的 `N#t3` 同形，靠字符串锚排除后者"
    ) { ctx ->
        val owner = ctx.classOf(HostContractId.PANEL_MANAGER) ?: return@contract null
        val panelEnum = ctx.classOf(HostContractId.PANEL_ENUM) ?: return@contract null
        val shape: Method.() -> Boolean = {
            parameterTypes.size == 2 && parameterTypes[0] == panelEnum &&
                parameterTypes[1] == Bundle::class.java && returnType == Void.TYPE
        }
        val switchKeyboard = ctx.methodsByDexStrings(
            HostContractId.PANEL_SWITCH_ENUM,
            owner,
            SWITCH_KEYBOARD_ANCHOR
        ).toSet()
        ctx.uniqueMethodExcluding(HostContractId.PANEL_SWITCH_ENUM, owner, switchKeyboard, shape)
            ?.let { return@contract HostHandle(owner = owner, method = it) }
        ctx.methodByNames(owner, PANEL_SWITCH_ENUM_NAMES, shape)
            ?.let { HostHandle(owner = owner, method = it) }
    },

    contract(HostContractId.PANEL_CLIPBOARD_CONSTANT, "CustomPhraseAndClipboard 面板常量") { ctx ->
        val owner = ctx.classOf(HostContractId.PANEL_ENUM) ?: return@contract null
        ctx.enumConstant(owner, PANEL_CUSTOM_PHRASE_AND_CLIPBOARD)
            ?.let { HostHandle(owner = owner, constant = it) }
    },

    // ---- 剪贴板搜索的 native 提交链 ----

    // 三条形状全部取框架回调（onStartInputView / onLayout / onMeasure）：R8 既不能给虚方法改名，
    // 也不能把它删掉，签名跨版本天然稳定。**不要换回合成方法形状**——以前这里的中间一条是
    // (Canvas,View,long)boolean，那是宿主 lambda 的宿主方法，3.5.4 / 4.0.0 落在 ImeCandidateView
    // 与 view.qmui.b 两个类上（靠第三条才收成唯一），3.5.3 上干脆只剩 view.qmui.b，交集为空直接
    // 跌回写死类名。
    contract(
        HostContractId.CLIPBOARD_CANDIDATE_VIEW,
        "候选视图类。按「只有候选条容器才有的三条框架覆写形状」锚定，不认类名"
    ) { ctx ->
        ctx.classByMethodShapes(
            HostContractId.CLIPBOARD_CANDIDATE_VIEW,
            listOf(
                MethodShape(
                    listOf("android.view.inputmethod.EditorInfo", "boolean", "boolean", "int"),
                    "void"
                ),
                MethodShape(listOf("boolean", "int", "int", "int", "int"), "void"),
                MethodShape(listOf("int", "int"), "void")
            )
        ) { View::class.java.isAssignableFrom(it) }
            ?.let { return@contract HostHandle(owner = it) }
        ctx.classByNames(CANDIDATE_VIEW_CLASS)?.let { HostHandle(owner = it) }
    },

    contract(HostContractId.CLIPBOARD_CANDIDATE_GETTER, "候选视图 getter（按返回型取）") { ctx ->
        val owner = ctx.classOf(HostContractId.PANEL_MANAGER) ?: return@contract null
        val view = ctx.classOf(HostContractId.CLIPBOARD_CANDIDATE_VIEW) ?: return@contract null
        ctx.firstMethod(HostContractId.CLIPBOARD_CANDIDATE_GETTER, owner) {
            parameterTypes.isEmpty() && view.isAssignableFrom(returnType)
        }?.let { HostHandle(owner = owner, method = it) }
    },

    contract(
        HostContractId.CLIPBOARD_ENGINE,
        "输入引擎类。判据是两条方法形状的交集（待上屏 getter + 强制上屏），与类名无关"
    ) { ctx ->
        ctx.classByMethodShapes(
            HostContractId.CLIPBOARD_ENGINE,
            listOf(
                MethodShape(listOf("boolean"), "java.lang.CharSequence"),
                MethodShape(listOf("java.lang.String", "boolean", "boolean"), "void")
            ),
            ::hasStaticSelfField
        )?.let { return@contract HostHandle(owner = it) }
        ctx.classByNames(CLIPBOARD_ENGINE_CLASS)?.let { HostHandle(owner = it) }
    },

    contract(HostContractId.CLIPBOARD_ENGINE_INSTANCE, "输入引擎单例（静态自引用字段）") { ctx ->
        val owner = ctx.classOf(HostContractId.CLIPBOARD_ENGINE) ?: return@contract null
        ctx.firstSingletonField(HostContractId.CLIPBOARD_ENGINE_INSTANCE, owner)
            ?.let { HostHandle(owner = owner, hostField = it) }
    },

    contract(HostContractId.CLIPBOARD_PENDING_GETTER, "待上屏文本 getter (boolean) -> CharSequence") { ctx ->
        val owner = ctx.classOf(HostContractId.CLIPBOARD_ENGINE) ?: return@contract null
        val shape: Method.() -> Boolean = {
            parameterTypes.size == 1 && parameterTypes[0] == Boolean::class.javaPrimitiveType &&
                CharSequence::class.java.isAssignableFrom(returnType)
        }
        ctx.uniqueShapePrimitive(HostContractId.CLIPBOARD_PENDING_GETTER, owner, shape)
            ?.let { return@contract HostHandle(owner = owner, method = it) }
        ctx.methodByNames(owner, PENDING_GETTER_NAMES, shape)
            ?.let { HostHandle(owner = owner, method = it) }
    },

    contract(HostContractId.CLIPBOARD_PENDING_EMIT, "强制上屏 (String, boolean, boolean) -> void") { ctx ->
        val owner = ctx.classOf(HostContractId.CLIPBOARD_ENGINE) ?: return@contract null
        val shape: Method.() -> Boolean = {
            parameterTypes.size == 3 && parameterTypes[0] == String::class.java &&
                parameterTypes[1] == Boolean::class.javaPrimitiveType &&
                parameterTypes[2] == Boolean::class.javaPrimitiveType && returnType == Void.TYPE
        }
        ctx.uniqueMethod(HostContractId.CLIPBOARD_PENDING_EMIT, owner, shape)
            ?.let { return@contract HostHandle(owner = owner, method = it) }
        ctx.methodByNames(owner, PENDING_EMIT_NAMES, shape)
            ?.let { HostHandle(owner = owner, method = it) }
    },

    contract(
        HostContractId.CLIPBOARD_ACTION,
        "原生动作分发器类。判据是 (int, Object) -> void 形状 + 静态自引用字段；同形的 selfview 类"
            + "没有自引用字段，所以唯一命中"
    ) { ctx ->
        ctx.classByMethodShapes(
            HostContractId.CLIPBOARD_ACTION,
            listOf(MethodShape(listOf("int", "java.lang.Object"), "void")),
            ::hasStaticSelfField
        )?.let { return@contract HostHandle(owner = it) }
        ctx.classByNames(CLIPBOARD_ACTION_CLASS)?.let { HostHandle(owner = it) }
    },

    contract(HostContractId.CLIPBOARD_ACTION_INSTANCE, "动作分发器单例（静态自引用字段）") { ctx ->
        val owner = ctx.classOf(HostContractId.CLIPBOARD_ACTION) ?: return@contract null
        ctx.firstSingletonField(HostContractId.CLIPBOARD_ACTION_INSTANCE, owner)
            ?.let { HostHandle(owner = owner, hostField = it) }
    },

    contract(HostContractId.CLIPBOARD_ACTION_DISPATCH, "原生动作分发入口 (int, Object) -> void") { ctx ->
        val owner = ctx.classOf(HostContractId.CLIPBOARD_ACTION) ?: return@contract null
        val shape: Method.() -> Boolean = {
            parameterTypes.size == 2 && parameterTypes[0] == Int::class.javaPrimitiveType &&
                parameterTypes[1] == Any::class.java && returnType == Void.TYPE
        }
        ctx.uniqueMethod(HostContractId.CLIPBOARD_ACTION_DISPATCH, owner, shape)
            ?.let { return@contract HostHandle(owner = owner, method = it) }
        ctx.methodByNames(owner, listOf("O"), shape)
            ?.let { HostHandle(owner = owner, method = it) }
    },

    // ---- 剪贴板条目 / 图片 / 搜索链 ----

    contract(
        HostContractId.CLIPBOARD_MANAGER,
        "剪贴板管理器类。用只属于它的两条日志文本锚定，静态自引用字段校验"
    ) { ctx ->
        ctx.classByDexStrings(
            HostContractId.CLIPBOARD_MANAGER,
            CLIPBOARD_MANAGER_ANCHORS,
            ::hasStaticSelfField
        )?.let { return@contract HostHandle(owner = it) }
        ctx.classByNames(CLIPBOARD_MANAGER_CLASS)?.let { HostHandle(owner = it) }
    },

    contract(
        HostContractId.CLIPBOARD_ITEM,
        "剪贴板条目（DAO 查询返回的行）。用 toString 里的字段名文本锚定，实体行形状校验"
    ) { ctx ->
        ctx.classByDexStrings(
            HostContractId.CLIPBOARD_ITEM,
            CLIPBOARD_ITEM_ANCHORS,
            ::looksLikeEntityRow
        )?.let { return@contract HostHandle(owner = it) }
        ctx.classByNames(CLIPBOARD_ITEM_CLASS)?.let { HostHandle(owner = it) }
    },

    contract(
        HostContractId.CLIPBOARD_ENTRIES_HOST,
        "剪贴板行 ViewHolder。用只在它体内出现的两条文本锚定，并以点击/长按接口校验"
    ) { ctx ->
        ctx.classByDexStrings(
            HostContractId.CLIPBOARD_ENTRIES_HOST,
            CLIPBOARD_ENTRIES_HOST_ANCHORS
        ) { clazz ->
            View.OnClickListener::class.java.isAssignableFrom(clazz) &&
                View.OnLongClickListener::class.java.isAssignableFrom(clazz)
        }?.let { return@contract HostHandle(owner = it) }
        ctx.classByNames(CLIPBOARD_ENTRIES_HOST_CLASS)?.let { HostHandle(owner = it) }
    },

    contract(
        HostContractId.CLIPBOARD_ADAPTER,
        "剪贴板列表 Adapter。用只在它体内出现的两条文本锚定，并以返回 RecyclerView 家族校验"
    ) { ctx ->
        ctx.classByDexStrings(
            HostContractId.CLIPBOARD_ADAPTER,
            CLIPBOARD_ADAPTER_ANCHORS,
            ::bindsRecyclerViewHolder
        )?.let { return@contract HostHandle(owner = it) }
        ctx.classByNames(CLIPBOARD_ADAPTER_CLASS)?.let { HostHandle(owner = it) }
    },

    contract(
        HostContractId.CLIPBOARD_DAO_FACTORY,
        "Room DAO 工厂。用宿主自己的 TAG 文本锚定，静态自引用字段校验"
    ) { ctx ->
        ctx.classByDexStrings(
            HostContractId.CLIPBOARD_DAO_FACTORY,
            listOf(CLIPBOARD_DAO_FACTORY_ANCHOR),
            ::hasStaticSelfField
        )?.let { return@contract HostHandle(owner = it) }
        ctx.classByNames(CLIPBOARD_DAO_FACTORY_CLASS)?.let { HostHandle(owner = it) }
    },

    contract(
        HostContractId.CLIPBOARD_SCROLLVIEW,
        "剪贴板列表容器。用宿主自己的 TAG 文本锚定，并以 View 子树 + setList(List) 校验"
    ) { ctx ->
        ctx.classByDexStrings(
            HostContractId.CLIPBOARD_SCROLLVIEW,
            listOf(CLIPBOARD_SCROLLVIEW_ANCHOR)
        ) { clazz ->
            View::class.java.isAssignableFrom(clazz) && takesListSetter(clazz)
        }?.let { return@contract HostHandle(owner = it) }
        ctx.classByNames(CLIPBOARD_SCROLLVIEW_CLASS)?.let { HostHandle(owner = it) }
    },

    contract(
        HostContractId.CLIPBOARD_IMAGE_CARD,
        "图片预览卡片视图。用宿主自己的 TAG 文本锚定，并以 View 子树校验"
    ) { ctx ->
        ctx.classByDexStrings(
            HostContractId.CLIPBOARD_IMAGE_CARD,
            listOf(CLIPBOARD_IMAGE_CARD_ANCHOR)
        ) { View::class.java.isAssignableFrom(it) }?.let { return@contract HostHandle(owner = it) }
        ctx.classByNames(CLIPBOARD_IMAGE_CARD_CLASS)?.let { HostHandle(owner = it) }
    },

    contract(
        HostContractId.CLIPBOARD_PREVIEW_HOST,
        "图片预览宿主视图。用属性名文本与 Kotlin 默认参数桩的文本锚定，并以 View 子树校验"
    ) { ctx ->
        ctx.classByDexStrings(
            HostContractId.CLIPBOARD_PREVIEW_HOST,
            CLIPBOARD_PREVIEW_HOST_ANCHORS
        ) { View::class.java.isAssignableFrom(it) }?.let { return@contract HostHandle(owner = it) }
        ctx.classByNames(CLIPBOARD_PREVIEW_HOST_CLASS)?.let { HostHandle(owner = it) }
    },

    contract(
        HostContractId.CLIPBOARD_SCALE_INSTANCE,
        "尺寸换算单例（dp→px）。判据是 (Integer)->int + (int)->int + ()->double 三条形状的交集"
    ) { ctx ->
        ctx.classByMethodShapes(
            HostContractId.CLIPBOARD_SCALE_INSTANCE,
            listOf(
                MethodShape(listOf("java.lang.Integer"), "int"),
                MethodShape(listOf("int"), "int"),
                MethodShape(emptyList(), "double")
            ),
            ::hasStaticSelfField
        )?.let { return@contract HostHandle(owner = it) }
        ctx.classByNames(*CLIPBOARD_SCALE_INSTANCE_CLASSES)?.let { HostHandle(owner = it) }
    },

    contract(
        HostContractId.CLIPBOARD_SCALE_STATIC,
        "尺寸换算静态工具（设计像素→px）。判据是 (RecyclerView,float)->int + (Context,int)->int 的交集"
    ) { ctx ->
        ctx.classByMethodShapes(
            HostContractId.CLIPBOARD_SCALE_STATIC,
            listOf(
                MethodShape(listOf("androidx.recyclerview.widget.RecyclerView", "float"), "int"),
                MethodShape(listOf("android.content.Context", "int"), "int")
            )
        )?.let { return@contract HostHandle(owner = it) }
        ctx.classByNames(*CLIPBOARD_SCALE_STATIC_CLASSES)?.let { HostHandle(owner = it) }
    },

    contract(
        HostContractId.BITMAP_UTIL,
        "宿主位图工具。用宿主自己的 TAG 文本锚定，静态自引用单例 + (String)->boolean 校验"
    ) { ctx ->
        ctx.classByDexStrings(
            HostContractId.BITMAP_UTIL,
            listOf(BITMAP_UTIL_ANCHOR)
        ) { clazz ->
            hasStaticSelfField(clazz) && clazz.declaredMethods.any { method ->
                method.parameterTypes.size == 1 &&
                    method.parameterTypes[0] == String::class.java &&
                    method.returnType == Boolean::class.javaPrimitiveType
            }
        }?.let { return@contract HostHandle(owner = it) }
        ctx.classByNames(BITMAP_UTIL_CLASS)?.let { HostHandle(owner = it) }
    },

    contract(
        HostContractId.VIEW_RADIUS_LAYOUT,
        "原生圆角容器。判据是 (int)void / (float)void / (Canvas)void / (int,int,int,int)void 的交集"
    ) { ctx ->
        ctx.classByMethodShapes(
            HostContractId.VIEW_RADIUS_LAYOUT,
            listOf(
                MethodShape(listOf("int"), "void"),
                MethodShape(listOf("float"), "void"),
                MethodShape(listOf("android.graphics.Canvas"), "void"),
                MethodShape(listOf("int", "int", "int", "int"), "void")
            ),
            ::isConstraintLayoutSubclass
        )?.let { return@contract HostHandle(owner = it) }
        ctx.classByNames(VIEW_RADIUS_LAYOUT_CLASS)?.let { HostHandle(owner = it) }
    },

    contract(
        HostContractId.TRANSLATOR_TOP_VIEW,
        "翻译条根视图。用排版文案与只在它体内出现的日志文本锚定，并以 ViewGroup 子树校验"
    ) { ctx ->
        ctx.classByDexStrings(
            HostContractId.TRANSLATOR_TOP_VIEW,
            TRANSLATOR_TOP_VIEW_ANCHORS
        ) { ViewGroup::class.java.isAssignableFrom(it) }?.let { return@contract HostHandle(owner = it) }
        ctx.classByNames(TRANSLATOR_TOP_VIEW_CLASS)?.let { HostHandle(owner = it) }
    },

    // ---- IME 服务 ----

    contract(
        HostContractId.CLIPBOARD_IME_SERVICE,
        "微信输入法 IME 服务类。按「InputMethodService 子树 + 只有输入法才收到的回调形状」锚定"
    ) { ctx ->
        ctx.classByMethodShapes(
            HostContractId.CLIPBOARD_IME_SERVICE,
            listOf(
                MethodShape(listOf("android.view.inputmethod.EditorInfo", "boolean"), "void"),
                MethodShape(emptyList(), "android.view.View")
            )
        ) { InputMethodService::class.java.isAssignableFrom(it) }
            ?.let { return@contract HostHandle(owner = it) }
        ctx.classByNames(IME_SERVICE_CLASS)?.let { HostHandle(owner = it) }
    },

    contract(
        HostContractId.CLIPBOARD_IME_SERVICE_KICK,
        "提交后送交宿主编辑器的入口。判据是「零参 void 且体内含宿主自己的日志串」，与短名无关"
    ) { ctx ->
        val owner = ctx.classOf(HostContractId.CLIPBOARD_IME_SERVICE) ?: return@contract null
        ctx.methodsByDexStrings(
            HostContractId.CLIPBOARD_IME_SERVICE_KICK,
            owner,
            listOf("handleActionKey actionType:")
        ) { parameterTypes.isEmpty() && returnType == Void.TYPE }
            .singleOrNull()
            ?.let {
                ctx.winner = "dexString:${owner.simpleName}#${it.name}"
                return@contract HostHandle(owner = owner, method = it)
            }
        ctx.methodByNames(owner, listOf("c")) {
            parameterTypes.isEmpty() && returnType == Void.TYPE
        }?.let { HostHandle(owner = owner, method = it) }
    },

    contract(
        HostContractId.CLIPBOARD_IME_SERVICE_COMPANION,
        "服务 Companion 字段 + 零参 getter。字段名随版本漂移，只认「静态字段 → 返回服务接口的零参 getter」结构"
    ) { ctx ->
        val service = ctx.classOf(HostContractId.CLIPBOARD_IME_SERVICE) ?: return@contract null
        for (field in service.declaredFields) {
            if (!Modifier.isStatic(field.modifiers)) continue
            val companionType = field.type
            if (companionType == service) continue
            val getters = companionType.declaredMethods.filter { candidate ->
                candidate.parameterTypes.isEmpty() && candidate.returnType != Void.TYPE &&
                    candidate.returnType.isInterface &&
                    runCatching { candidate.returnType.isAssignableFrom(service) }.getOrDefault(false)
            }
            if (getters.isEmpty()) continue
            if (runCatching { field.isAccessible = true; field.get(null) }.getOrNull() == null) continue
            val getter = (getters.firstOrNull { it.name == "e" } ?: getters.first()).apply { isAccessible = true }
            ctx.winner = "companion:${companionType.simpleName}#${getter.name}"
            return@contract HostHandle(owner = companionType, hostField = field, method = getter)
        }
        null
    },

    // ---- 翻译条高度流 ----

    contract(
        HostContractId.CLIPBOARD_HEIGHT_MANAGER,
        "翻译条高度流管理类。判据是单参 / 双参 CharSequence 两条形状的交集，唯一命中"
    ) { ctx ->
        ctx.classByMethodShapes(
            HostContractId.CLIPBOARD_HEIGHT_MANAGER,
            listOf(
                MethodShape(listOf("java.lang.CharSequence"), "void"),
                MethodShape(listOf("java.lang.CharSequence", "java.lang.CharSequence"), "void")
            )
        )?.let { return@contract HostHandle(owner = it) }
        ctx.classByNames(HEIGHT_MANAGER_CLASS)?.let { HostHandle(owner = it) }
    },

    contract(
        HostContractId.CLIPBOARD_HEIGHT_SET_TEXT,
        "调度翻译 (CharSequence) -> void。同形的另一个是编译器生成的静态存取器，按 synthetic 排除"
    ) { ctx ->
        val owner = ctx.classOf(HostContractId.CLIPBOARD_HEIGHT_MANAGER) ?: return@contract null
        val shape: Method.() -> Boolean = {
            parameterTypes.size == 1 && parameterTypes[0] == CharSequence::class.java &&
                returnType == Void.TYPE
        }
        ctx.uniqueMethod(HostContractId.CLIPBOARD_HEIGHT_SET_TEXT, owner) { shape() && !isSynthetic }
            ?.let { return@contract HostHandle(owner = owner, method = it) }
        ctx.methodByNames(owner, listOf("T0"), shape)
            ?.let { HostHandle(owner = owner, method = it) }
    },

    contract(
        HostContractId.CLIPBOARD_HEIGHT_SET_CHAR,
        "提交翻译 (boolean) -> void。同形有 4 个原始方法，按方法体内的独有日志文本锚定"
    ) { ctx ->
        val owner = ctx.classOf(HostContractId.CLIPBOARD_HEIGHT_MANAGER) ?: return@contract null
        val shape: Method.() -> Boolean = {
            parameterTypes.size == 1 && parameterTypes[0] == Boolean::class.javaPrimitiveType &&
                returnType == Void.TYPE
        }
        ctx.methodsByDexStrings(
            HostContractId.CLIPBOARD_HEIGHT_SET_CHAR,
            owner,
            TRANSLATION_COMMIT_ANCHOR,
            shape
        ).singleOrNull()?.let {
            it.isAccessible = true
            ctx.winner = "dexString:${owner.simpleName}#${it.name}"
            return@contract HostHandle(owner = owner, method = it)
        }
        ctx.methodByNames(owner, listOf("T"), shape)
            ?.let { HostHandle(owner = owner, method = it) }
    },

    // ---- 宿主 R 类（按字段名锚定，短名兜底）----

    contract(
        HostContractId.RESOURCE_CLASS_ID,
        "R.id 类。字段名能活过 R8，用它锚定；校验：大量 int 静态字段 + 三个已知 id 名齐全"
    ) { ctx ->
        val looksLikeIdClass = { clazz: Class<*> ->
            val statics = clazz.declaredFields.filter { Modifier.isStatic(it.modifiers) }
            statics.count { it.type == Int::class.javaPrimitiveType } >= 20 &&
                statics.any { it.name == "keyboard_container_rl" } &&
                statics.any { it.name == "empty_clipboard_view_vs" }
        }
        ctx.classByDexFields(HostContractId.RESOURCE_CLASS_ID, listOf("logo_iv"), looksLikeIdClass)
            ?.let { return@contract HostHandle(owner = it) }
        ctx.classByNames(RESOURCE_CLASS_ID_NAME)?.let { HostHandle(owner = it) }
    },

    contract(
        HostContractId.RESOURCE_CLASS_DRAWABLE,
        "R.drawable 类。用 Logo 的两个明暗变体字段名同时锚定（同一类必须两个都有）"
    ) { ctx ->
        ctx.classByDexFields(
            HostContractId.RESOURCE_CLASS_DRAWABLE,
            listOf("ime_logo_green")
        ) { clazz ->
            clazz.declaredFields.any { it.name == "ime_logo_green_dark" }
        }?.let { return@contract HostHandle(owner = it) }
        ctx.classByNames(RESOURCE_CLASS_DRAWABLE_NAME)?.let { HostHandle(owner = it) }
    },

    contract(
        HostContractId.RESOURCE_CLASS_ATTR,
        "R.attr 类。类名是 R8 产物、字段名不是 —— 按「声明了下面这些属性名里的至少两个」锚定"
    ) { ctx ->
        ctx.classByFieldCandidates(HostContractId.RESOURCE_CLASS_ATTR, RESOURCE_ATTR_MARKERS) { clazz ->
            looksLikeResourceTable(clazz) && declaresAtLeast(clazz, RESOURCE_ATTR_MARKERS, 2)
        }?.let { return@contract HostHandle(owner = it) }
        ctx.classByNames(RESOURCE_CLASS_ATTR_NAME)?.let { HostHandle(owner = it) }
    },

    contract(
        HostContractId.RESOURCE_CLASS_COLOR,
        "R.color 类。同上，按「声明了这些颜色名里的至少两个」锚定"
    ) { ctx ->
        ctx.classByFieldCandidates(HostContractId.RESOURCE_CLASS_COLOR, RESOURCE_COLOR_MARKERS) { clazz ->
            looksLikeResourceTable(clazz) && declaresAtLeast(clazz, RESOURCE_COLOR_MARKERS, 2)
        }?.let { return@contract HostHandle(owner = it) }
        ctx.classByNames(RESOURCE_CLASS_COLOR_NAME)?.let { HostHandle(owner = it) }
    },

    contract(
        HostContractId.RESOURCE_CLASS_DIMEN,
        "R.dimen 类。同上，按「声明了这些尺寸名里的至少两个」锚定"
    ) { ctx ->
        ctx.classByFieldCandidates(HostContractId.RESOURCE_CLASS_DIMEN, RESOURCE_DIMEN_MARKERS) { clazz ->
            looksLikeResourceTable(clazz) && declaresAtLeast(clazz, RESOURCE_DIMEN_MARKERS, 2)
        }?.let { return@contract HostHandle(owner = it) }
        ctx.classByNames(RESOURCE_CLASS_DIMEN_NAME)?.let { HostHandle(owner = it) }
    },

    // ---- 整包扫描：判据要看成员之间的相互关系，DexKit 查询表达式表达不了 ----

    contract(
        HostContractId.STRIP_FLOAT_PANEL,
        "浮窗管理类。判据是静态自引用字段 + 静态 9 参重排入口（首参就是本类），整包扫描唯一命中"
    ) { ctx ->
        ctx.classByPackageScan(
            HostContractId.STRIP_FLOAT_PANEL,
            "packageScan:float",
            listOf(FLOAT_PACKAGE),
            ::looksLikeFloatPanel
        )?.let { return@contract HostHandle(owner = it) }
        ctx.classByNames(*FLOAT_PANEL_CLASSES)?.let { HostHandle(owner = it) }
    },

    contract(
        HostContractId.STRIP_SKIN_BINDER,
        "皮肤绑定工具类。判据是无实例字段、方法全静态，且声明了三条 View 操作形状"
    ) { ctx ->
        ctx.classByPackageScan(
            HostContractId.STRIP_SKIN_BINDER,
            "packageScan:skin.utils",
            listOf(SKIN_UTILS_PACKAGE),
            ::looksLikeSkinBinder
        )?.let { return@contract HostHandle(owner = it) }
        ctx.classByNames(SKIN_BINDER_CLASS)?.let { HostHandle(owner = it) }
    },

    contract(
        HostContractId.TRANSLATOR_DROPDOWN_ITEM,
        "下拉语言项数据类。判据是 int + String 两个实例字段、构造 (int,String)、两个零参取值方法"
    ) { ctx ->
        ctx.classByPackageScan(
            HostContractId.TRANSLATOR_DROPDOWN_ITEM,
            "packageScan:translatingwhilewriting",
            listOf(TRANSLATING_PACKAGE),
            ::looksLikeDropdownItem
        )?.let { return@contract HostHandle(owner = it) }
        ctx.classByNames(DROPDOWN_ITEM_CLASS)?.let { HostHandle(owner = it) }
    },

    contract(
        HostContractId.IMAGE_PREVIEW_KEYBOARD,
        "图片预览页键盘。判据是预览宿主的直接子类，且同时持有条目与卡片实例字段（两个类型都来自契约）"
    ) { ctx ->
        val previewHost = ctx.classOf(HostContractId.CLIPBOARD_PREVIEW_HOST)
        val itemType = ctx.classOf(HostContractId.CLIPBOARD_ITEM)
        val cardType = ctx.classOf(HostContractId.CLIPBOARD_IMAGE_CARD)
        ctx.classByPackageScan(
            HostContractId.IMAGE_PREVIEW_KEYBOARD,
            "packageScan:keyboard+previewHost",
            listOf(KEYBOARD_PACKAGE)
        ) { looksLikeImagePreviewKeyboard(it, previewHost, itemType, cardType) }
            ?.let { return@contract HostHandle(owner = it) }
        ctx.classByNames(PREVIEW_KEYBOARD_CLASS)?.let { HostHandle(owner = it) }
    },

    contract(
        HostContractId.IME_EDIT_TEXT,
        "IME 输入框。判据是 imeedittext 包里唯一的 TextView 子树，与类名无关"
    ) { ctx ->
        ctx.classByPackageScan(
            HostContractId.IME_EDIT_TEXT,
            "packageScan:imeedittext",
            listOf(IME_EDIT_TEXT_PACKAGE),
            ::looksLikeImeEditText
        )?.let { return@contract HostHandle(owner = it) }
        ctx.classByNames(IME_EDIT_TEXT_CLASS)?.let { HostHandle(owner = it) }
    },

    contract(
        HostContractId.TRANSLATOR_DROPDOWN_LIST,
        "翻译下拉列表。判据是 translatingwhilewriting 包里直接继承 RecyclerView 的那个类，整包扫描唯一命中"
    ) { ctx ->
        ctx.classByPackageScan(
            HostContractId.TRANSLATOR_DROPDOWN_LIST,
            "packageScan:translatingwhilewriting+recycler",
            listOf(TRANSLATING_PACKAGE),
            ::looksLikeDropdownList
        )?.let { return@contract HostHandle(owner = it) }
        ctx.classByNames(DROPDOWN_LIST_CLASS)?.let { HostHandle(owner = it) }
    },

    // ---- 第三方库与宿主工具类：包名是真实包名/稳定包名，包内按形状取 ----

    contract(
        HostContractId.GLIDE_ENTRY,
        "Glide 入口类。判据是 com.bumptech.glide 包里的单例 + 静态 (Context) -> RequestManager"
    ) { ctx ->
        ctx.classByPackageScan(
            HostContractId.GLIDE_ENTRY,
            "packageScan:glide",
            listOf(GLIDE_PACKAGE)
        ) { GlideShape.looksLikeGlideEntry(it, Context::class.java) }
            ?.let { return@contract HostHandle(owner = it) }
        ctx.classByNames(GLIDE_ENTRY_CLASS)?.let { HostHandle(owner = it) }
    },

    contract(
        HostContractId.HOST_CRYPTO_FILE,
        "宿主文件解密工具。判据是 ([B,String)->[B 与 (String,String,String)->boolean 两条静态方法同在一类"
    ) { ctx ->
        ctx.classByPackageScan(
            HostContractId.HOST_CRYPTO_FILE,
            "packageScan:k6+crypto",
            listOf(CRYPTO_PACKAGE),
            ::looksLikeFileCrypto
        )?.let { return@contract HostHandle(owner = it) }
        ctx.classByNames(CRYPTO_FILE_CLASS)?.let { HostHandle(owner = it) }
    },

    contract(
        HostContractId.HOST_HASH_FILE,
        "宿主文件摘要工具。判据是 (File)->String、(File,int)->String、(String)->String 三条静态方法同在一类"
    ) { ctx ->
        ctx.classByPackageScan(
            HostContractId.HOST_HASH_FILE,
            "packageScan:k6+hash",
            listOf(CRYPTO_PACKAGE),
            ::looksLikeFileHash
        )?.let { return@contract HostHandle(owner = it) }
        ctx.classByNames(HASH_FILE_CLASS)?.let { HostHandle(owner = it) }
    },

    // ---- 语音：把宿主的识别引擎借给系统识别服务 ----
    //
    // 三版实测矩阵（3.5.3 / 3.5.4 / 4.0.0，三版均跑通端到端）：
    //
    //   契约                      3.5.3     3.5.4     4.0.0     实际命中策略
    //   voice.singleton           voice.j   voice.j   voice.j   packageScan
    //   voice.singleton.instance  j#k       j#k       j#l       自引用字段
    //   voice.transcript          j#c       j#c       j#g       形状
    //   voice.start               j#b1      j#k1      j#z1      形状（排除锚仅 4.0.0 命中）
    //   voice.recorder            l6.e      l6.f      l6.f      AudioRecord 字段类型
    //   voice.recorder.record     w         w         w         字段类型
    //   voice.recorder.mode       y         y         y         抽象基类字段
    //   voice.recorder.init       t         u         u         写 AudioRecord 字段
    //   voice.recorder.teardown   z         L         L         写 AudioRecord 字段
    //   voice.gate                #O        #O        #P        读循环日志串反查
    //
    // 三版都是 59/59 契约解析成功、零条落到写死的短名。名字列每版全变 —— 这正是整套形状
    // 判据存在的理由；下面那些短名表只是形状全部落空时的最后一档，别当主路径用。

    contract(
        HostContractId.VOICE_SINGLETON,
        "语音单例类。整包扫描 +「具体类 + 静态自引用字段 + 声明转录回调」三重校验；参数下限先严后宽，最后才认类名"
    ) { ctx ->
        ctx.classByPackageScan(
            HostContractId.VOICE_SINGLETON,
            "packageScan:$VOICE_PACKAGE",
            listOf(VOICE_PACKAGE)
        ) { looksLikeVoiceSingleton(it, VOICE_TRANSCRIPT_PARAMS) }
            ?.let { return@contract HostHandle(owner = it) }
        ctx.classByPackageScan(
            HostContractId.VOICE_SINGLETON,
            "packageScan-loose:$VOICE_PACKAGE",
            listOf(VOICE_PACKAGE)
        ) { looksLikeVoiceSingleton(it, VOICE_TRANSCRIPT_MIN_PARAMS) }
            ?.let { return@contract HostHandle(owner = it) }
        ctx.classByNames(VOICE_SINGLETON_CLASS)?.let { HostHandle(owner = it) }
    },

    contract(HostContractId.VOICE_SINGLETON_INSTANCE, "语音单例的静态自引用字段") { ctx ->
        val owner = ctx.classOf(HostContractId.VOICE_SINGLETON) ?: return@contract null
        ctx.firstSingletonField(HostContractId.VOICE_SINGLETON_INSTANCE, owner)
            ?.let { HostHandle(owner = owner, hostField = it) }
    },

    contract(
        HostContractId.VOICE_TRANSCRIPT,
        "转录回调。前五参是源码签名，尾巴上的会话句柄按版本增删，所以只判下限；下限先严后宽"
    ) { ctx ->
        val owner = ctx.classOf(HostContractId.VOICE_SINGLETON) ?: return@contract null
        ctx.uniqueMethod(HostContractId.VOICE_TRANSCRIPT, owner) {
            isVoiceTranscriptCallback(VOICE_TRANSCRIPT_PARAMS)
        }?.let { return@contract HostHandle(owner = owner, method = it) }
        ctx.uniqueMethod(HostContractId.VOICE_TRANSCRIPT, owner) {
            isVoiceTranscriptCallback(VOICE_TRANSCRIPT_MIN_PARAMS)
        }?.let { HostHandle(owner = owner, method = it) }
    },

    contract(
        HostContractId.VOICE_START,
        "启动入口。先用「发送时收尾」的日志字符串把同形方法排除掉，再按形状取唯一，最后才认短名"
    ) { ctx ->
        val owner = ctx.classOf(HostContractId.VOICE_SINGLETON) ?: return@contract null
        val excluded = ctx
            .methodsByDexStrings(
                HostContractId.VOICE_START,
                owner,
                listOf(VOICE_SEND_ACTION_ANCHOR)
            ) { isVoiceStartEntry() }
            .toSet()
        ctx.uniqueMethodExcluding(HostContractId.VOICE_START, owner, excluded) { isVoiceStartEntry() }
            ?.let { return@contract HostHandle(owner = owner, method = it) }
        ctx.methodByNames(owner, VOICE_START_NAMES) { isVoiceStartEntry() }
            ?.let { HostHandle(owner = owner, method = it) }
    },

    contract(
        HostContractId.VOICE_RECORDER,
        "录音器。按「持有 AudioRecord + 两个以上无参 boolean + 持有读线程字段」定位，不限包"
    ) { ctx ->
        ctx.classByFieldType(
            HostContractId.VOICE_RECORDER,
            "android.media.AudioRecord",
            ::looksLikePcmRecorder
        )?.let { return@contract HostHandle(owner = it) }
        VOICE_RECORDER_CLASSES.firstNotNullOfOrNull { name ->
            loadClassOrNull(name, ctx.classLoader)?.takeIf(::looksLikePcmRecorder)
        }?.let { return@contract HostHandle(owner = it) }
        null
    },

    contract(HostContractId.VOICE_RECORDER_RECORD, "录音器持有的 AudioRecord 字段") { ctx ->
        val owner = ctx.classOf(HostContractId.VOICE_RECORDER) ?: return@contract null
        ctx.uniqueField(HostContractId.VOICE_RECORDER_RECORD, owner) {
            !Modifier.isStatic(modifiers) && AudioRecord::class.java.isAssignableFrom(type)
        }?.let { HostHandle(owner = owner, hostField = it) }
    },

    contract(HostContractId.VOICE_RECORDER_MODE, "录音器持有的读线程字段（声明类型是抽象基类）") { ctx ->
        val owner = ctx.classOf(HostContractId.VOICE_RECORDER) ?: return@contract null
        ctx.uniqueField(HostContractId.VOICE_RECORDER_MODE, owner) { isVoiceReadModeHolder() }
            ?.let { HostHandle(owner = owner, hostField = it) }
    },

    contract(
        HostContractId.VOICE_RECORDER_INIT,
        "录音器建 AudioRecord 的入口。按「写 AudioRecord 字段、且返回 boolean」取，不写死 t/u"
    ) { ctx ->
        val owner = ctx.classOf(HostContractId.VOICE_RECORDER) ?: return@contract null
        val record = ctx.handle(HostContractId.VOICE_RECORDER_RECORD)?.hostField ?: return@contract null
        val hits = ctx.fieldWriters(HostContractId.VOICE_RECORDER_INIT, owner, record.name)
            .filter { it.parameterCount == 0 && it.returnType == Boolean::class.javaPrimitiveType }
        if (hits.size != 1) {
            ctx.note("${HostContractId.VOICE_RECORDER_INIT} 命中 ${hits.map { it.name }}")
            return@contract null
        }
        ctx.winner = "fieldWriter:${owner.simpleName}#${record.name}"
        HostHandle(owner = owner, method = hits[0].apply { isAccessible = true })
    },

    contract(
        HostContractId.VOICE_RECORDER_TEARDOWN,
        "录音器清掉 AudioRecord 的收尾。按「写 AudioRecord 字段、且返回 void」取，不写死 z/L"
    ) { ctx ->
        val owner = ctx.classOf(HostContractId.VOICE_RECORDER) ?: return@contract null
        val record = ctx.handle(HostContractId.VOICE_RECORDER_RECORD)?.hostField ?: return@contract null
        val hits = ctx.fieldWriters(HostContractId.VOICE_RECORDER_TEARDOWN, owner, record.name)
            .filter { it.parameterCount == 0 && it.returnType == Void.TYPE }
        if (hits.size != 1) {
            ctx.note("${HostContractId.VOICE_RECORDER_TEARDOWN} 命中 ${hits.map { it.name }}")
            return@contract null
        }
        ctx.winner = "fieldWriter:${owner.simpleName}#${record.name}"
        HostHandle(owner = owner, method = hits[0].apply { isAccessible = true })
    },

    contract(
        HostContractId.VOICE_GATE,
        "窗口隐藏门禁。从读循环的日志字符串（三条互备）反查它调用的那个零参 boolean 接口方法，再取 WxHldService 上的实现"
    ) { ctx ->
        val service = loadClassOrNull(VOICE_IME_SERVICE_CLASS, ctx.classLoader)
            ?: return@contract null
        val interfaces = service.interfaces.mapTo(mutableSetOf()) { it.name }
        val invoked = ctx.methodsInvokedByStringAnchor(HostContractId.VOICE_GATE, VOICE_GATE_ANCHORS)
            .filter {
                it.parameterCount == 0 && it.returnType == Boolean::class.javaPrimitiveType &&
                    it.declaringClass.name in interfaces
            }
        val impl = invoked.mapNotNull { iface ->
            service.declaredMethods.firstOrNull {
                it.name == iface.name && it.parameterCount == 0 &&
                    it.returnType == Boolean::class.javaPrimitiveType
            }
        }.distinct()
        if (impl.size == 1) {
            impl[0].isAccessible = true
            ctx.winner = "invoke:${VOICE_GATE_ANCHORS.first()}"
            return@contract HostHandle(owner = service, method = impl[0])
        }
        ctx.note("${HostContractId.VOICE_GATE} 反查命中 ${impl.map { it.name }}")
        ctx.methodByNames(service, VOICE_GATE_NAMES) {
            parameterCount == 0 && returnType == Boolean::class.javaPrimitiveType
        }?.let { HostHandle(owner = service, method = it) }
    }
)

/**
 * 取契约结果，缺失即抛。
 *
 * 迁移既有 `runCatching { … check(…) }` 的调用点时用它保持 fail-closed 语义：
 * 契约没解析出来就等于原来那次 check 失败。
 */
internal fun requireClass(id: String): Class<*> =
    WeTypeHostContracts.classOf(id) ?: throw IllegalStateException("host contract $id unresolved")

internal fun requireMethod(id: String): Method =
    WeTypeHostContracts.methodOf(id) ?: throw IllegalStateException("host contract $id unresolved")

internal fun requireField(id: String): java.lang.reflect.Field =
    WeTypeHostContracts.fieldOf(id) ?: throw IllegalStateException("host contract $id unresolved")

internal fun requireConstant(id: String): Any =
    WeTypeHostContracts.constantOf(id) ?: throw IllegalStateException("host contract $id unresolved")

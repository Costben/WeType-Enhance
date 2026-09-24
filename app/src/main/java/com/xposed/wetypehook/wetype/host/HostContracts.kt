package com.xposed.wetypehook.wetype.host

import android.os.Bundle
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

/** 面板导航入口的历史候选名（3.5.3=p3、3.5.4=o3）。 */
private val PANEL_SWITCH_ENUM_NAMES = listOf("n3", "p3", "o3", "k3", "l3")

/** 切键盘入口的日志字符串：用来把同形的 `N#t3` 从面板导航候选里排除。 */
private val SWITCH_KEYBOARD_ANCHOR = listOf("switchKeyboardNoAnimation ")

/** 待上屏文本 getter 的历史候选名（3.5.3=B2、3.5.4=D2）。 */
private val PENDING_GETTER_NAMES = listOf("B2", "D2")

/** 强制上屏 emit 的历史候选名（3.5.3=W1、3.5.4=Y1）。 */
private val PENDING_EMIT_NAMES = listOf("W1", "Y1")

private fun contract(
    id: String,
    desc: String,
    resolve: (HostContext) -> HostHandle?
): HostContract = HostContract(id, desc, resolve)

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

    contract(
        HostContractId.CLIPBOARD_CANDIDATE_VIEW,
        "候选视图类。由 XML 膨胀，名称不经 R8 改名，可直接按名加载"
    ) { ctx ->
        ctx.classByNames(CANDIDATE_VIEW_CLASS)?.let { HostHandle(owner = it) }
    },

    contract(HostContractId.CLIPBOARD_CANDIDATE_GETTER, "候选视图 getter（按返回型取）") { ctx ->
        val owner = ctx.classOf(HostContractId.PANEL_MANAGER) ?: return@contract null
        val view = ctx.classOf(HostContractId.CLIPBOARD_CANDIDATE_VIEW) ?: return@contract null
        ctx.firstMethod(HostContractId.CLIPBOARD_CANDIDATE_GETTER, owner) {
            parameterTypes.isEmpty() && view.isAssignableFrom(returnType)
        }?.let { HostHandle(owner = owner, method = it) }
    },

    contract(HostContractId.CLIPBOARD_ENGINE, "输入引擎类（待上屏文本 + 强制上屏）") { ctx ->
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
        ctx.uniqueMethod(HostContractId.CLIPBOARD_PENDING_GETTER, owner, shape)
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

    contract(HostContractId.CLIPBOARD_ACTION, "原生动作分发器类") { ctx ->
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

    // ---- IME 服务 ----

    contract(
        HostContractId.CLIPBOARD_IME_SERVICE,
        "微信输入法 IME 服务类。由 AndroidManifest 声明，名称不经 R8 改名"
    ) { ctx ->
        ctx.classByNames(IME_SERVICE_CLASS)?.let { HostHandle(owner = it) }
    },

    contract(HostContractId.CLIPBOARD_IME_SERVICE_KICK, "提交后送交宿主编辑器的入口（仍按短名取）") { ctx ->
        val owner = ctx.classOf(HostContractId.CLIPBOARD_IME_SERVICE) ?: return@contract null
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

    contract(HostContractId.CLIPBOARD_HEIGHT_MANAGER, "翻译条高度流管理类") { ctx ->
        ctx.classByNames(HEIGHT_MANAGER_CLASS)?.let { HostHandle(owner = it) }
    },

    contract(HostContractId.CLIPBOARD_HEIGHT_SET_TEXT, "发布条高到原生高度流 (CharSequence) -> void") { ctx ->
        val owner = ctx.classOf(HostContractId.CLIPBOARD_HEIGHT_MANAGER) ?: return@contract null
        val shape: Method.() -> Boolean = {
            parameterTypes.size == 1 && parameterTypes[0] == CharSequence::class.java &&
                returnType == Void.TYPE
        }
        ctx.uniqueMethod(HostContractId.CLIPBOARD_HEIGHT_SET_TEXT, owner, shape)
            ?.let { return@contract HostHandle(owner = owner, method = it) }
        ctx.methodByNames(owner, listOf("T0"), shape)
            ?.let { HostHandle(owner = owner, method = it) }
    },

    contract(HostContractId.CLIPBOARD_HEIGHT_SET_CHAR, "清空/置空条高 (boolean) -> void") { ctx ->
        val owner = ctx.classOf(HostContractId.CLIPBOARD_HEIGHT_MANAGER) ?: return@contract null
        val shape: Method.() -> Boolean = {
            parameterTypes.size == 1 && parameterTypes[0] == Boolean::class.javaPrimitiveType &&
                returnType == Void.TYPE
        }
        ctx.uniqueMethod(HostContractId.CLIPBOARD_HEIGHT_SET_CHAR, owner, shape)
            ?.let { return@contract HostHandle(owner = owner, method = it) }
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

    contract(HostContractId.RESOURCE_CLASS_ATTR, "R.attr 类（无稳定字段名锚，仍按短名）") { ctx ->
        ctx.classByNames(RESOURCE_CLASS_ATTR_NAME)?.let { HostHandle(owner = it) }
    },

    contract(HostContractId.RESOURCE_CLASS_COLOR, "R.color 类（无稳定字段名锚，仍按短名）") { ctx ->
        ctx.classByNames(RESOURCE_CLASS_COLOR_NAME)?.let { HostHandle(owner = it) }
    },

    contract(HostContractId.RESOURCE_CLASS_DIMEN, "R.dimen 类（无稳定字段名锚，仍按短名）") { ctx ->
        ctx.classByNames(RESOURCE_CLASS_DIMEN_NAME)?.let { HostHandle(owner = it) }
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

package com.xposed.wetypehook.wetype.host

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.xposed.wetypehook.xposed.loadClassOrNull
import org.luckypray.dexkit.DexKitBridge
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap

/** 微信输入法混淆类所在的包。DexKit 查询一律限制在这个包内。 */
internal const val HOST_PACKAGE = "com.tencent.wetype.plugin.hld"

private const val TAG = "WeTypeHostContract"

/**
 * 宿主契约层：把「写死 R8 混淆名」的查找换成**有序解析策略**，并在 IME 进程启动时自检一次。
 *
 * 每个触点声明成一条 [HostContract]，解析顺序固定为
 * 字符串/枚举锚 → 形状锚 → 名字候选；命中即停。
 *
 * 两条铁律：
 * 1. 结构锚（字符串、形状）必须**唯一命中且通过语义校验**才采用，否则记进 [HostContext.notes]
 *    并让下一级策略接手 —— 绝不静默取第一个。`N#o3` / `N#t3` 同形不同义就是这个规则的反例。
 * 2. 名字候选表是最后手段，保留既有行为，不做加法也不做减法。
 *
 * 解析结果连同「哪条策略命中」一起打进 `WeTypeHostContract` 标签的日志。宿主更新后
 * 只需看一行报告就知道该改哪里，不必再翻 dex。
 */
internal class HostHandle(
    val owner: Class<*>? = null,
    val method: Method? = null,
    /**
     * 命名为 `hostField` 而非 `field`：`field` 在属性访问器里是 Kotlin 的软关键字
     * （指代 backing field），叫 `field` 会让 [label] 这类 getter 静默解析到关键字上。
     */
    val hostField: Field? = null,
    val constant: Any? = null
) {
    /** 自检报告里的人类可读名字，例如 `N#o3`。 */
    val label: String
        get() {
            val method = method
            if (method != null) return "${(owner ?: method.declaringClass).simpleName}#${method.name}"
            val hostField = hostField
            if (hostField != null) return "${(owner ?: hostField.declaringClass).simpleName}#${hostField.name}"
            val constant = constant
            if (constant is Enum<*>) return "${owner?.simpleName ?: "?"}#${constant.name}"
            return owner?.simpleName ?: "?"
        }
}

/**
 * 单次解析的上下文。承载 classLoader、DexKit bridge、已解析句柄查询与本次解析的歧义记录。
 *
 * 契约按声明顺序解析，后面的契约可以用 [handle] / [classOf] 读取前面契约的结果。
 */
internal class HostContext(
    val classLoader: ClassLoader,
    val bridge: DexKitBridge?,
    private val lookup: (String) -> HostHandle?
) {
    val notes = mutableListOf<String>()

    /** 命中的策略标签，供报告使用。 */
    var winner: String? = null

    fun note(text: String) {
        if (notes.size < 8) notes += text
    }

    fun handle(id: String): HostHandle? = lookup(id)

    fun classOf(id: String): Class<*>? = lookup(id)?.owner

    fun methodOf(id: String): Method? = lookup(id)?.method

    fun constantOf(id: String): Any? = lookup(id)?.constant
}

/** 一条宿主契约：`id` 用于报告与缓存，[resolve] 按策略顺序取句柄，取不到返回 null。 */
internal class HostContract(
    val id: String,
    val desc: String,
    val resolve: (HostContext) -> HostHandle?
)

// ---------------------------------------------------------------------------
// 解析原语
// ---------------------------------------------------------------------------

/**
 * 有序候选类名：命中第一个可加载的即采用。等价于既有的硬编码名字表，不算歧义。
 */
internal fun HostContext.classByNames(vararg names: String): Class<*>? {
    for (name in names) {
        val clazz = loadClassOrNull(name, classLoader) ?: continue
        winner = "names:$name"
        return clazz
    }
    return null
}

/**
 * 字符串锚定位类：R8 不改字符串常量，所以这条策略能跨宿主版本存活。
 *
 * 首选类级查询（「这个类用到了这些字符串」），它能把「同一类的不同方法各用一条字符串」
 * 也算进来 —— 宿主 `N` 正是这种情况（`o3` 用 onWindowShownParam、`t3` 用 switchKeyboard…）。
 * 类级查询不可用时退回方法级查询（静态初始化器 `<clinit>` 也是方法，枚举常量名落在它里面）。
 */
internal fun HostContext.classByDexStrings(
    label: String,
    anchors: List<String>,
    accept: (Class<*>) -> Boolean = { true }
): Class<*>? {
    val strategy = "dexString:${anchors.first()}"
    dexClassesUsingStrings(label, strategy, anchors, accept)?.let { return it }
    return dexDeclaringClasses(label, strategy, anchors, accept)
}

private fun HostContext.dexClassesUsingStrings(
    label: String,
    strategy: String,
    anchors: List<String>,
    accept: (Class<*>) -> Boolean
): Class<*>? {
    val dexKit = bridge ?: return null
    val classes = runCatching {
        dexKit.findClass {
            searchPackages(HOST_PACKAGE)
            matcher { usingStrings(*anchors.toTypedArray()) }
        }
    }.getOrNull().orEmpty()
        .mapNotNull { data -> classByName(data.name, classLoader) }
    return pickUniqueClass(label, strategy, classes, accept)
}

/** DexKit 给出的类名可能是点分名，也可能是 `L…;` 描述符，两种都吃。 */
private fun classByName(rawName: String, classLoader: ClassLoader): Class<*>? {
    var name = rawName
    if (name.startsWith("L") && name.endsWith(";")) name = name.substring(1, name.length - 1)
    return loadClassOrNull(name.replace('/', '.'), classLoader)
}

/**
 * 字段名锚定位类。
 *
 * 宿主 R 类的**字段名**是源码标识符，能活过 R8；R 类**类名**（`plugin.hld.s` 这类单字母
 * 短名）不能。所以 R 类一律按「哪个类声明了 `logo_iv`」这类问题来定位。
 */
internal fun HostContext.classByDexFields(
    label: String,
    fieldNames: List<String>,
    accept: (Class<*>) -> Boolean = { true }
): Class<*>? {
    val dexKit = bridge ?: return null
    val classes = runCatching {
        dexKit.findField {
            searchPackages(HOST_PACKAGE)
            matcher { name = fieldNames.first() }
        }
    }.getOrNull().orEmpty()
        .mapNotNull { data -> runCatching { data.getFieldInstance(classLoader).declaringClass }.getOrNull() }
    return pickUniqueClass(label, "fieldName:${fieldNames.first()}", classes, accept)
}

private fun HostContext.dexDeclaringClasses(
    label: String,
    strategy: String,
    anchors: List<String>,
    accept: (Class<*>) -> Boolean
): Class<*>? {
    val dexKit = bridge ?: return null
    val classes = runCatching {
        dexKit.findMethod {
            searchPackages(HOST_PACKAGE)
            matcher { usingStrings(*anchors.toTypedArray()) }
        }
    }.getOrNull().orEmpty()
        .mapNotNull { data -> runCatching { data.getMethodInstance(classLoader).declaringClass }.getOrNull() }
    return pickUniqueClass(label, strategy, classes, accept)
}

/**
 * 结构锚的唯一性判定：先按 [accept] 做语义校验，再要求恰好剩一个。
 *
 * 校验不过的候选会被记进 notes —— 这既是安全网（错锚定不会污染结果），
 * 也是报告里「下次该改哪」的线索。
 */
private fun HostContext.pickUniqueClass(
    label: String,
    strategy: String,
    candidates: List<Class<*>>,
    accept: (Class<*>) -> Boolean
): Class<*>? {
    val unique = candidates.distinct()
    if (unique.isEmpty()) return null
    val accepted = unique.filter(accept)
    if (accepted.size == 1) {
        winner = strategy
        return accepted[0]
    }
    val rejected = unique.size - accepted.size
    note(
        if (accepted.isEmpty()) {
            "$label $strategy 命中 ${unique.map { it.simpleName }} 但都没通过语义校验"
        } else {
            "$label $strategy 命中 ${accepted.map { it.simpleName }} 不唯一" +
                if (rejected > 0) "（另有 $rejected 个未通过语义校验）" else ""
        }
    )
    return null
}

/**
 * 在 [owner] 及其父类上按形状取**唯一**方法。命中多个即记歧义返回 null。
 *
 * 与 [methodByNames] 的分工：这里不允许「取第一个」，因为同形不同义在宿主里真实存在。
 */
internal fun HostContext.uniqueMethod(
    label: String,
    owner: Class<*>,
    predicate: Method.() -> Boolean
): Method? {
    var searchClass: Class<*>? = owner
    while (searchClass != null) {
        val hits = searchClass.declaredMethods.filter(predicate)
        if (hits.size == 1) {
            hits[0].isAccessible = true
            winner = "shape:${searchClass.simpleName}#${hits[0].name}"
            return hits[0]
        }
        if (hits.size > 1) {
            note("$label shape 在 ${searchClass.simpleName} 命中 ${hits.map { it.name }}")
            return null
        }
        searchClass = searchClass.superclass
    }
    return null
}

/**
 * 与 [uniqueMethod] 相对：**按既有次序取第一个**命中，命中多个时只记一条 note。
 *
 * 用于迁移既有 `declaredMethods.firstOrNull { … }` 的查找点 —— 那些位置的真实语义
 * 就是「取第一个」（例如宿主 `N` 上有两个都返回同一个 `N.l` 的等价 getter），
 * 在这里改成严格唯一会让原本能用的链路 fail-closed。歧义仍进报告，只是不阻断。
 */
internal fun HostContext.firstMethod(
    label: String,
    owner: Class<*>,
    predicate: Method.() -> Boolean
): Method? {
    var searchClass: Class<*>? = owner
    while (searchClass != null) {
        val hits = searchClass.declaredMethods.filter(predicate)
        if (hits.isNotEmpty()) {
            if (hits.size > 1) {
                note("$label 在 ${searchClass.simpleName} 有 ${hits.size} 个同形候选 ${hits.map { it.name }}，取第一个")
            }
            hits[0].isAccessible = true
            winner = "first:${searchClass.simpleName}#${hits[0].name}"
            return hits[0]
        }
        searchClass = searchClass.superclass
    }
    return null
}

/**
 * 在 [owner] 上按形状取方法，但先排除 [excluded] 里的方法。
 *
 * 用于 `N#o3` / `N#t3` 这种同形不同义：先用字符串锚把「切键盘」那条找出来排除，
 * 剩下的就是面板导航；排除后仍不唯一就记歧义并让名字候选接手。
 */
internal fun HostContext.uniqueMethodExcluding(
    label: String,
    owner: Class<*>,
    excluded: Set<Method>,
    predicate: Method.() -> Boolean
): Method? {
    val hits = owner.declaredMethods.filter { it !in excluded && it.predicate() }
    return when (hits.size) {
        0 -> null
        1 -> {
            hits[0].isAccessible = true
            winner = "shape-:${owner.simpleName}#${hits[0].name}"
            hits[0]
        }
        else -> {
            note("$label shape 在 ${owner.simpleName} 命中 ${hits.map { it.name }}")
            null
        }
    }
}

/**
 * 有序方法名候选 + 形状校验：命中第一个即采用。这是既有候选表的语义，不算歧义。
 */
internal fun HostContext.methodByNames(
    owner: Class<*>,
    names: List<String>,
    predicate: Method.() -> Boolean
): Method? {
    for (name in names) {
        val hit = owner.declaredMethods.firstOrNull { it.name == name && it.predicate() } ?: continue
        hit.isAccessible = true
        winner = "names:${owner.simpleName}#$name"
        return hit
    }
    return null
}

/** 字符串锚在指定类里定位方法，返回全部命中（调用方通常拿去当排除集）。 */
internal fun HostContext.methodsByDexStrings(
    label: String,
    owner: Class<*>,
    anchors: List<String>,
    predicate: Method.() -> Boolean = { true }
): List<Method> {
    val dexKit = bridge ?: return emptyList()
    val hits = runCatching {
        dexKit.findMethod {
            searchPackages(HOST_PACKAGE)
            matcher {
                declaredClass = owner.name
                usingStrings(*anchors.toTypedArray())
            }
        }
    }.getOrNull().orEmpty()
        .mapNotNull { data -> runCatching { data.getMethodInstance(classLoader) }.getOrNull() }
        .filter(predicate)
        .distinct()
    if (hits.isEmpty()) note("$label dexString:${anchors.first()} 在 ${owner.simpleName} 无命中")
    return hits
}

/**
 * 取「静态自引用字段」（Kotlin object / 单例持有者）。命中多个时按既有次序取第一个并记 note。
 *
 * 返回 [Field] 而不是实例值，因为契约结果要能进指纹缓存。
 */
internal fun HostContext.firstSingletonField(label: String, owner: Class<*>): Field? {
    val hits = owner.declaredFields.filter { Modifier.isStatic(it.modifiers) && it.type == owner }
    if (hits.isEmpty()) return null
    if (hits.size > 1) {
        note("$label singleton 在 ${owner.simpleName} 有 ${hits.size} 个自引用静态字段 ${hits.map { it.name }}，取第一个")
    }
    val field = hits[0].apply { isAccessible = true }
    winner = "singleton:${owner.simpleName}#${field.name}"
    return field
}

/** 在 [owner] 及其父类上按形状取**唯一**字段。 */
internal fun HostContext.uniqueField(
    label: String,
    owner: Class<*>,
    predicate: Field.() -> Boolean
): Field? {
    var searchClass: Class<*>? = owner
    while (searchClass != null) {
        val hits = searchClass.declaredFields.filter(predicate)
        if (hits.size == 1) {
            hits[0].isAccessible = true
            winner = "field:${searchClass.simpleName}#${hits[0].name}"
            return hits[0]
        }
        if (hits.size > 1) {
            note("$label field 在 ${searchClass.simpleName} 命中 ${hits.map { it.name }}")
            return null
        }
        searchClass = searchClass.superclass
    }
    return null
}

/** 按常量名取枚举实例。`name()` 的字符串在 dex 里是真实常量，跨版本稳定。 */
internal fun HostContext.enumConstant(owner: Class<*>, vararg names: String): Any? {
    val constants = owner.enumConstants ?: return null
    for (name in names) {
        val hit = constants.firstOrNull { (it as? Enum<*>)?.name == name } ?: continue
        winner = "enum:${owner.simpleName}#$name"
        return hit
    }
    return null
}

// ---------------------------------------------------------------------------
// 引擎：解析、报告、指纹缓存
// ---------------------------------------------------------------------------

internal sealed class ContractOutcome {
    abstract val id: String
    abstract val notes: List<String>

    class Resolved(
        override val id: String,
        val handle: HostHandle,
        val strategy: String,
        override val notes: List<String>
    ) : ContractOutcome()

    class Unresolved(
        override val id: String,
        val ambiguous: Boolean,
        override val notes: List<String>
    ) : ContractOutcome()
}

private const val CACHE_PREFS = "wetype_host_contract"
private const val CACHE_FINGERPRINT_KEY = "__fingerprint"
private const val CACHE_FORMAT = "v1"

private val PRIMITIVE_TYPES = mapOf(
    "boolean" to Boolean::class.javaPrimitiveType,
    "byte" to Byte::class.javaPrimitiveType,
    "char" to Char::class.javaPrimitiveType,
    "short" to Short::class.javaPrimitiveType,
    "int" to Int::class.javaPrimitiveType,
    "long" to Long::class.javaPrimitiveType,
    "float" to Float::class.javaPrimitiveType,
    "double" to Double::class.javaPrimitiveType,
    "void" to Void.TYPE
)

internal object WeTypeHostContracts {

    private val handles = ConcurrentHashMap<String, HostHandle>()
    private val outcomes = ConcurrentHashMap<String, ContractOutcome>()

    @Volatile
    private var installed = false

    @Volatile
    private var fingerprint: String? = null

    fun handle(id: String): HostHandle? = handles[id]

    fun classOf(id: String): Class<*>? = handles[id]?.owner

    fun methodOf(id: String): Method? = handles[id]?.method

    fun fieldOf(id: String): Field? = handles[id]?.hostField

    fun constantOf(id: String): Any? = handles[id]?.constant

    fun outcome(id: String): ContractOutcome? = outcomes[id]

    /**
     * 解析全部契约并打印自检报告。幂等；由 [com.xposed.wetypehook.MainHook] 在
     * 各 hook 组安装之前调用一次。
     *
     * 指纹未变时直接复用上次的描述符（不跑 DexKit），描述符逐条重新校验；
     * 任一失配即整轮重解析，保证缓存永远不会掩盖宿主漂移。
     */
    fun install(sourceDir: String?, classLoader: ClassLoader) {
        if (installed) return
        installed = true

        val bridge = runCatching {
            System.loadLibrary("dexkit")
            sourceDir?.let { DexKitBridge.create(it) }
        }.getOrNull()

        try {
            fingerprint = fingerprintOf(sourceDir)
            val context = HostContext(classLoader, bridge) { handles[it] }
            val restored = restoreFromCache(classLoader)
            if (restored != null && restored.size == HOST_CONTRACTS.size) {
                restored.forEach { (id, handle) ->
                    handles[id] = handle
                    outcomes[id] = ContractOutcome.Resolved(id, handle, "cache", emptyList())
                }
            } else {
                handles.clear()
                outcomes.clear()
                resolveAll(context)
            }
            persistCache()
            logReport()
        } catch (throwable: Throwable) {
            Log.e(TAG, "host contract self-check failed", throwable)
        } finally {
            runCatching { bridge?.close() }
        }
    }

    private fun resolveAll(context: HostContext) {
        for (contract in HOST_CONTRACTS) {
            context.notes.clear()
            context.winner = null
            val handle = runCatching { contract.resolve(context) }.getOrElse { error ->
                context.note("exception: ${error.javaClass.simpleName}: ${error.message}")
                null
            }
            if (handle != null) {
                handles[contract.id] = handle
                outcomes[contract.id] = ContractOutcome.Resolved(
                    id = contract.id,
                    handle = handle,
                    strategy = context.winner ?: "?",
                    notes = context.notes.toList()
                )
            } else {
                outcomes[contract.id] = ContractOutcome.Unresolved(
                    id = contract.id,
                    ambiguous = context.notes.isNotEmpty(),
                    notes = context.notes.toList()
                )
            }
        }
    }

    private fun logReport() {
        var resolved = 0
        var unresolved = 0
        for (contract in HOST_CONTRACTS) {
            when (val outcome = outcomes[contract.id]) {
                is ContractOutcome.Resolved -> {
                    resolved++
                    val notes = outcome.notes.takeIf { it.isNotEmpty() }?.joinToString("; ")?.let { " note=$it" }.orEmpty()
                    Log.i(TAG, "contract[${outcome.id}]=${outcome.handle.label} by=${outcome.strategy}$notes")
                }
                is ContractOutcome.Unresolved -> {
                    unresolved++
                    val kind = if (outcome.ambiguous) "AMBIGUOUS" else "UNRESOLVED"
                    val detail = outcome.notes.takeIf { it.isNotEmpty() }?.joinToString("; ").orEmpty()
                    Log.e(TAG, "contract[${outcome.id}]=$kind ${contract.desc} $detail")
                }
                null -> {
                    unresolved++
                    Log.e(TAG, "contract[${contract.id}]=UNRESOLVED (未解析，检查声明顺序)")
                }
            }
        }
        Log.i(
            TAG,
            "host contract self-check: $resolved/${HOST_CONTRACTS.size} resolved, " +
                "$unresolved unresolved, fingerprint=$fingerprint"
        )
    }

    /**
     * 宿主指纹：宿主 APK 的大小 + 修改时间，加缓存格式版本。
     *
     * 不用 versionName：那需要 Context，而这里只需要一个「宿主换了」的判据 ——
     * 新装/更新的 APK 大小与 mtime 必然变化。
     */
    private fun fingerprintOf(sourceDir: String?): String? {
        val file = sourceDir?.let { File(it) }?.takeIf { it.isFile } ?: return null
        return "$CACHE_FORMAT:${file.length()}:${file.lastModified()}"
    }

    private fun cachePreferences(): SharedPreferences? {
        val context = runCatching {
            Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication")
                .invoke(null) as? Context
        }.getOrNull() ?: return null
        return runCatching {
            (context.applicationContext ?: context)
                .getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE)
        }.getOrNull()
    }

    private fun persistCache() {
        val current = fingerprint ?: return
        val preferences = cachePreferences() ?: return
        runCatching {
            val editor = preferences.edit()
            editor.clear()
            editor.putString(CACHE_FINGERPRINT_KEY, current)
            outcomes.forEach { (id, outcome) ->
                if (outcome is ContractOutcome.Resolved) {
                    encode(outcome.handle)?.let { editor.putString(id, it) }
                }
            }
            editor.apply()
        }
    }

    /** 指纹一致时按描述符重建句柄；任一失配返回 null，让调用方走全量解析。 */
    private fun restoreFromCache(classLoader: ClassLoader): Map<String, HostHandle>? {
        val current = fingerprint ?: return null
        val preferences = cachePreferences() ?: return null
        if (preferences.getString(CACHE_FINGERPRINT_KEY, null) != current) return null
        val restored = LinkedHashMap<String, HostHandle>()
        for (contract in HOST_CONTRACTS) {
            val raw = preferences.getString(contract.id, null) ?: return null
            restored[contract.id] = decode(raw, classLoader) ?: return null
        }
        return restored
    }

    private fun encode(handle: HostHandle): String? = when {
        handle.method != null -> listOf(
            "m",
            handle.method.declaringClass.name,
            handle.method.name,
            handle.method.parameterTypes.joinToString(";") { it.name }
        ).joinToString("|")

        handle.hostField != null -> listOf(
            "f",
            handle.hostField.declaringClass.name,
            handle.hostField.name
        ).joinToString("|")

        handle.constant is Enum<*> ->
            listOf("e", handle.owner?.name.orEmpty(), (handle.constant as Enum<*>).name).joinToString("|")

        handle.owner != null -> listOf("c", handle.owner.name).joinToString("|")

        else -> null
    }

    private fun decode(raw: String, classLoader: ClassLoader): HostHandle? = runCatching {
        val parts = raw.split("|")
        when (parts.firstOrNull()) {
            "c" -> loadClassOrNull(parts[1], classLoader)?.let { HostHandle(owner = it) }

            "e" -> {
                val owner = loadClassOrNull(parts[1], classLoader) ?: return null
                val constant = owner.enumConstants?.firstOrNull { (it as? Enum<*>)?.name == parts[2] } ?: return null
                HostHandle(owner = owner, constant = constant)
            }

            "m" -> {
                val owner = loadClassOrNull(parts[1], classLoader) ?: return null
                val types = parts[3].takeIf { it.isNotEmpty() }
                    ?.split(";")
                    ?.map { typeByName(it, classLoader) ?: return null }
                    .orEmpty()
                val method = owner.declaredMethods.firstOrNull { candidate ->
                    candidate.name == parts[2] &&
                        candidate.parameterTypes.size == types.size &&
                        candidate.parameterTypes.indices.all { candidate.parameterTypes[it] == types[it] }
                } ?: return null
                method.isAccessible = true
                HostHandle(owner = owner, method = method)
            }

            "f" -> {
                val owner = loadClassOrNull(parts[1], classLoader) ?: return null
                val field = owner.declaredFields.firstOrNull { it.name == parts[2] } ?: return null
                field.isAccessible = true
                HostHandle(owner = owner, hostField = field)
            }

            else -> null
        }
    }.getOrNull()

    private fun typeByName(name: String, classLoader: ClassLoader): Class<*>? =
        PRIMITIVE_TYPES[name] ?: loadClassOrNull(name, classLoader)
}

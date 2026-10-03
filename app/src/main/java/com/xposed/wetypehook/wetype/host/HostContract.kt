package com.xposed.wetypehook.wetype.host

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.xposed.wetypehook.xposed.loadClassOrNull
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.matchers.MethodMatcher
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

    /**
     * 靠写死的混淆名命中时记下的来源（类名如 `names:com.tencent.wetype.plugin.hld.model.i0`，
     * 方法名如 `names:l0$d#a`）。
     *
     * 非空表示这条契约的位置是**抄下来的短名**，而不是算出来的形状 —— 宿主下次重排名字就会
     * 断，且断得无声无息。报告里会单独汇总成一行警告；这就是「保留条数」那类锚失效退化能
     * 潜伏两个版本的成因。
     */
    var nameFallback: String? = null

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
        nameFallback = winner
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

/**
 * 有序字段名候选：命中第一个「声明了该字段、且通过语义校验的类」即采用。
 *
 * R 类之间没有能跨版本分辨的方法形状，唯一稳定的东西就是字段名（R8 从不改资源标识符）。
 * 某个资源被删掉时换下一个候选，全落空才交给名字兜底。
 */
internal fun HostContext.classByFieldCandidates(
    label: String,
    fieldNames: List<String>,
    accept: (Class<*>) -> Boolean
): Class<*>? {
    for (name in fieldNames) {
        classByDexFields(label, listOf(name), accept)?.let { return it }
    }
    return null
}

/**
 * 按**字段类型**定位类，不设包范围。
 *
 * 包锚对 `plugin.hld` 之外的类无效 —— 宿主把 `MMPcmRecorder` 放在默认包 `l6` 下，包名
 * 本身也是 R8 产物。但「哪个类持有 `android.media.AudioRecord`」是结构事实，与包名和类名
 * 都无关。候选里通常还混着读线程一类的同类持有者，靠 [accept] 的成员形状区分。
 */
internal fun HostContext.classByFieldType(
    label: String,
    typeName: String,
    accept: (Class<*>) -> Boolean
): Class<*>? {
    val dexKit = bridge ?: return null
    val classes = runCatching {
        dexKit.findField {
            matcher { type = typeName }
        }
    }.getOrNull().orEmpty()
        .mapNotNull { data -> runCatching { data.getFieldInstance(classLoader).declaringClass }.getOrNull() }
    return pickUniqueClass(label, "fieldType:$typeName", classes, accept)
}

/**
 * 写 [owner] 上字段 [fieldName] 的**全部**方法。
 *
 * `FieldData.writers` 描述的是「宿主自己怎么用它」，比方法名稳：录音器的 init 与收尾写的是
 * 同一个 `AudioRecord` 字段，两者的差异只剩返回类型（init 返回 boolean，收尾是 void）。
 * 这样两个挂载点都能算出来，不必写死 `t` / `u` / `z` / `L` 这类每次发版都重排的短名。
 */
internal fun HostContext.fieldWriters(label: String, owner: Class<*>, fieldName: String): List<Method> {
    val dexKit = bridge ?: return emptyList()
    val fields = runCatching {
        dexKit.findField {
            matcher {
                declaredClass = owner.name
                name = fieldName
            }
        }
    }.getOrNull().orEmpty()
    if (fields.isEmpty()) {
        note("$label 找不到 ${owner.simpleName}#$fieldName")
        return emptyList()
    }
    return fields.asSequence()
        .flatMap { data -> runCatching { data.writers }.getOrNull().orEmpty().asSequence() }
        .mapNotNull { writer -> runCatching { writer.getMethodInstance(classLoader) }.getOrNull() }
        .distinct()
        .toList()
}

/**
 * 「用到字符串 [anchors] 中某一条的方法」所调用的全部方法。
 *
 * 日志字符串能活过 R8，而它调用的门禁方法名每次发版都在重排。用锚字符串反查调用关系，
 * 就不必写死 `O` / `P` 这类短名。
 *
 * 锚按顺序试，取**第一个唯一命中**的 —— 单条字符串被删改是宿主更新里最容易发生的事，
 * 多备几条就多一层。全部不唯一时不猜，交给下一级策略。
 */
internal fun HostContext.methodsInvokedByStringAnchor(label: String, anchors: List<String>): List<Method> {
    val dexKit = bridge ?: return emptyList()
    val tried = mutableListOf<String>()
    for (anchor in anchors) {
        val hits = runCatching {
            dexKit.findMethod {
                matcher { usingStrings(anchor) }
            }
        }.getOrNull().orEmpty()
        if (hits.size != 1) {
            tried += "$anchor×${hits.size}"
            continue
        }
        if (tried.isNotEmpty()) note("$label 前 ${tried.size} 个锚未唯一命中（${tried.joinToString(";")}），改用 $anchor")
        return hits[0].invokes
            .mapNotNull { invoked -> runCatching { invoked.getMethodInstance(classLoader) }.getOrNull() }
    }
    note("$label dexString 全部未唯一命中：${tried.joinToString(";")}")
    return emptyList()
}

/** R 类都是「清一色静态 int」的资源表；字段类型不对或数量太少就不是。 */
internal fun looksLikeResourceTable(clazz: Class<*>, minFields: Int = 300): Boolean {
    val statics = clazz.declaredFields.filter { Modifier.isStatic(it.modifiers) }
    return statics.size >= minFields &&
        statics.all { it.type == Int::class.javaPrimitiveType || it.type == Integer::class.java }
}

/** 类里有没有声明其中至少 [min] 个字段名 —— 用来把同族的几个 R 类区分开。 */
internal fun declaresAtLeast(clazz: Class<*>, names: List<String>, min: Int): Boolean {
    val declared = clazz.declaredFields.asSequence()
        .filter { Modifier.isStatic(it.modifiers) }
        .mapTo(mutableSetOf()) { it.name }
    return names.count { it in declared } >= min
}

/**
 * 一条方法形状：参数类型全列 + 返回类型。两者都用 Java 名（`boolean`、`java.lang.String`），
 * 与 DexKit 的查询参数同一套写法。
 */
internal data class MethodShape(val paramTypes: List<String>, val returnType: String) {
    /** 报告用的短标签，例如 `(String,boolean,boolean)void`。 */
    val label: String
        get() = "(${paramTypes.joinToString(",") { it.substringAfterLast('.') }})" +
            returnType.substringAfterLast('.')
}

/**
 * 方法**形状**锚定位类：要求同一个类同时声明若干条形状，再按 [accept] 做语义校验。
 *
 * 这是「不写死类名」的主力。宿主每次发版都会重排混淆名，但方法签名是源码形状，跨版本稳定：
 * 实测 `(boolean)CharSequence` 与 `(String,boolean,boolean)void` 的交集在 3.5.4 与 4.0.0 上
 * 都唯一命中输入引擎类，`(int,Object)void` + 静态自引用字段唯一命中动作分发器。
 *
 * 单条形状通常不唯一（`(CharSequence)void` 在 4.0.0 命中 116 个类），所以判据是
 * **若干条形状的交集**再叠 [accept] 的结构校验；任一环节不唯一就返回 null 交给下一级策略。
 *
 * 形状优先挑**框架回调**（View / IME 生命周期覆写）：R8 不能给虚方法改名，也不能删掉它，
 * 签名跨版本天然稳定。**别用合成方法**（lambda 宿主方法、匿名类成员）——它们的宿主方法会随
 * 版本被合并或挪走，实测 `(Canvas,View,long)boolean` 在 3.5.3 上就从候选视图挪到了 `view.qmui.b`。
 */
internal fun HostContext.classByMethodShapes(
    label: String,
    shapes: List<MethodShape>,
    accept: (Class<*>) -> Boolean = { true }
): Class<*>? {
    val dexKit = bridge ?: return null
    var names: Set<String>? = null
    for (shape in shapes) {
        val hits = runCatching {
            dexKit.findMethod {
                searchPackages(HOST_PACKAGE)
                matcher {
                    returnType = shape.returnType
                    paramCount = shape.paramTypes.size
                    shape.paramTypes.forEach { addParamType(it) }
                }
            }
        }.getOrNull().orEmpty()
            .mapNotNull { data ->
                runCatching { data.getMethodInstance(classLoader).declaringClass }.getOrNull()
            }
            .mapTo(mutableSetOf()) { it.name }
        names = names?.intersect(hits) ?: hits
        if (names.isEmpty()) return null
    }
    val classes = names.orEmpty().mapNotNull { loadClassOrNull(it, classLoader) }
    return pickUniqueClass(
        label,
        "shapeClass:${shapes.joinToString("+") { it.label }}",
        classes,
        accept
    )
}

/** 类里有没有「类型等于本类自己」的静态字段 —— 宿主单例的固定形状，与命名无关。 */
internal fun hasStaticSelfField(clazz: Class<*>): Boolean =
    clazz.declaredFields.any { Modifier.isStatic(it.modifiers) && it.type == clazz }

/**
 * 按包取全部类，交给 Kotlin 侧做结构筛选。
 *
 * DexKit 的查询表达式只吃**具体类型名**，表达不了「字段类型就是本类」「第一个参数就是本类」
 * 这类成员之间的相互关系；这类判据只能先取整包类、再在 JVM 侧判。包名与成员形状都能活过 R8，
 * 所以它和 [classByMethodShapes] 一样跨版本成立。
 */
internal fun HostContext.classesInPackages(vararg packages: String): List<Class<*>> {
    val dexKit = bridge ?: return emptyList()
    return runCatching {
        dexKit.findClass { searchPackages(*packages) }
    }.getOrNull().orEmpty().mapNotNull { classByName(it.name, classLoader) }
}

/** [classesInPackages] 之后按 [accept] 取唯一命中；不唯一即返回 null 交给名字候选。 */
internal fun HostContext.classByPackageScan(
    label: String,
    strategy: String,
    packages: List<String>,
    accept: (Class<*>) -> Boolean
): Class<*>? = pickUniqueClass(label, strategy, classesInPackages(*packages.toTypedArray()), accept)

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
 * 同形候选里取「原始方法」：包装方法一定转调原始方法，原始方法不转调包装。
 *
 * 引擎类上会同时存在原始取法与门控包装，两者形状完全相同，形状挑不出唯一；方法名又会随
 * 宿主更新重排（3.5.4 的 `D2` 在 4.0.0 变成 `E2`，旧名还被别的同形方法占用），所以唯一
 * 能跨版本成立的判据是调用关系。
 *
 * [callersOf] 给出「[owner] 内调用该方法的其它方法名」，默认走 DexKit；拿不到 bridge 时
 * 返回空集，本函数随即返回 null，由名字候选表接手。
 */
internal fun HostContext.uniqueShapePrimitive(
    label: String,
    owner: Class<*>,
    predicate: Method.() -> Boolean,
    callersOf: (Method) -> Set<String> = { dexCallerNames(owner, it) }
): Method? {
    var searchClass: Class<*>? = owner
    while (searchClass != null) {
        val hits = searchClass.declaredMethods.filter(predicate)
        if (hits.isNotEmpty()) {
            if (hits.size == 1) {
                hits[0].isAccessible = true
                winner = "shape:${searchClass.simpleName}#${hits[0].name}"
                return hits[0]
            }
            val primitives = hits.filter { candidate ->
                val callers = callersOf(candidate)
                hits.any { sibling -> sibling !== candidate && sibling.name in callers }
            }
            if (primitives.size == 1) {
                primitives[0].isAccessible = true
                winner = "shape-primitive:${searchClass.simpleName}#${primitives[0].name}"
                return primitives[0]
            }
            note("$label shape 在 ${searchClass.simpleName} 命中 ${hits.map { it.name }}")
            return null
        }
        searchClass = searchClass.superclass
    }
    return null
}

/** [owner] 内调用 [callee] 的方法名。走 DexKit；无 bridge 或无命中时返回空集。 */
private fun HostContext.dexCallerNames(owner: Class<*>, callee: Method): Set<String> {
    val dexKit = bridge ?: return emptySet()
    return runCatching {
        dexKit.findMethod {
            searchPackages(HOST_PACKAGE)
            matcher {
                declaredClass = owner.name
                invokeMethods { add(MethodMatcher(callee)) }
            }
        }
    }.getOrNull().orEmpty().mapTo(mutableSetOf()) { it.name }
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
        nameFallback = winner
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
        override val notes: List<String>,
        /**
         * 本条是靠名字候选兜住的（非空即退化）。缓存恢复的分支拿不到这个信息，只有走
         * 完整解析的那一轮才知道；报告里据此单独汇总一行警告。
         */
        val degraded: String? = null
    ) : ContractOutcome()

    class Unresolved(
        override val id: String,
        val ambiguous: Boolean,
        override val notes: List<String>
    ) : ContractOutcome()
}

private const val CACHE_PREFS = "wetype_host_contract"
private const val CACHE_FINGERPRINT_KEY = "__fingerprint"

/**
 * 缓存格式版本。v2 起才把「字段 + 方法」双载体句柄的两个载体都写进描述符（companion 那条
 * 契约就是这种形状）。v1 写下的条目只留方法，读回来字段为空；这种旧值仍能解码成非 null，
 * 缓存不会自我修正，所以必须靠版本号整轮作废。
 */
private const val CACHE_FORMAT = "v2"

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
            context.nameFallback = null
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
                    notes = context.notes.toList(),
                    degraded = context.nameFallback
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
        val degraded = mutableListOf<String>()
        for (contract in HOST_CONTRACTS) {
            when (val outcome = outcomes[contract.id]) {
                is ContractOutcome.Resolved -> {
                    resolved++
                    val notes = outcome.notes.takeIf { it.isNotEmpty() }?.joinToString("; ")?.let { " note=$it" }.orEmpty()
                    // 双载体句柄（字段 + 方法）的 label 只显示方法，字段丢没丢看不出来 —— 报告里必须显式写。
                    val field = outcome.handle.hostField
                        ?.let { " field=${(outcome.handle.owner ?: it.declaringClass).simpleName}#${it.name}" }
                        .orEmpty()
                    Log.i(TAG, "contract[${outcome.id}]=${outcome.handle.label}$field by=${outcome.strategy}$notes")
                    // 靠写死的短名命中时单独提一行警告：以前这种位置只能在 by= 里看出来，
                    // 容易漏读，宿主更新前扫一眼就知道哪几条该补形状判据。
                    outcome.degraded?.let {
                        degraded += outcome.id
                        Log.w(TAG, "contract[${outcome.id}] 靠写死的混淆名命中: $it")
                    }
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
        if (degraded.isNotEmpty()) {
            Log.w(
                TAG,
                "host contract 靠写死的混淆名 ${degraded.size}/${HOST_CONTRACTS.size} 条 $degraded" +
                    " —— 宿主重排名字后这几条会断，优先补形状判据"
            )
        }
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

    /**
     * 指纹一致时按描述符重建句柄；任一失配返回 null，让调用方走全量解析。
     *
     * 每条放弃的原因都要写日志：静默回退会让「缓存永远不生效」这种问题一直看不出来。
     */
    private fun restoreFromCache(classLoader: ClassLoader): Map<String, HostHandle>? {
        val current = fingerprint ?: return null
        val preferences = cachePreferences() ?: return null
        val stored = preferences.getString(CACHE_FINGERPRINT_KEY, null)
        if (stored != current) {
            Log.i(TAG, "host contract cache skipped: fingerprint stored=$stored current=$current")
            return null
        }
        val restored = LinkedHashMap<String, HostHandle>()
        for (contract in HOST_CONTRACTS) {
            val raw = preferences.getString(contract.id, null)
            if (raw == null) {
                Log.i(TAG, "host contract cache skipped: ${contract.id} 无条目")
                return null
            }
            val handle = decode(raw, classLoader)
            if (handle == null) {
                Log.i(TAG, "host contract cache skipped: ${contract.id} 无法重建 from=$raw")
                return null
            }
            restored[contract.id] = handle
        }
        return restored
    }

    internal fun encode(handle: HostHandle): String? = when {
        handle.method != null && handle.hostField != null -> listOf(
            "mf",
            handle.method.declaringClass.name,
            handle.method.name,
            handle.method.parameterTypes.joinToString(";") { it.name },
            handle.hostField.declaringClass.name,
            handle.hostField.name
        ).joinToString("|")

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

    internal fun decode(raw: String, classLoader: ClassLoader): HostHandle? = runCatching {
        val parts = raw.split("|")
        when (parts.firstOrNull()) {
            "c" -> loadClassOrNull(parts[1], classLoader)?.let { HostHandle(owner = it) }

            "e" -> {
                val owner = loadClassOrNull(parts[1], classLoader) ?: return null
                val constant = owner.enumConstants?.firstOrNull { (it as? Enum<*>)?.name == parts[2] } ?: return null
                HostHandle(owner = owner, constant = constant)
            }

            "mf" -> {
                val method = methodByDescriptor(parts[1], parts[2], parts[3], classLoader) ?: return null
                val field = fieldByDescriptor(parts[4], parts[5], classLoader) ?: return null
                HostHandle(owner = method.declaringClass, method = method, hostField = field)
            }

            "m" -> methodByDescriptor(parts[1], parts[2], parts[3], classLoader)
                ?.let { HostHandle(owner = it.declaringClass, method = it) }

            "f" -> fieldByDescriptor(parts[1], parts[2], classLoader)
                ?.let { HostHandle(owner = it.declaringClass, hostField = it) }

            else -> null
        }
    }.getOrNull()

    private fun methodByDescriptor(
        ownerName: String,
        name: String,
        parameters: String,
        classLoader: ClassLoader
    ): Method? {
        val owner = loadClassOrNull(ownerName, classLoader) ?: return null
        val types = parameters.takeIf { it.isNotEmpty() }
            ?.split(";")
            ?.map { typeByName(it, classLoader) ?: return null }
            .orEmpty()
        val method = owner.declaredMethods.firstOrNull { candidate ->
            candidate.name == name &&
                candidate.parameterTypes.size == types.size &&
                candidate.parameterTypes.indices.all { candidate.parameterTypes[it] == types[it] }
        } ?: return null
        method.isAccessible = true
        return method
    }

    private fun fieldByDescriptor(ownerName: String, name: String, classLoader: ClassLoader): Field? {
        val owner = loadClassOrNull(ownerName, classLoader) ?: return null
        val field = owner.declaredFields.firstOrNull { it.name == name } ?: return null
        field.isAccessible = true
        return field
    }

    private fun typeByName(name: String, classLoader: ClassLoader): Class<*>? =
        PRIMITIVE_TYPES[name] ?: loadClassOrNull(name, classLoader)
}

package com.xposed.wetypehook.wetype.hook

import android.app.Application
import android.content.Context
import android.media.AudioRecord
import com.xposed.wetypehook.wetype.host.HostContext
import com.xposed.wetypehook.wetype.host.MethodShape
import com.xposed.wetypehook.wetype.host.classByDexStrings
import com.xposed.wetypehook.wetype.host.classByMethodShapes
import com.xposed.wetypehook.wetype.host.hasStaticSelfField
import com.xposed.wetypehook.wetype.voice.FakeAudioRecord
import com.xposed.wetypehook.wetype.voice.WeTypeVoiceBridge
import com.xposed.wetypehook.wetype.settings.WeTypeSettings
import com.xposed.wetypehook.xposed.Log
import com.xposed.wetypehook.xposed.hookAfter
import com.xposed.wetypehook.xposed.hookBefore
import org.luckypray.dexkit.DexKitBridge
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * 把微信输入法的语音识别借给外部用：**换掉宿主的麦克风，音频从外部喂进来，转录从外部读走。**
 *
 * 链路（全部发生在微信输入法进程内）：
 *
 * ```
 * Eta ──TCP 127.0.0.1:18515──▶ WeTypeVoiceBridge ──队列──▶ FakeAudioRecord.read()
 *                                                                │
 *                          宿主读线程 → 识别引擎 → 转录回调 ──────┘
 *                                                                │
 * Eta ◀────────────────────── 转录增量 ◀──────────────────────────┘
 * ```
 *
 * 为什么能绕开「键盘必须可见」：宿主对录音的判据落在 `AudioRecord` 上，而 audioserver
 * 的静音只掐「从硬件麦克风读」这条原生路径。我们把 `AudioRecord` 换成不发原生调用的
 * 假货（见 [WeTypeVoiceBridge] 的类注释与 `wetype-voice-fake-record-swap` 记录），
 * 静音检查根本不参与，于是不弹键盘也能借到识别。
 *
 * 复用宿主的定位一律按**形状/字符串锚**，不写死混淆名 —— 宿主更新后短名会整体漂移。
 */
internal object WeTypeVoiceHooks {

    private const val TAG = "WeTypeVoice"

    /** 录音器字符串锚：`voice.E` 的 `initDeviceInLock`。 */
    private const val RECORDER_ANCHOR = "initDeviceInLock"

    /** 输入法服务。只用来问系统「输入法跑在哪个进程」，不 hook 它。 */
    private const val IME_SERVICE_CLASS = "com.tencent.wetype.plugin.hld.WxHldService"

    @Volatile
    private var hostClassLoader: ClassLoader? = null

    private var appContextRef: WeakReference<Context>? = null

    /** 宿主语音单例的**类**。绝不为了拿实例去碰 `declaredFields` —— `<clinit>` 会读 MMKV。 */
    @Volatile
    private var singletonClass: Class<*>? = null

    @Volatile
    private var singletonInstance: Any? = null

    // ---- 录音器（`MMPcmRecorder`，3.5.x = `l6.e`）相关句柄 ----

    @Volatile
    private var pcmRecorderClass: Class<*>? = null

    @Volatile
    private var pcmRecorderRecordField: Field? = null

    @Volatile
    private var readModeField: Field? = null

    @Volatile
    private var readThreadRecordField: Field? = null

    @Volatile
    private var stopRecordMethod: Method? = null

    @Volatile
    private var realRecord: AudioRecord? = null

    /** 换录音器是否已就绪（字段都定位到了）。 */
    @Volatile
    private var swapReady = false

    /** 上一次拉起会话的时间，用来给「连上就启动」做节流，避免重连风暴里反复调宿主。 */
    @Volatile
    private var lastStartAt = 0L

    private const val START_THROTTLE_MS = 1500L

    // ------------------------------------------------------------------
    // 安装
    // ------------------------------------------------------------------

    fun install(sourceDir: String?, classLoader: ClassLoader) {
        hostClassLoader = classLoader
        val bridge = runCatching {
            System.loadLibrary("dexkit")
            sourceDir?.let { DexKitBridge.create(it) }
        }.getOrNull()
        if (bridge == null) {
            Log.i("$TAG: DexKit bridge unavailable; voice bridge disabled")
            return
        }
        try {
            val context = HostContext(classLoader, bridge) { null }
            val singleton = resolveVoiceSingleton(context)
            if (singleton == null) {
                Log.i("$TAG: voice singleton unresolved; notes=${context.notes}")
                return
            }
            singletonClass = singleton
            hookTranscript(singleton)

            // 录音器按「持有 AudioRecord + 无参 G()Z / I()Z」的形状定位，
            // 从锚类的字段类型反向取 —— 编译期字段类型是混淆改不掉的边。
            val recorderAnchor = context.classByDexStrings("voice.recorder", listOf(RECORDER_ANCHOR))
            val pcmRecorder = resolvePcmRecorderClass(recorderAnchor)
            if (pcmRecorder == null) {
                Log.i("$TAG: pcmRecorder unresolved; cannot borrow recognition")
                return
            }
            grabRecorderFields(pcmRecorder)
            hookRecordSwap()
            hookWindowHiddenGate()

            // 会话生命周期挂在 Eta 的连接上：连上就拉起宿主语音，EOS 就收尾。
            WeTypeVoiceBridge.onClientConnected = { startVoiceSession() }
            WeTypeVoiceBridge.onStreamEnd = { stopVoiceSession() }

            hookApplicationContext()
            Log.i("$TAG: installed. singleton=${singleton.name} recorder=${pcmRecorder.name} swapReady=$swapReady")
        } finally {
            runCatching { bridge.close() }
        }
    }

    // ------------------------------------------------------------------
    // 定位
    // ------------------------------------------------------------------

    /**
     * 转录回调的形状在版本间只差尾巴上那个会话句柄，所以两条形状都试；命中即停。
     *
     * 同形状还会命中声明它的接口与匿名实现，所以叠一条语义校验：**非接口、非抽象、
     * 且带静态自引用字段**（宿主单例的固定形状）。
     */
    private fun resolveVoiceSingleton(context: HostContext): Class<*>? {
        val head = listOf(
            "java.lang.String[]", "java.util.List", "boolean", "boolean",
            "java.lang.String", "java.lang.String", "java.lang.String",
            "long", "long", "java.util.List", "boolean", "java.lang.String",
            "int", "int", "int"
        )
        val accept: (Class<*>) -> Boolean = { clazz ->
            !clazz.isInterface && !Modifier.isAbstract(clazz.modifiers) && hasStaticSelfField(clazz)
        }
        for (extra in listOf(emptyList<String>(), listOf("java.lang.Object"))) {
            val shape = MethodShape(head + extra, "void")
            context.classByMethodShapes("voice.singleton", listOf(shape), accept)?.let { return it }
        }
        return null
    }

    /**
     * 按形状认领录音器：**持有 `AudioRecord` 字段** + 声明了无参返回 boolean 的
     * `G()`（startRecord）/ `I()`（stopRecord）。
     *
     * 不写死类名，也不能只在锚的嵌套树里找（录音器是顶层类）。稳的取法是从**持有者的
     * 字段类型**反向取：锚命中的 `voice.E$c` 是 `voice.E` 的内部类，而 `voice.E` 上
     * `mPcmRecorder` 字段的声明类型就是它。
     */
    private fun resolvePcmRecorderClass(recorderAnchor: Class<*>?): Class<*>? {
        val owners = linkedSetOf<Class<*>>()
        recorderAnchor?.let {
            collectNested(it, owners)
            it.enclosingClass?.let { outer ->
                owners += outer
                outer.enclosingClass?.let { owners += it }
            }
        }
        for (owner in owners) {
            for (field in owner.declaredFields) {
                if (Modifier.isStatic(field.modifiers)) continue
                if (isPcmRecorderShape(field.type)) {
                    Log.i("$TAG: pcmRecorder via ${owner.simpleName}.${field.name} : ${field.type.name}")
                    return field.type
                }
            }
        }
        return null
    }

    private fun isPcmRecorderShape(clazz: Class<*>): Boolean {
        if (clazz.isInterface || Modifier.isAbstract(clazz.modifiers)) return false
        if (clazz.isPrimitive || clazz.isArray) return false
        val holdsRecord = clazz.declaredFields.any {
            !Modifier.isStatic(it.modifiers) && AudioRecord::class.java.isAssignableFrom(it.type)
        }
        if (!holdsRecord) return false
        val zeroArgBooleans = clazz.declaredMethods
            .filter {
                it.parameterCount == 0 &&
                    it.returnType == Boolean::class.javaPrimitiveType &&
                    !Modifier.isStatic(it.modifiers)
            }
            .map { it.name }
            .toSet()
        return zeroArgBooleans.containsAll(setOf("G", "I"))
    }

    private fun collectNested(clazz: Class<*>, out: MutableSet<Class<*>>) {
        if (!out.add(clazz)) return
        runCatching { clazz.declaredClasses }.getOrDefault(emptyArray())
            .forEach { collectNested(it, out) }
    }

    private fun hasStaticSelfField(clazz: Class<*>): Boolean =
        clazz.declaredFields.any { Modifier.isStatic(it.modifiers) && it.type == clazz }

    private fun staticSelfField(clazz: Class<*>): Field? =
        clazz.declaredFields.firstOrNull {
            Modifier.isStatic(it.modifiers) && it.type == clazz
        }?.apply { isAccessible = true }

    // ------------------------------------------------------------------
    // 转录出口
    // ------------------------------------------------------------------

    /**
     * 转录回调：`(String[], List, boolean, boolean, String, …, int, int, int)void`。
     * `args[0]` 是分片，拼起来才是文本；`args[3]` 是本句结束标志。
     */
    private fun hookTranscript(clazz: Class<*>) {
        val method = clazz.declaredMethods.firstOrNull(::isTranscriptCallback) ?: run {
            Log.i("$TAG: transcript callback not found on ${clazz.name}")
            return
        }
        method.isAccessible = true
        method.hookAfter { param ->
            val args = param.args
            val text = (args.getOrNull(0) as? Array<*>)?.joinToString("") { it?.toString().orEmpty() }
                .orEmpty()
            val endFlag = args.getOrNull(3) as? Boolean ?: false
            if (text.isNotEmpty()) {
                WeTypeVoiceBridge.emitTranscript(text, endFlag)
            }
        }
        Log.i("$TAG: transcript callback hooked on ${clazz.name}#${method.name}")
    }

    private fun isTranscriptCallback(method: Method): Boolean {
        if (method.returnType != Void.TYPE) return false
        if (Modifier.isAbstract(method.modifiers)) return false
        val types = method.parameterTypes
        if (types.size < 15) return false
        return types[0] == Array<String>::class.java &&
            List::class.java.isAssignableFrom(types[1]) &&
            types[2] == Boolean::class.javaPrimitiveType &&
            types[3] == Boolean::class.javaPrimitiveType &&
            types[4] == String::class.java
    }

    // ------------------------------------------------------------------
    // 换录音器
    // ------------------------------------------------------------------

    /** 定位录音器上那三个字段与 stopRecord 句柄。 */
    private fun grabRecorderFields(pcmRecorder: Class<*>) {
        pcmRecorderClass = pcmRecorder
        // 宿主持有的真 AudioRecord。字段**声明类型**就是 `android.media.AudioRecord`。
        pcmRecorderRecordField = pcmRecorder.declaredFields.firstOrNull {
            !Modifier.isStatic(it.modifiers) && AudioRecord::class.java.isAssignableFrom(it.type)
        }?.apply { isAccessible = true }
        stopRecordMethod = pcmRecorder.declaredMethods.firstOrNull {
            it.name == "I" && it.parameterCount == 0 &&
                it.returnType == Boolean::class.javaPrimitiveType
        }?.apply { isAccessible = true }
        // 读线程字段：声明类型是**抽象类**、且抽象方法里有 `()Z` 和 `()V` 的那个实例字段
        // （3.5.x = `y`，声明类型 `l6.j`，实际装 `l6.i`）。不能按 Runnable 匹配 ——
        // Runnable 是那个基类的**内部类**；也不能按「声明类型持有 AudioRecord」匹配 ——
        // AudioRecord 字段在**子类**上，不在基类上。子类那份等 `t()` 跑出实例后按运行时类型取。
        readModeField = pcmRecorder.declaredFields.firstOrNull { f ->
            if (Modifier.isStatic(f.modifiers)) return@firstOrNull false
            val t = f.type
            if (!Modifier.isAbstract(t.modifiers)) return@firstOrNull false
            val m = t.declaredMethods
            m.any { it.parameterCount == 0 && it.returnType == Boolean::class.javaPrimitiveType } &&
                m.any { it.parameterCount == 0 && it.returnType == Void.TYPE }
        }?.apply { isAccessible = true }
        swapReady = pcmRecorderRecordField != null && readModeField != null && stopRecordMethod != null
        Log.i(
            "$TAG: recorder fields: record=${pcmRecorderRecordField?.name} " +
                "readMode=${readModeField?.name} stop=${stopRecordMethod?.name} ready=$swapReady"
        )
    }

    /**
     * 在 `t()`（init）的 after 换掉 `AudioRecord`。
     *
     * 为什么是 `t()` 而不是 `G()`（startRecord）的 before：`H()` 第一行就是
     * `if (this.w != null) return false`，`G()` 入口 `w` 按设计是 null；而 `t()` 里
     * `w` 与读线程都是刚建好的，真正开录的 `H()` 还没跑。此刻两个目标引用都已就位，
     * 一起换掉，不存在时序窗口。
     *
     * 要换**两个**引用，缺一不可：
     * - `w`：`H()` 里的 `startRecording()` 与 `getRecordingState() != 3` 两道判据都在它上面。
     * - 读线程自己那份：`RecordModeAsyncRead` 在**构造时**就把 `AudioRecord` 拷进自己的
     *   字段，之后改 `w` 影响不到它。它的实例就在 `readModeField` 上，同一时刻一起换。
     */
    private fun hookRecordSwap() {
        val init = pcmRecorderClass?.declaredMethods?.firstOrNull {
            it.name == "t" && it.parameterCount == 0 &&
                it.returnType == Boolean::class.javaPrimitiveType
        }?.apply { isAccessible = true }
        if (init == null || !swapReady) {
            Log.i("$TAG: record swap not armed (init=${init != null} ready=$swapReady)")
            return
        }
        init.hookAfter { param ->
            val owner = param.thisObject ?: return@hookAfter
            val real = runCatching { pcmRecorderRecordField?.get(owner) }.getOrNull() as? AudioRecord
                ?: return@hookAfter
            val fake = FakeAudioRecord.from(real) ?: run {
                Log.i("$TAG: fake record creation failed")
                return@hookAfter
            }
            realRecord = real
            runCatching { pcmRecorderRecordField?.set(owner, fake) }

            val mode = runCatching { readModeField?.get(owner) }.getOrNull()
            val threadField = readThreadRecordField ?: mode?.javaClass?.let { mc ->
                mc.declaredFields.firstOrNull {
                    !Modifier.isStatic(it.modifiers) && AudioRecord::class.java.isAssignableFrom(it.type)
                }?.apply { isAccessible = true }?.also { readThreadRecordField = it }
            }
            val swappedThread = mode != null && threadField != null &&
                runCatching { threadField.set(mode, fake) }.isSuccess

            val rate = runCatching { real.sampleRate }.getOrDefault(-1)
            val ch = runCatching { real.channelCount }.getOrDefault(-1)
            Log.i(
                "$TAG: mic replaced (rate=$rate ch=$ch host=${real.javaClass.simpleName} " +
                    "readThread=${mode?.javaClass?.simpleName} threadSwapped=$swappedThread)"
            )
            if (!swappedThread) Log.i("$TAG: WARNING read-thread record not swapped; stream will be silent")
        }

        // 收尾：`I()` before 把真货还回去，让宿主按原样走完它的 stopRecord。
        stopRecordMethod?.hookBefore { param ->
            val owner = param.thisObject ?: return@hookBefore
            val real = realRecord ?: return@hookBefore
            runCatching { pcmRecorderRecordField?.set(owner, real) }
            runCatching {
                val mode = readModeField?.get(owner)
                if (mode != null) readThreadRecordField?.set(mode, real)
            }
            realRecord = null
        }
    }

    /**
     * 录音读线程的「窗口隐藏」门禁。
     *
     * 读线程每轮都做 `if (WxHldService.N1() != null) { if (!O()) return; }`，
     * **极性别搞反**：`if-nez O()Z` —— `O()` 为 **true 才继续**，为 false 就 `return-void`
     * 打 "mAudioRecord window hidden return"，整条读线程退出（不是 continue，是退出）。
     * `O()` 的语义就是「窗口已隐藏」这**一个**位（唯一实现是 `WxHldService#O()`，
     * 方法体 `return this.c`）。借识别期间恒返 true（= 视作窗口可见）即可。
     */
    private fun hookWindowHiddenGate() {
        val service = runCatching {
            Class.forName("com.tencent.wetype.plugin.hld.WxHldService", false, hostClassLoader)
        }.getOrNull() ?: return
        val candidates = service.declaredMethods.filter {
            it.name == "O" && it.parameterCount == 0 &&
                it.returnType == Boolean::class.javaPrimitiveType &&
                !Modifier.isAbstract(it.modifiers)
        }
        for (m in candidates) {
            m.isAccessible = true
            val outcome = runCatching {
                m.hookBefore { param ->
                    if (WeTypeVoiceBridge.isClientConnected) param.result = true
                }
            }
            if (outcome.isSuccess) Log.i("$TAG: window-hidden gate hooked (${service.simpleName}#${m.name})")
        }
    }

    // ------------------------------------------------------------------
    // 会话生命周期
    // ------------------------------------------------------------------

    private fun hookApplicationContext() {
        Application::class.java.declaredMethods.firstOrNull {
            it.name == "attach" && it.parameterCount == 1 &&
                it.parameterTypes[0] == Context::class.java
        }?.apply { isAccessible = true }?.hookAfter { param ->
            val context = param.args[0] as? Context ?: return@hookAfter
            val appContext = context.applicationContext ?: context
            appContextRef = WeakReference(appContext)
            startLocalServices(appContext)
        }
    }

    /**
     * 本进程是不是**输入法进程**。
     *
     * 微信输入法有两个进程（主进程与 `:hld`），两个都在作用域里、都会装这套 hook，于是
     * 两个都会去 bind 18515/18516/18517 —— 先起的赢，后起的只打一行 `EADDRINUSE`。
     * 谁赢纯看启动顺序，而只有**跑着输入法**的那个进程才有活的录音器：如果主进程抢到端口，
     * 客户端连上后 `startVoiceSession()` 会在主进程里调，那边没有会话、`mic replaced`
     * 永远不出现，表现为「协议全对、一个转录都没有」。
     *
     * 判据不写死 `:hld`：直接问 PackageManager 输入法服务的 `processName`，由系统替我们
     * 解析出进程名（服务类名本来就已经在 [hookWindowHiddenGate] 里用到了）。
     * 查不到就按「是」处理 —— 宁可回到原来的抢端口行为，也不要因为一次查询失败把功能关死。
     */
    private fun isImeProcess(context: Context): Boolean {
        val info = runCatching {
            context.packageManager.getServiceInfo(
                android.content.ComponentName(context.packageName, IME_SERVICE_CLASS), 0
            )
        }.getOrNull() ?: return true
        val current = runCatching { Application.getProcessName() }.getOrNull() ?: return true
        return info.processName == current
    }

    /**
     * 按设置拉起回环桥（18515）。
     *
     * 放在 `Application.attach` 之后是**唯一**能早于宿主自身初始化的位置：设置页改动
     * 只影响下次进程启动，运行中不热重启服务 —— 客户端本来就要求重连，热切换只会把
     * 「正在跑的一轮识别」打断。
     *
     * 这条桥服务的是「系统识别服务」那一路：模块进程里的 `WeTypeRecognitionService`
     * 收下系统请求，把 PCM 推到本端口，由这里喂给宿主识别引擎。
     */
    private fun startLocalServices(context: Context) {
        if (!isImeProcess(context)) {
            Log.i("$TAG: not the IME process; local voice services left to the IME process")
            return
        }
        val settings = runCatching { WeTypeSettings.readSnapshot(context) }.getOrNull()
        if (settings == null) {
            Log.i("$TAG: settings snapshot unavailable; local voice services not started")
            return
        }
        if (!settings.voiceBridgeEnabled) {
            Log.i("$TAG: voice bridge disabled by settings; no local port opened")
            return
        }
        WeTypeVoiceBridge.startServer()
    }

    /**
     * 拉起宿主语音会话。
     *
     * 走宿主自己的启动入口 `(boolean, <场景枚举>)void`。实测它在键盘不可见时**也能**
     * 跑到 `voiceAddr.start()` + `startRecord()`，所以没必要自己拼链路 —— 让宿主按原本
     * 顺序建会话，我们只在录音器 init 时把那一个 `AudioRecord` 换掉。
     *
     * 节流：宿主在会话**已经起来**时 `b1()` 会直接返回，重连时重复调它是无害的，
     * 但没必要每 100ms 调一次，所以加个时间窗。
     */
    @Synchronized
    private fun startVoiceSession() {
        if (!swapReady) {
            Log.i("$TAG: cannot start voice session: recorder not resolved")
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastStartAt < START_THROTTLE_MS) return
        val clazz = singletonClass ?: return
        val context = appContextRef?.get() ?: run {
            Log.i("$TAG: cannot start voice session: no application context yet")
            return
        }
        val singleton = singletonInstance ?: runCatching { staticSelfField(clazz)?.get(null) }
            .getOrNull()?.also { singletonInstance = it } ?: return
        lastStartAt = now
        // 注意：**不要**在这里 resetStream()。缓冲是在客户端连上时清的，此刻队列里
        // 已经攒了本次会话的头几帧 —— 正是靠它们，宿主读线程第一口就吃到真音频，
        // 识别引擎才不会把这一轮判成静音。
        val invoked = runCatching { invokeStart(singleton) }
            .onFailure { Log.i("$TAG: start threw: ${it.javaClass.simpleName}: ${it.message}") }
            .getOrDefault(false)
        Log.i("$TAG: voice session start invoked=$invoked")
    }

    private fun stopVoiceSession() {
        val singleton = singletonInstance ?: return
        for (method in singleton.javaClass.declaredMethods) {
            if (Modifier.isAbstract(method.modifiers)) continue
            val args: Array<Any?> = when {
                method.returnType != Void.TYPE -> continue
                method.parameterCount == 2 &&
                    method.parameterTypes.all { it == Boolean::class.javaPrimitiveType } ->
                    arrayOf(true, false)
                method.parameterCount == 1 &&
                    method.parameterTypes[0] == Boolean::class.javaPrimitiveType -> arrayOf(true)
                method.parameterCount == 0 -> emptyArray()
                else -> continue
            }
            method.isAccessible = true
            runCatching { method.invoke(singleton, *args) }
            Log.i("$TAG: voice session stop invoked ${method.name}")
            return
        }
    }

    /** 按形状挑启动入口：`(boolean, <场景枚举>)void`。场景枚举只影响埋点，不影响识别。 */
    private fun invokeStart(singleton: Any): Boolean {
        for (method in singleton.javaClass.declaredMethods) {
            val types = method.parameterTypes
            if (types.size != 2) continue
            if (types[0] != Boolean::class.javaPrimitiveType) continue
            if (!types[1].isEnum) continue
            val scene = types[1].enumConstants?.firstOrNull() ?: continue
            method.isAccessible = true
            method.invoke(singleton, false, scene)
            return true
        }
        return false
    }
}

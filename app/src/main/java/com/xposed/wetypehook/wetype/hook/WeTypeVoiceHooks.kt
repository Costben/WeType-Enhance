package com.xposed.wetypehook.wetype.hook

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioRecord
import android.os.Build
import androidx.core.content.ContextCompat
import com.xposed.wetypehook.ModuleBridgeContract
import com.xposed.wetypehook.wetype.host.HostContractId
import com.xposed.wetypehook.wetype.host.WeTypeHostContracts
import com.xposed.wetypehook.wetype.voice.FakeAudioRecord
import com.xposed.wetypehook.wetype.voice.WeTypeVoiceBridge
import com.xposed.wetypehook.wetype.voice.shell.AsrShellHost
import com.xposed.wetypehook.wetype.settings.WeTypeSettings
import com.xposed.wetypehook.xposed.Log
import com.xposed.wetypehook.xposed.hookAfter
import com.xposed.wetypehook.xposed.hookBefore
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicBoolean

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
 * 宿主定位全部交给契约层（`voice.*`），本文件不碰 DexKit、不写死任何混淆短名。
 * 三个受支持版本（3.5.3 / 3.5.4 / 4.0.0）的类名、方法名、录音器字段名全都不同，
 * 唯一跨版本成立的是**成员形状与调用关系** —— 那正是 `HostContracts.kt` 里那几条
 * 契约在做的事。
 */
internal object WeTypeVoiceHooks {

    private const val TAG = "WeTypeVoice"

    @Volatile
    private var hostClassLoader: ClassLoader? = null

    private var appContextRef: WeakReference<Context>? = null

    /** 宿主语音单例的**类**。绝不为了拿实例去碰 `declaredFields` —— `<clinit>` 会读 MMKV。 */
    @Volatile
    private var singletonClass: Class<*>? = null

    @Volatile
    private var singletonInstance: Any? = null

    // ---- 录音器（日志里的 `MMPcmRecorder`）相关句柄 ----

    @Volatile
    private var pcmRecorderClass: Class<*>? = null

    @Volatile
    private var pcmRecorderRecordField: Field? = null

    @Volatile
    private var readModeField: Field? = null

    @Volatile
    private var readThreadRecordField: Field? = null

    /** 录音器清掉 `AudioRecord` 的收尾方法，用来把真货还回去。 */
    @Volatile
    private var teardownMethod: Method? = null

    @Volatile
    private var realRecord: AudioRecord? = null

    /** 换录音器是否已就绪（字段都定位到了）。 */
    @Volatile
    private var swapReady = false

    /**
     * 最近一个见过 `AudioRecord` 的录音器实例。
     *
     * 给「客户端在宿主会话**已经跑起来之后**才连上」兜底：那种情况下宿主不会重建录音器，
     * init 的 after 不会再跑，只能拿着这个引用补换一次。
     */
    @Volatile
    private var lastRecorderOwner: WeakReference<Any>? = null

    /** 上一次拉起会话的时间，用来给「连上就启动」做节流，避免重连风暴里反复调宿主。 */
    @Volatile
    private var lastStartAt = 0L

    private const val START_THROTTLE_MS = 1500L

    /** 壳同步广播的接收器是否已注册。每个输入法进程注册一次。 */
    private val shellSyncReceiverRegistered = AtomicBoolean(false)

    // ------------------------------------------------------------------
    // 安装
    // ------------------------------------------------------------------

    fun install(classLoader: ClassLoader) {
        hostClassLoader = classLoader
        // Application.attach 排在最前面：它拉起的是本模块自己的本地服务（18515 回环桥与
        // 18516 壳监听），与宿主语音契约无关。契约解析失败（宿主更新、短名漂移）只该让
        // 「借识别」失效，不该把壳一起拖哑。
        hookApplicationContext()

        val singleton = WeTypeHostContracts.classOf(HostContractId.VOICE_SINGLETON)
        if (singleton == null) {
            Log.i("$TAG: voice singleton unresolved; voice bridge disabled")
            return
        }
        singletonClass = singleton
        hookTranscript()
        // 门禁与录音器相互独立：录音器定位失败也不该把门禁一起丢掉。
        hookWindowHiddenGate()

        val pcmRecorder = WeTypeHostContracts.classOf(HostContractId.VOICE_RECORDER)
        if (pcmRecorder == null) {
            Log.i("$TAG: pcmRecorder unresolved; cannot borrow recognition")
            return
        }
        grabRecorderFields(pcmRecorder)
        hookRecordSwap()

        // 会话生命周期挂在 Eta 的连接上：连上就拉起宿主语音，EOS 就收尾。
        WeTypeVoiceBridge.onClientConnected = { startVoiceSession() }
        WeTypeVoiceBridge.onStreamEnd = { stopVoiceSession() }

        Log.i("$TAG: installed. singleton=${singleton.name} recorder=${pcmRecorder.name} swapReady=$swapReady")
    }

    // ------------------------------------------------------------------
    // 转录出口
    // ------------------------------------------------------------------

    /**
     * 转录回调：`(String[], List, boolean, boolean, String, …, int, int, int)void`。
     * `args[0]` 是分片，拼起来才是文本；`args[3]` 是本句结束标志。
     *
     * `args[6]` 是宿主回填的润色全文，与 `args[0]` 同为累积整句而非分片，可直接整体替换。
     * 宿主的基础润色对本轮音频可能没有改动，那时两者逐字相同，替换等价于原文。
     */
    private fun hookTranscript() {
        val method = WeTypeHostContracts.methodOf(HostContractId.VOICE_TRANSCRIPT) ?: run {
            Log.i("$TAG: transcript callback unresolved")
            return
        }
        method.hookAfter { param ->
            val args = param.args
            val raw = (args.getOrNull(0) as? Array<*>)?.joinToString("") { it?.toString().orEmpty() }
                .orEmpty()
            val polished = (args.getOrNull(6) as? String).orEmpty()
            val text = if (WeTypeSettings.isVoiceAiPolishEnabledXposed() && polished.isNotEmpty()) {
                polished
            } else {
                raw
            }
            val endFlag = args.getOrNull(3) as? Boolean ?: false
            if (text.isNotEmpty()) {
                WeTypeVoiceBridge.emitTranscript(text, endFlag)
            }
        }
        Log.i("$TAG: transcript callback hooked on ${method.declaringClass.name}#${method.name}")
    }

    // ------------------------------------------------------------------
    // 换录音器
    // ------------------------------------------------------------------

    /** 从契约层取录音器上那两个字段与收尾方法。 */
    private fun grabRecorderFields(pcmRecorder: Class<*>) {
        pcmRecorderClass = pcmRecorder
        // 宿主持有的真 AudioRecord。字段**声明类型**就是 `android.media.AudioRecord`。
        pcmRecorderRecordField = WeTypeHostContracts.fieldOf(HostContractId.VOICE_RECORDER_RECORD)
        // 读线程字段：声明类型是**抽象类**、且抽象方法里有 `()Z` 和 `()V` 的那个实例字段。
        // 不能按 Runnable 匹配 —— Runnable 是那个基类的**内部类**；也不能按「声明类型持有
        // AudioRecord」匹配 —— AudioRecord 字段在**子类**上，不在基类上。子类那份等 init
        // 跑出实例后按运行时类型取。
        readModeField = WeTypeHostContracts.fieldOf(HostContractId.VOICE_RECORDER_MODE)
        teardownMethod = WeTypeHostContracts.methodOf(HostContractId.VOICE_RECORDER_TEARDOWN)
        swapReady = pcmRecorderRecordField != null && readModeField != null && teardownMethod != null
        Log.i(
            "$TAG: recorder fields: record=${pcmRecorderRecordField?.name} " +
                "readMode=${readModeField?.name} teardown=${teardownMethod?.name} ready=$swapReady"
        )
    }

    /**
     * 在录音器 init（建 `AudioRecord` 那个方法）的 after 换掉 `AudioRecord`。
     *
     * 为什么是 init 而不是公开 start 的 before：start 的第一行就是
     * `if (this.w != null) return false`，入口处 `w` 按设计是 null；而 init 返回时
     * `w` 与读线程都是刚建好的，真正开录的 start 还没跑。此刻两个目标引用都已就位，
     * 一起换掉，不存在时序窗口。
     *
     * 要换**两个**引用，缺一不可：
     * - `w`：宿主 start 里的 `startRecording()` 与 `getRecordingState()` 两道判据都在它上面。
     * - 读线程自己那份：读线程在**构造时**就把 `AudioRecord` 拷进自己的字段，之后改 `w`
     *   影响不到它。它的实例就在读线程字段上，同一时刻一起换。
     *
     * **只在外部客户端连着的会话里换。** 宿主自己发起的语音输入要放行真麦克风，否则
     * 桥接队列空着、读线程只能补静音，宿主识别引擎永远拿不到语音。
     */
    private fun hookRecordSwap() {
        val init = WeTypeHostContracts.methodOf(HostContractId.VOICE_RECORDER_INIT)
        if (init == null || !swapReady) {
            Log.i("$TAG: record swap not armed (init=${init != null} ready=$swapReady)")
            return
        }
        init.hookAfter { param ->
            val owner = param.thisObject ?: return@hookAfter
            lastRecorderOwner = WeakReference(owner)
            // **只换外部会话的麦克风。** 宿主自己发起的语音输入（长按空格、键盘上的麦克风）
            // 必须放行真麦克风：桥接队列里此刻没有任何外部音频，换成假货后宿主读到的是
            // 纯静音，识别引擎一帧有效音频都拿不到，表现为「怎么说话都没反应」。
            if (!WeTypeVoiceBridge.isClientConnected) return@hookAfter
            swapRecorder(owner)
        }

        // 收尾：录音器清 `AudioRecord` 那个方法（353 的 `z()`、4.0.0 的 `L()`）的 before
        // 把真货还回去，让宿主按原样 release 掉，而不是让假货把这一步吃掉。
        teardownMethod?.hookBefore { param ->
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
     * 把 [owner] 上那个真 `AudioRecord` 换成 [FakeAudioRecord]，连同读线程自己那份。
     *
     * 字段里已经是假货就直接返回 —— 补换路径与 init 路径会同时走到这里，靠这一步去重。
     */
    private fun swapRecorder(owner: Any) {
        val real = runCatching { pcmRecorderRecordField?.get(owner) }.getOrNull() as? AudioRecord
            ?: return
        if (real is FakeAudioRecord) return
        val fake = FakeAudioRecord.from(real) ?: run {
            Log.i("$TAG: fake record creation failed")
            return
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

    /**
     * 录音读线程的「窗口隐藏」门禁。
     *
     * 读线程每轮都做 `if (服务单例 != null) { if (!门禁()) return; }`，
     * **极性别搞反**：`if-nez 门禁()Z` —— 门禁为 **true 才继续**，为 false 就 `return-void`
     * 打 "mAudioRecord window hidden return"，整条读线程退出（不是 continue，是退出）。
     * 门禁的语义就是「窗口已隐藏」这**一个**位（方法体 `return this.c`）。
     * 借识别期间恒返 true（= 视作窗口可见）即可。
     *
     * 方法本身由契约层从读循环的日志字符串反查得到 —— 它 3.5.3 叫 `O`、之后叫 `P`。
     */
    private fun hookWindowHiddenGate() {
        val method = WeTypeHostContracts.methodOf(HostContractId.VOICE_GATE) ?: run {
            Log.i("$TAG: window-hidden gate unresolved")
            return
        }
        method.hookBefore { param ->
            if (WeTypeVoiceBridge.isClientConnected) param.result = true
        }
        Log.i("$TAG: window-hidden gate hooked (${method.declaringClass.simpleName}#${method.name})")
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
     * 按设置拉起本模块在输入法进程里的两个本地服务：回环桥（18515）与壳监听（18516）。
     *
     * 放在 `Application.attach` 之后是**唯一**能早于宿主自身初始化的位置：设置页改动
     * 只影响下次进程启动，运行中不热重启服务 —— 客户端本来就要求重连，热切换只会把
     * 「正在跑的一轮识别」打断。
     *
     * 回环桥服务的是「系统识别服务」那一路：模块进程里的 `WeTypeRecognitionService`
     * 收下系统请求，把 PCM 推到本端口，由这里喂给宿主识别引擎。壳监听则把同一个引擎按
     * 千问 / 豆包协议转给 Eta —— 两者互相独立，开关也各管各的。
     */
    private fun startLocalServices(context: Context) {
        if (!WeTypeProcessIdentity.isImeProcess(context)) {
            Log.i("$TAG: not the IME process; local voice services left to the IME process")
            return
        }
        val settings = runCatching { WeTypeSettings.readSnapshot(context) }.getOrNull()
        if (settings == null) {
            Log.i("$TAG: settings snapshot unavailable; local voice services not started")
            return
        }
        ensureShellSyncReceiver(context)
        // 壳的开关与桥的开关独立，不能挂在下面那个 `if (!voiceBridgeEnabled) return` 之后。
        AsrShellHost.sync(
            context,
            settings.voiceShellEnabled,
            settings.voiceShellPort,
            settings.voiceShellAllowLan
        )
        if (!settings.voiceBridgeEnabled) {
            Log.i("$TAG: voice bridge disabled by settings; no local port opened")
            return
        }
        WeTypeVoiceBridge.startServer()
    }

    /**
     * 注册壳这条链上的广播接收器（每个输入法进程一次），只收 [ModuleBridgeContract.ACTION_SHELL_SYNC]。
     *
     * 发送方是别的 App（另一个 uid），所以必须 `RECEIVER_EXPORTED`；身份在 `onReceive`
     * 里按包名核对。注册一次就够，输入法进程活多久它活多久。
     */
    private fun ensureShellSyncReceiver(context: Context) {
        if (!shellSyncReceiverRegistered.compareAndSet(false, true)) return
        val appContext = context.applicationContext ?: context
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                when (intent.action) {
                    ModuleBridgeContract.ACTION_SHELL_SYNC ->
                        onShellSync(appContext, receiverContext, sentFromUid, intent)
                }
            }
        }
        val filter = IntentFilter(ModuleBridgeContract.ACTION_SHELL_SYNC)
        runCatching {
            ContextCompat.registerReceiver(
                appContext,
                receiver,
                filter,
                ContextCompat.RECEIVER_EXPORTED
            )
        }.onFailure {
            Log.e("$TAG: failed to register the shell sync receiver")
            Log.e(it)
            shellSyncReceiverRegistered.set(false)
        }
    }

    /** 设置页同步：按新配置起停监听。发送方要么是宿主自己（设置页在宿主进程里渲染），要么是模块 App。 */
    private fun onShellSync(
        appContext: Context,
        receiverContext: Context,
        senderUid: Int,
        intent: Intent
    ) {
        if (!isTrustedShellSender(receiverContext, senderUid)) {
            Log.i("$TAG: ignored a shell sync from an untrusted sender")
            return
        }
        val enabled = intent.getBooleanExtra(
            ModuleBridgeContract.EXTRA_ASR_SHELL_ENABLED, false
        )
        val port = intent.getIntExtra(
            ModuleBridgeContract.EXTRA_ASR_SHELL_PORT,
            WeTypeSettings.DEFAULT_VOICE_SHELL_PORT
        )
        val allowLan = intent.getBooleanExtra(
            ModuleBridgeContract.EXTRA_ASR_SHELL_ALLOW_LAN, false
        )
        AsrShellHost.sync(appContext, enabled, port, allowLan)
    }

    /**
     * 发送方包名里含宿主或模块 App 才放行。
     *
     * `sentFromUid` 是 API 34 才有的，低于 34 拿不到，直接放行（与
     * `ModuleBridgeReceiver.isTrustedSender` 同一取舍）。
     */
    private fun isTrustedShellSender(receiverContext: Context, senderUid: Int): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
        val senderPackages = receiverContext.packageManager.getPackagesForUid(senderUid)
        return senderPackages?.any {
            it == ModuleBridgeContract.HOST_PACKAGE_NAME ||
                it == ModuleBridgeContract.MODULE_PACKAGE_NAME
        } == true
    }

    /**
     * 拉起宿主语音会话。
     *
     * 走宿主自己的启动入口 `(boolean, <场景枚举>)void`。实测它在键盘不可见时**也能**
     * 跑到会话建立 + 开录，所以没必要自己拼链路 —— 让宿主按原本顺序建会话，我们只在
     * 录音器 init 时把那一个 `AudioRecord` 换掉。
     *
     * 节流：宿主在会话**已经起来**时会直接返回，重连时重复调它是无害的，
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
        if (appContextRef?.get() == null) {
            Log.i("$TAG: cannot start voice session: no application context yet")
            return
        }
        val singleton = singletonInstance ?: runCatching {
            WeTypeHostContracts.fieldOf(HostContractId.VOICE_SINGLETON_INSTANCE)?.get(null)
        }.getOrNull()?.also { singletonInstance = it } ?: return
        lastStartAt = now
        // 注意：**不要**在这里 resetStream()。缓冲是在客户端连上时清的，此刻队列里
        // 已经攒了本次会话的头几帧 —— 正是靠它们，宿主读线程第一口就吃到真音频，
        // 识别引擎才不会把这一轮判成静音。
        val invoked = runCatching { invokeStart(singleton) }
            .onFailure { Log.i("$TAG: start threw: ${it.javaClass.simpleName}: ${it.message}") }
            .getOrDefault(false)
        Log.i("$TAG: voice session start invoked=$invoked")
        // 客户端可能是在宿主**已经跑着一轮**的时候连上的：那种情况宿主内部的「已在会话中
        // 就返回」会让它不重建录音器，init 的 after 便不再触发。这里拿最近见过的录音器补换一次，
        // 换过了会在 [swapRecorder] 里直接返回。
        if (invoked) lastRecorderOwner?.get()?.let { swapRecorder(it) }
    }

    /**
     * 收尾：调宿主自己的「结束语音输入」入口，让它的状态机回到可再次启动的状态。
     *
     * 不能乱挑第一个 void 方法。曾经的写法按形状捞到一个只是离线/在线标志位 setter 的
     * 两参方法，宿主会话状态于是永远停在 `INPUT`；下一次启动会被开头的「已在会话中就返回」
     * 直接挡掉，录音器不会被换，表现为**同一个输入法进程里第一轮能用、第二轮一个转录都没有**。
     *
     * 宿主自己的收尾分两层，这里按形状认领（不写死混淆名）：
     * - 简单入口 `(<场景枚举>, boolean forceStop, boolean allowDelay)void`，内部按状态机
     *   分支，最稳；
     * - 本体 `(boolean, boolean, <场景枚举>, boolean, boolean)void`，作为兜底。
     */
    private fun stopVoiceSession() {
        val singleton = singletonInstance ?: return
        val clazz = singleton.javaClass

        val simpleEnd = clazz.declaredMethods.firstOrNull { m ->
            val t = m.parameterTypes
            m.returnType == Void.TYPE && t.size == 3 && t[0].isEnum &&
                t[1] == Boolean::class.javaPrimitiveType && t[2] == Boolean::class.javaPrimitiveType
        }
        val scene = simpleEnd?.parameterTypes?.get(0)?.enumConstants?.firstOrNull()
        if (simpleEnd != null && scene != null) {
            simpleEnd.isAccessible = true
            val ok = runCatching { simpleEnd.invoke(singleton, scene, true, false) }
                .onFailure { Log.i("$TAG: end voice threw: ${it.javaClass.simpleName}: ${it.message}") }
                .isSuccess
            Log.i("$TAG: voice session end invoked ${simpleEnd.name} ok=$ok")
            return
        }

        for (m in clazz.declaredMethods) {
            val t = m.parameterTypes
            if (m.returnType != Void.TYPE || t.size != 5) continue
            if (t[0] != Boolean::class.javaPrimitiveType || t[1] != Boolean::class.javaPrimitiveType) continue
            if (!t[2].isEnum) continue
            if (t[3] != Boolean::class.javaPrimitiveType || t[4] != Boolean::class.javaPrimitiveType) continue
            val scene2 = t[2].enumConstants?.firstOrNull() ?: continue
            m.isAccessible = true
            val ok = runCatching { m.invoke(singleton, true, false, scene2, false, false) }
                .onFailure { Log.i("$TAG: end voice threw: ${it.javaClass.simpleName}: ${it.message}") }
                .isSuccess
            Log.i("$TAG: voice session end invoked ${m.name} ok=$ok")
            return
        }
        Log.i("$TAG: voice session end entry not found")
    }

    /**
     * 启动入口：`(boolean, <场景枚举>)void`，场景枚举只影响埋点，不影响识别。
     *
     * 形状不足以定唯一 —— 3.5.4 / 4.0.0 上「按键松开触发发送」的收尾方法与它形状逐字相同，
     * 盲取第一个会把会话直接结束掉（日志表现为 `voice session start invoked=true` 之后
     * 一个 `mic replaced` 都没有）。认领逻辑放在 `voice.start` 契约里。
     */
    private fun invokeStart(singleton: Any): Boolean {
        val entry = WeTypeHostContracts.methodOf(HostContractId.VOICE_START) ?: run {
            Log.i("$TAG: voice session start entry not resolved")
            return false
        }
        val types = entry.parameterTypes
        val scene = types[1].enumConstants?.firstOrNull() ?: run {
            Log.i("$TAG: voice session start scene enum empty")
            return false
        }
        entry.isAccessible = true
        entry.invoke(singleton, false, scene)
        return true
    }
}

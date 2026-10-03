package com.xposed.wetypehook.wetype.voice

/**
 * 回环语音桥的线格式常量。**跨两个进程使用**：
 *
 * - 服务端在微信输入法进程内（[WeTypeVoiceBridge]）；
 * - 客户端在模块自己的进程内（[WeTypeVoiceClient]，由 [WeTypeRecognitionService] 驱动）。
 *
 * 两端都用 `DataInputStream` / `DataOutputStream` 读写，所以整数一律是 **Java 的大端**：
 * `writeInt` 写的是 `<< 24 | << 16 | << 8 |`，不是小端。外部工具（Python / nc）接进来时
 * 最容易在这里翻车 —— 字节序写反的话，桥会读到 `len=-2146697216` 这种离谱值并当场断开。
 *
 * ```
 * 客户端 → 服务端
 *   0x01 <u32 len> <pcm bytes>  推一段 PCM，16k / 单声道 / PCM16
 *   0x02                        本轮结束：停止喂音并收尾会话
 *
 * 服务端 → 客户端
 *   0x11 <u8 flag> <u32 len> <utf8 text>   转录（**累积整句**，不是增量）
 * ```
 *
 * 常量放在这里而不是各自文件里，是为了让两端共用同一份定义 —— 协议漂移只会在
 * 一个地方发生，且编译期就能发现。
 */
internal object WeTypeVoiceProtocol {

    /**
     * 默认监听端口。只绑回环，不对外网暴露。
     *
     * 两端都在用它：服务端在微信输入法进程里 [WeTypeVoiceBridge.startServer]，
     * 客户端在模块进程里 [WeTypeRecognitionService] 拉起的 [WeTypeVoiceClient]。
     */
    const val DEFAULT_PORT = 18515

    /** 单帧上限。客户端一次推 100ms 也才 3200 字节，64K 是防呆。 */
    const val MAX_FRAME_BYTES = 64 * 1024

    const val FRAME_PCM = 0x01
    const val FRAME_EOS = 0x02
    const val FRAME_TRANSCRIPT = 0x11

    /** 16k / 单声道 / PCM16 的字节速率。客户端按它决定每次推多少毫秒。 */
    const val BYTES_PER_SEC = 16000 * 2
}
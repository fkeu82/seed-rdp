package com.accessrdp.client.jni

import com.accessrdp.client.keymodel.KeyAction
import com.accessrdp.client.keymodel.KeyActionType

/**
 * FreeRDP 的 JNI 桥接层（Kotlin 侧定义）。
 *
 * 设计原则（按需求）：
 * - 这里只定义 Kotlin 与 C/C++ 之间的接口契约，**不实现 RDP 协议本身**；
 *   真正的协议处理在 FreeRDP 原生库（libfreerdp_client.so）里。
 * - 核心键盘接口就是题面要求的 [sendKeyDown] / [sendKeyUp]，参数为“RDP 扫描码”。
 *   扩展键的扫描码在高位带 0xE000（见 [com.accessrdp.client.keymodel.RdpKey.rdpCode]）。
 * - 音频重定向：[setAudioSinkEnabled] 开启后，原生层（rdpsnd/audin）解码出 PCM，
 *   通过静态回调 [onAudioData] 把数据交回 Kotlin，由 [AudioRedirect] 写入 Android AudioTrack 播放。
 *
 * 接入真实 FreeRDP 时，只需提供编译好的 `libfreerdp_client.so` 放到 apk 的 jniLibs 目录，
 * 并在 C 侧实现下方所有 `external` 函数即可，Kotlin 代码无需改动。
 */
object FreerdpJni {

    private const val LIB_NAME = "freerdp_client"
    private const val LIB_AUDIO = "rdpsnd_android"

    /** 原生库是否已成功加载。 */
    var isLibraryLoaded: Boolean = false
        private set

    /**
     * 尝试加载原生库。返回 false 表示未找到 .so（演示/未集成 FreeRDP 时属正常）。
     * 主桥接 [LIB_NAME] 负责连接/键盘；[LIB_AUDIO] 是 FreeRDP 的音频输出后端，
     * 须一并加载，否则 FreeRDP 在连接时无法找到它来接管“远端声音回传”。
     */
    fun load(): Boolean {
        if (isLibraryLoaded) return true
        return try {
            System.loadLibrary(LIB_NAME)
            try {
                System.loadLibrary(LIB_AUDIO)
            } catch (_: UnsatisfiedLinkError) {
                // 音频后端缺失时仍允许连接（只是没有声音），不阻断主流程。
            }
            isLibraryLoaded = true
            true
        } catch (e: UnsatisfiedLinkError) {
            isLibraryLoaded = false
            false
        }
    }

    // ---------------- 连接管理 ----------------

    /** 建立到远端 Windows 主机的 RDP 会话。返回 true 表示成功。 */
    external fun nativeConnect(
        host: String,
        port: Int,
        username: String,
        password: String,
        domain: String,
        enableAudio: Boolean,
        securityLevel: Int
    ): Boolean

    /** 断开会话。 */
    external fun nativeDisconnect(): Boolean

    // ---------------- 键盘：核心接口 ----------------

    /**
     * 发送“按键按下”。
     * @param rdpScanCode RDP 扫描码（扩展键高位带 0xE000）。与 FreeRDP 的
     *        freerdp_input_send_keyboard_event_ex(flags, code) 对应。
     */
    external fun sendKeyDown(rdpScanCode: Int)

    /**
     * 发送“按键抬起”。
     * @param rdpScanCode RDP 扫描码（扩展键高位带 0xE000）。
     */
    external fun sendKeyUp(rdpScanCode: Int)

    // 便捷封装：直接吃一条 [KeyAction]，无需在调用方判断按下/抬起。
    fun sendKeyAction(action: KeyAction) {
        when (action.type) {
            KeyActionType.DOWN -> sendKeyDown(action.rdpCode)
            KeyActionType.UP -> sendKeyUp(action.rdpCode)
        }
    }

    // ---------------- 鼠标（触屏映射用，可选） ----------------

    external fun sendMouseMove(x: Int, y: Int)
    external fun sendMouseButton(button: Int, down: Boolean)

    // ---------------- 音频重定向 ----------------

    /** 通知原生层是否把远端音频回传（true 开启后 [onAudioData] 才会被回调）。 */
    external fun setAudioSinkEnabled(enabled: Boolean)

    /**
     * 告诉 FreeRDP 去 APK 的原生库目录找 librdpsnd_android.so（音频后端）。
     * 必须在连接前调用；路径来自 Application 的 nativeLibraryDir。
     */
    external fun setNativeLibraryDir(dir: String)

    /**
     * 由原生层（FreeRDP 音频解码器）回调，把 PCM 数据交回 Kotlin 播放。
     * 必须是 `@JvmStatic` 才能被 C 侧通过 JNI 找到。
     * @param bitsPerSample PCM 位深（通常为 16，少数为 8）
     */
    @JvmStatic
    fun onAudioData(data: ByteArray?, sampleRate: Int, channels: Int, bitsPerSample: Int) {
        if (data == null) return
        AudioRedirect.write(data, sampleRate, channels, bitsPerSample)
    }

    /** 音频格式协商完成、即将开始播放（来自 FreeRDP 音频后端）。 */
    @JvmStatic
    fun onAudioStart() {
        AudioRedirect.onStart()
    }

    /** 音频通道关闭（来自 FreeRDP 音频后端）。 */
    @JvmStatic
    fun onAudioStop() {
        AudioRedirect.release()
    }
}

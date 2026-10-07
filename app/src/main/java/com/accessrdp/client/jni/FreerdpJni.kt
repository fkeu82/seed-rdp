package com.accessrdp.client.jni

import android.util.Log
import com.accessrdp.client.CrashLogger
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
 *
 * ⚠️ 防御性编程原则（避免真机闪退）：
 * - 任何 `System.loadLibrary` 失败都必须被吞掉并回退到演示模式，绝不让 App 崩溃；
 * - 任何 `external` 原生调用都必须包裹 try/catch（UnsatisfiedLinkError / Throwable），
 *   因为原生库加载成功 ≠ 每个符号都能解析，缺失时会抛 UnsatisfiedLinkError；
 * - 提供 [lastLoadError] 供 UI 用 TalkBack 播报失败原因。
 */
object FreerdpJni {

    private const val TAG = "AccessRDP/JNI"

    private const val LIB_NAME = "freerdp_client"
    private const val LIB_AUDIO = "rdpsnd_android"

    /** 原生库是否已成功加载。 */
    var isLibraryLoaded: Boolean = false
        private set

    /** 音频后端（librdpsnd_android.so）是否已成功加载。缺失时只是没有声音，不影响主流程。 */
    var isAudioBackendLoaded: Boolean = false
        private set

    /** 最近一次加载/调用失败的描述（供 UI 播报）。null 表示无错误。 */
    @Volatile
    var lastLoadError: String? = null
        private set

    /**
     * 尝试加载原生库。返回 false 表示未找到 .so 或加载失败（演示/未集成 FreeRDP 时属正常）。
     *
     * 安全气囊：任何 `UnsatisfiedLinkError`（甚至底层崩溃前的 `Throwable`）都被捕获，
     * 绝不向上抛出导致 App 闪退。主桥接 [LIB_NAME] 负责连接/键盘；[LIB_AUDIO] 是
     * FreeRDP 的音频输出后端，须一并加载，否则 FreeRDP 在连接时无法找到它来接管“远端声音回传”。
     */
    @Synchronized
    fun load(): Boolean {
        if (isLibraryLoaded) return true

        // ---- 1) 主桥接库：决定是否能用“真实连接”模式 ----
        try {
            System.loadLibrary(LIB_NAME)
            isLibraryLoaded = true
            lastLoadError = null
        } catch (e: UnsatisfiedLinkError) {
            // 最常见：APK 内缺少对应 ABI 的 .so，或某个依赖库符号无法解析。
            isLibraryLoaded = false
            isAudioBackendLoaded = false
            lastLoadError = "音频库加载失败（主桥接）：${e.message}"
            Log.e(TAG, "loadLibrary($LIB_NAME) 失败", e)
            return false
        } catch (e: Throwable) {
            // 兜底：LinkageError / SecurityException 等一律吞掉，避免启动即崩。
            isLibraryLoaded = false
            isAudioBackendLoaded = false
            lastLoadError = "音频库加载失败（主桥接）：${e.javaClass.simpleName}: ${e.message}"
            Log.e(TAG, "loadLibrary($LIB_NAME) 异常", e)
            return false
        }

        // ---- 2) 音频后端库：缺失只降级为“无声”，不阻断连接 ----
        try {
            System.loadLibrary(LIB_AUDIO)
            isAudioBackendLoaded = true
        } catch (e: UnsatisfiedLinkError) {
            isAudioBackendLoaded = false
            // 记下但不改变 isLibraryLoaded：仍可进入真实连接，只是没有远端声音。
            lastLoadError = "音频后端库加载失败（远端声音将不可用）：${e.message}"
            Log.w(TAG, "loadLibrary($LIB_AUDIO) 失败，已降级为无声模式", e)
        } catch (e: Throwable) {
            isAudioBackendLoaded = false
            lastLoadError = "音频后端库加载失败（远端声音将不可用）：${e.javaClass.simpleName}: ${e.message}"
            Log.w(TAG, "loadLibrary($LIB_AUDIO) 异常，已降级为无声模式", e)
        }

        return true
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

    /**
     * 取回最近一次连接失败的详细原因（由原生层通过 freerdp_get_last_error_string 生成）。
     *
     * 这是「不要只报一句模糊的连接失败」的实现：底层会返回诸如
     * 「TLS 连接失败」「认证失败」「DNS 解析失败」「连接被拒绝」等具体描述。
     * 无错误时返回空串。
     */
    external fun nativeGetLastError(): String

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
        // 防御：音频播放异常绝不能冒泡到原生调用栈（那里无法 catch），否则会直接崩 App。
        try {
            AudioRedirect.write(data, sampleRate, channels, bitsPerSample)
        } catch (e: Throwable) {
            Log.e(TAG, "onAudioData 播放失败", e)
        }
    }

    /** 音频格式协商完成、即将开始播放（来自 FreeRDP 音频后端）。 */
    @JvmStatic
    fun onAudioStart() {
        try {
            AudioRedirect.onStart()
        } catch (e: Throwable) {
            Log.e(TAG, "onAudioStart 失败", e)
        }
    }

    /** 音频通道关闭（来自 FreeRDP 音频后端）。 */
    @JvmStatic
    fun onAudioStop() {
        try {
            AudioRedirect.release()
        } catch (e: Throwable) {
            Log.e(TAG, "onAudioStop 失败", e)
        }
    }

    // ---------------- 原生日志转发（供用户直接看文件） ----------------

    /**
     * 【v1.1.4 新增】原生层（C/C++）的日志回调。
     *
     * 背景：C 层的 `__android_log_print` 只进 logcat，不进 CrashLogger 写的
     * `Download/AccessRDP/run-*.txt`。而多数用户**没有电脑、装不了 adb**，
     * 只能看那个文件。于是排查时看到的永远只有「连接失败」这种结果行，
     * 看不到「金丝雀」这种过程行 —— 等于读了跟没读一样。
     *
     * 本方法由 C 层的 `accessrdp_log()` 主动调用（JNI_OnLoad 缓存全局引用 +
     * CallStaticVoidMethod），把原生关键日志**同时**写进 CrashLogger 的日志文件，
     * 用户直接翻文件就能看到全过程，不需要 adb。
     *
     * @param tag     原生日志的分区标签（如 "JNI"）
     * @param message 日志正文
     */
    @JvmStatic
    fun onNativeLog(tag: String, message: String) {
        // 先打 logcat（不依赖 Context，永远可用）
        try {
            Log.i("AccessRDP/Native", "[$tag] $message")
        } catch (t: Throwable) {
            // 忽略
        }
        // 再落文件（需要 Application 实例；拿不到就只留 logcat）
        try {
            val ctx = com.accessrdp.client.AccessRdpApp.instance ?: return
            CrashLogger.log(ctx, "原生-$tag", message)
        } catch (t: Throwable) {
            // 写文件失败绝不能影响连接流程
        }
    }
}

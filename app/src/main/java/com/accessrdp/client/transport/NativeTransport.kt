package com.accessrdp.client.transport

import android.util.Log
import com.accessrdp.client.jni.FreerdpJni
import com.accessrdp.client.keymodel.KeyAction

/**
 * 真实传输层：通过 [FreerdpJni] 调用 FreeRDP 原生库。
 *
 * 使用前需先 [FreerdpJni.load] 成功（即 apk 内已包含 libfreerdp_client.so）。
 * 若原生库未集成，[isConnected] 会为 false，调用方应回退到 [MockTransport]。
 *
 * ⚠️ 防弹衣原则：所有跨 JNI 边界的调用都用 try/catch 包裹。原生库加载成功 ≠ 每个符号都能
 * 解析，且原生段可能因网络/协议异常抛错，任何异常都必须转成 [ConnectionResult.Failure]，
 * 绝不允许冒泡到调用方（否则在主线程就会闪退）。
 */
class NativeTransport : RdpTransport {

    private companion object {
        const val TAG = "AccessRDP/NativeTransport"
    }

    override val name: String = "FreeRDP 原生"

    @Volatile private var connected = false

    override val isConnected: Boolean
        get() = connected && FreerdpJni.isLibraryLoaded

    override fun connect(config: ConnectionConfig): ConnectionResult {
        // 1) 加载原生库（load 自带安全气囊，理论上不抛，但仍包一层）
        try {
            if (!FreerdpJni.isLibraryLoaded && !FreerdpJni.load()) {
                return ConnectionResult.Failure("未找到 FreeRDP 原生库 (libfreerdp_client.so)")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "加载原生库失败", t)
            return ConnectionResult.Failure("原生库加载失败：${t.message}")
        }

        // 2) 参数基本校验：主机为空不往下走，避免把非法值交给原生段
        if (config.host.isBlank()) {
            return ConnectionResult.Failure("主机地址为空")
        }
        val port = if (config.port in 1..65535) config.port else 3389

        // 3) 真正发起连接（防弹衣核心：这里包住所有可能的原生异常）
        return try {
            val ok = FreerdpJni.nativeConnect(
                config.host.trim(), port, config.username,
                config.password, config.domain, config.enableAudio, config.securityLevel
            )
            connected = ok
            if (ok) {
                // 音频开关设置失败不影响连接本身
                try {
                    FreerdpJni.setAudioSinkEnabled(config.enableAudio)
                } catch (t: Throwable) {
                    Log.w(TAG, "设置音频开关失败（不影响连接）", t)
                }
                ConnectionResult.Success
            } else {
                // 【关键】把底层真实原因带回去，而不是只说一句"连接失败"。
                // nativeGetLastError 对应 freerdp_get_last_error_string()，
                // 能区分 TLS 握手失败 / 认证失败 / DNS 失败 / 端口不通 等情况。
                val detail = try {
                    FreerdpJni.nativeGetLastError().takeIf { it.isNotBlank() }
                } catch (t: Throwable) {
                    null
                }
                Log.e(TAG, "nativeConnect 返回 false，底层原因：${detail ?: "未提供"}")
                ConnectionResult.Failure(detail ?: "底层未返回具体原因")
            }
        } catch (e: UnsatisfiedLinkError) {
            connected = false
            Log.e(TAG, "nativeConnect 符号缺失", e)
            ConnectionResult.Failure("原生库不完整：${e.message}")
        } catch (t: Throwable) {
            connected = false
            Log.e(TAG, "nativeConnect 抛出异常", t)
            ConnectionResult.Failure("连接异常：${t.javaClass.simpleName}: ${t.message}")
        }
    }

    override fun disconnect() {
        try {
            if (FreerdpJni.isLibraryLoaded) FreerdpJni.nativeDisconnect()
        } catch (t: Throwable) {
            Log.e(TAG, "断开连接异常", t)
        } finally {
            connected = false
        }
    }

    override fun send(action: KeyAction) {
        try {
            FreerdpJni.sendKeyAction(action)
        } catch (t: Throwable) {
            Log.e(TAG, "发送按键异常", t)
        }
    }

    override fun setAudioEnabled(enabled: Boolean) {
        try {
            FreerdpJni.setAudioSinkEnabled(enabled)
        } catch (t: Throwable) {
            Log.w(TAG, "设置音频开关异常", t)
        }
    }
}

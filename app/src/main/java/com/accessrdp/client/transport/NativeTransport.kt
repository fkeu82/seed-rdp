package com.accessrdp.client.transport

import com.accessrdp.client.jni.FreerdpJni
import com.accessrdp.client.keymodel.KeyAction

/**
 * 真实传输层：通过 [FreerdpJni] 调用 FreeRDP 原生库。
 *
 * 使用前需先 [FreerdpJni.load] 成功（即 apk 内已包含 libfreerdp_client.so）。
 * 若原生库未集成，[isConnected] 会为 false，调用方应回退到 [MockTransport]。
 */
class NativeTransport : RdpTransport {

    override val name: String = "FreeRDP 原生"

    @Volatile private var connected = false

    override val isConnected: Boolean
        get() = connected && FreerdpJni.isLibraryLoaded

    override fun connect(config: ConnectionConfig): ConnectionResult {
        if (!FreerdpJni.isLibraryLoaded && !FreerdpJni.load()) {
            return ConnectionResult.Failure("未找到 FreeRDP 原生库 (libfreerdp_client.so)")
        }
        val ok = FreerdpJni.nativeConnect(
            config.host, config.port, config.username,
            config.password, config.domain, config.enableAudio, config.securityLevel
        )
        connected = ok
        if (ok) FreerdpJni.setAudioSinkEnabled(config.enableAudio)
        return if (ok) ConnectionResult.Success
        else ConnectionResult.Failure("连接失败，请检查主机地址 / 账号 / 网络")
    }

    override fun disconnect() {
        if (FreerdpJni.isLibraryLoaded) FreerdpJni.nativeDisconnect()
        connected = false
    }

    override fun send(action: KeyAction) {
        FreerdpJni.sendKeyAction(action)
    }

    override fun setAudioEnabled(enabled: Boolean) {
        FreerdpJni.setAudioSinkEnabled(enabled)
    }
}

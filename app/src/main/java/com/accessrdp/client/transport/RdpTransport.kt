package com.accessrdp.client.transport

import com.accessrdp.client.keymodel.KeyAction

/**
 * 连接配置（填写远端 Windows 主机信息）。
 */
data class ConnectionConfig(
    val host: String,
    val port: Int = 3389,
    val username: String = "",
    val password: String = "",
    val domain: String = "",
    val enableAudio: Boolean = true,
    /** 安全层：0=自动, 1=RDP, 2=TLS, 3=NLA。公网建议 NLA(3)。 */
    val securityLevel: Int = 3
)

/** 连接结果。 */
sealed interface ConnectionResult {
    data object Success : ConnectionResult
    data class Failure(val reason: String) : ConnectionResult
}

/**
 * RDP 传输层抽象。UI / 粘滞键逻辑只依赖这个接口，不关心底层是原生 FreeRDP 还是演示用 Mock。
 *
 * 真实实现见 [NativeTransport]（桥接 FreeRDP 的 JNI）；
 * 演示实现见 [MockTransport]（不连真机，仅把按键数据回显，便于无障碍体验与调试）。
 */
interface RdpTransport {
    val name: String

    fun connect(config: ConnectionConfig): ConnectionResult
    fun disconnect()
    val isConnected: Boolean

    /** 发送单条按键动作（按下 / 抬起），参数已编码为 RDP 扫描码。 */
    fun send(action: KeyAction)

    /** 开启 / 关闭远程音频重定向（把 Windows 声音传回手机播放）。 */
    fun setAudioEnabled(enabled: Boolean)
}

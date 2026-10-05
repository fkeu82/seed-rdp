package com.accessrdp.client.transport

import com.accessrdp.client.keymodel.KeyAction

/**
 * 演示用传输层（不连接真机）。
 *
 * 用途：
 * 1. 在没有编译 FreeRDP 原生库时，让粘滞键键盘“跑通”——把每条按键动作回显出来，
 *    方便视障用户 / 开发者直观看到“按了什么键、发出了什么 RDP 扫描码数据”。
 * 2. 自动模拟连接成功，使整个 UI 流程可体验。
 *
 * 接入真实 FreeRDP 后，把 [com.accessrdp.client.RdpViewModel] 里的默认实现换成
 * [NativeTransport] 即可，UI 与粘滞键逻辑完全不用改。
 */
class MockTransport : RdpTransport {

    override val name: String = "演示模式（未连真机）"

    @Volatile override var isConnected: Boolean = false
        private set

    /** 记录最近一次发送的完整动作链，供 UI 展示“真实发出的数据”。 */
    val lastSent: MutableList<KeyAction> = mutableListOf()

    override fun connect(config: ConnectionConfig): ConnectionResult {
        // 演示模式：只要填了主机就视为“已连接”，便于体验键盘与音频开关。
        isConnected = config.host.isNotBlank()
        return ConnectionResult.Success
    }

    override fun disconnect() {
        isConnected = false
    }

    override fun send(action: KeyAction) {
        synchronized(lastSent) {
            lastSent.add(action)
            if (lastSent.size > 200) lastSent.removeAt(0)
        }
        // 真实环境这里会调用 FreerdpJni.sendKeyDown/Up；演示环境仅打印，便于抓日志。
        android.util.Log.d("MockTransport", "send ${action.type} ${action.key.label} code=0x${action.rdpCode.toString(16)}")
    }

    override fun setAudioEnabled(enabled: Boolean) {
        // 演示模式无真实音频流；开启后 UI 会提示“连接真实主机后可听到 Windows 声音”。
    }
}

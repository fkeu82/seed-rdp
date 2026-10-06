package com.accessrdp.client.transport

import com.accessrdp.client.keymodel.KeyAction

/**
 * 已废弃的演示传输层。
 *
 * ⚠️【重要历史教训 —— 此类的旧实现是重大缺陷的根源】
 *
 * 旧实现 `connect()` 里写着 `isConnected = config.host.isNotBlank()`，
 * 只要主机输入框非空就返回 [ConnectionResult.Success]。后果是：
 * 用户填入**根本不存在的 IP**（例如 `192.123456.895`）时，界面照样显示"已连接"、
 * 照样弹出键盘 —— 因为**从未调用过 freerdp_connect()**，纯粹在 UI 层改状态。
 * 这属于**假连接**，是对用户（尤其是依赖读屏的视障用户）的严重误导。
 *
 * 修复原则（v1.1.0 起）：
 *   1. 连接**必须**走 [NativeTransport] -> JNI `nativeConnect()` -> FreeRDP `freerdp_connect()`；
 *   2. 原生库不可用时**直接报错**，绝不"假装成功"；
 *   3. 本类仅保留 [send] 的回显能力（用于无原生库时的键盘数据调试），
 *      [connect] 永远返回失败，杜绝任何形式的假连接。
 *
 * @deprecated v1.1.0 起不再作为连接降级方案。连接失败必须如实上报。
 */
@Deprecated("假连接来源，v1.1.0 起 connect() 恒失败，仅保留按键回显用于调试")
class MockTransport : RdpTransport {

    override val name: String = "调试模式（不建立真实连接）"

    @Volatile override var isConnected: Boolean = false
        private set

    /** 记录最近一次发送的完整动作链，供调试查看。 */
    val lastSent: MutableList<KeyAction> = mutableListOf()

    /**
     * 【始终失败】调试模式不建立任何真实连接。
     *
     * 这里刻意返回失败而不是成功：RDP 客户端的存在意义就是连上真实主机，
     * 任何"没连上也说连上了"的行为都是欺骗用户，必须从源头禁止。
     */
    override fun connect(config: ConnectionConfig): ConnectionResult {
        isConnected = false
        return ConnectionResult.Failure(
            "调试模式不会建立真实连接。" +
                    "请确认 APK 内已包含 FreeRDP 原生库（libfreerdp_client.so），" +
                    "否则无法连接 ${config.host}:${config.port}。"
        )
    }

    override fun disconnect() {
        isConnected = false
    }

    override fun send(action: KeyAction) {
        synchronized(lastSent) {
            lastSent.add(action)
            if (lastSent.size > 200) lastSent.removeAt(0)
        }
        android.util.Log.d(
            "MockTransport",
            "send ${action.type} ${action.key.label} code=0x${action.rdpCode.toString(16)}"
        )
    }

    override fun setAudioEnabled(enabled: Boolean) {
        // 调试模式无真实音频流。
    }
}

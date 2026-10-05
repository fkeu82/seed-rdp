package com.accessrdp.client.keymodel

/**
 * RDP 键盘按键模型（纯 Kotlin，不依赖 Android）。
 *
 * RDP 协议通过“扫描码(scan code)”发送按键。FreeRDP 的
 * `freerdp_input_send_keyboard_event_ex(flags, code)` 接收的 `code` 即为扫描码；
 * 扩展键(Extended)需要额外带上 `KBD_FLAGS_EXTENDED (0x0100)` 标志。
 *
 * 为方便直接对接 JNI 的 [sendKeyDown]/[sendKeyUp]，这里把“扩展”标志单独保存，
 * 并通过 [rdpCode] 把扫描码与扩展位合并成一个 32 位整数（扩展键高位标记 0xE000）。
 *
 * @param label     读屏软件要朗读的名字，例如 "Shift"、"D"、"F4"
 * @param scanCode  低 8 位有效的键盘扫描码
 * @param extended  是否为扩展键（如 Win / Insert / 方向键 / 小键盘 Enter）
 * @param description 可选，更口语化的朗读描述
 */
data class RdpKey(
    val label: String,
    val scanCode: Int,
    val extended: Boolean = false,
    val description: String = label
) {
    /** 合并后的 RDP 扫描码：扩展键在高位标记 0xE000，可直接传给 JNI。 */
    val rdpCode: Int
        get() = if (extended) (scanCode or 0xE000) else scanCode
}

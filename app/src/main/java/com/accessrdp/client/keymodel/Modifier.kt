package com.accessrdp.client.keymodel

/**
 * 粘滞键支持的“修饰键”。每个修饰键对应一个 [RdpKey]，并带有读屏朗读用的中文/英文名称。
 *
 * 默认使用左键（Left Shift / Left Ctrl / Left Alt / Left Win），因为它们的扫描码最通用；
 * 若日后需要区分左右，可在此扩展为带 side 字段的数据类。
 */
enum class Modifier(
    val key: RdpKey,
    /** 读屏软件朗读用的状态描述，例如“已选中 Shift”。 */
    val spokenName: String
) {
    SHIFT(RdpKey("Shift", 0x2A), "Shift 上档键"),
    CTRL(RdpKey("Ctrl", 0x1D), "Ctrl 控制键"),
    ALT(RdpKey("Alt", 0x38), "Alt 换挡键"),
    WIN(RdpKey("Win", 0x5B, extended = true), "Win 开始键"),
    INSERT(INSERT_KEY, "Insert 插入键");

    /** 复选框未选中时的朗读文案。 */
    val unselectedName: String get() = "$spokenName，未选中"

    /** 复选框已选中时的朗读文案。 */
    val selectedName: String get() = "$spokenName，已选中"
}

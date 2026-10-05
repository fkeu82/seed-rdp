package com.accessrdp.client.keymodel

/**
 * 纯 Kotlin 的“粘滞键”状态机。不依赖 Android，可独立做单元测试。
 *
 * 无障碍设计目标：
 * 1. 修饰键(Shift/Ctrl/Win/Alt/Insert)以“复选框”形式逐个被选中，代替多指同时按压。
 * 2. 选中若干修饰键后，再按任意一个“基础键”(字母/数字/功能键/特殊键)，
 *    自动拼成一次完整组合键序列并交给上层发送给远端。
 * 3. 【关键】发送完成后立即清空所有修饰键选中态，防止“卡键”。
 *
 * 本类只负责“状态 + 生成发送计划([StickyCombo])”，真正的网络发送由上层
 * [com.accessrdp.client.transport.RdpTransport] 完成，从而保持本类纯粹、易测。
 */
class StickyKeyManager {

    /** 用 LinkedHashSet 记录选中顺序，保证朗读 / 展示顺序稳定。 */
    private val selected = LinkedHashSet<Modifier>()

    /** 选中状态变化时回调（Compose 用它同步复选框勾选态与朗读反馈）。 */
    var onSelectionChanged: ((Set<Modifier>) -> Unit)? = null

    fun isSelected(m: Modifier): Boolean = m in selected

    fun getSelected(): Set<Modifier> = selected.toSet()

    /**
     * 切换某个修饰键的选中状态。
     * @return 切换后的选中态（true = 已选中）。
     */
    fun toggle(m: Modifier): Boolean {
        if (selected.contains(m)) selected.remove(m) else selected.add(m)
        onSelectionChanged?.invoke(getSelected())
        return isSelected(m)
    }

    fun select(m: Modifier) {
        if (selected.add(m)) onSelectionChanged?.invoke(getSelected())
    }

    fun deselect(m: Modifier) {
        if (selected.remove(m)) onSelectionChanged?.invoke(getSelected())
    }

    /** 清空全部修饰键（发射组合键后调用，防卡键）。 */
    fun clearAll() {
        if (selected.isNotEmpty()) {
            selected.clear()
            onSelectionChanged?.invoke(getSelected())
        }
    }

    /**
     * 构建并“发射”一次组合键。
     *
     * 发送序列（严格顺序，保证远端收到的是一次完整按键）：
     *   1) 依次按下全部已选修饰键（按下顺序 = 选中顺序）
     *   2) 按下基础键
     *   3) 抬起基础键
     *   4) 反向依次抬起全部修饰键
     *   5) 清空修饰键选中态（防卡键）
     *
     * @param base 基础键（字母 / 数字 / 功能键 / 特殊键，非修饰键）
     * @return 本次组合的描述与逐条动作，供上层真正发送 + 展示给用户。
     */
    fun fire(base: RdpKey): StickyCombo {
        val modifiers = selected.toList() // 保持选中顺序
        val actions = buildList {
            modifiers.forEach { add(KeyAction(KeyActionType.DOWN, it.key)) }
            add(KeyAction(KeyActionType.DOWN, base))
            add(KeyAction(KeyActionType.UP, base))
            modifiers.asReversed().forEach { add(KeyAction(KeyActionType.UP, it.key)) }
        }
        val description = buildDescription(modifiers, base)
        clearAll() // 关键：发射后立刻复位，防止卡键
        return StickyCombo(modifiers, base, actions, description)
    }

    private fun buildDescription(modifiers: List<Modifier>, base: RdpKey): String {
        val parts = modifiers.map { it.key.label } + base.label
        return parts.joinToString(" + ")
    }
}

/** 单条按键动作。 */
enum class KeyActionType { DOWN, UP }

data class KeyAction(val type: KeyActionType, val key: RdpKey) {
    /** 合并后的 RDP 扫描码，可直接传给 JNI 的 sendKeyDown / sendKeyUp。 */
    val rdpCode: Int get() = key.rdpCode

    val spokenText: String
        get() = when (type) {
            KeyActionType.DOWN -> "按下 ${key.label}"
            KeyActionType.UP -> "抬起 ${key.label}"
        }
}

/**
 * 一次组合键的完整描述（供 UI 朗读与日志展示）。
 *
 * @param modifiers 本次参与的修饰键
 * @param base      基础键
 * @param actions   按发送顺序排列的逐条动作
 * @param description 形如 "Ctrl + Alt + Delete" 的口语化组合名
 */
data class StickyCombo(
    val modifiers: List<Modifier>,
    val base: RdpKey,
    val actions: List<KeyAction>,
    val description: String
)

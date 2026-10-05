package com.accessrdp.client.keymodel

/**
 * 按键码表：把人类可读的字母 / 数字 / 功能键映射到 RDP 扫描码。
 *
 * 扫描码参考 Microsoft RDP 键盘事件规范（set 1 风格，扩展键带 E0 前缀）。
 * 这些常量既服务于“粘滞键”模型，也会在 JNI 层被真正发送给远端 FreeRDP。
 */
/**
 * Insert 既是修饰键也是可被朗读的键；单独定义供码表与控制键复用。
 * 注意：作为“修饰键”使用时由 [Modifier.INSERT] 引用，作为“基础键”使用时用本常量。
 */
val INSERT_KEY = RdpKey("Insert", 0x52, extended = true)

object KeyTables {

    /** 字母 A-Z 的扫描码。 */
    private val LETTER_CODES = mapOf(
        'A' to 0x1E, 'B' to 0x30, 'C' to 0x2E, 'D' to 0x20, 'E' to 0x12,
        'F' to 0x21, 'G' to 0x22, 'H' to 0x23, 'I' to 0x17, 'J' to 0x24,
        'K' to 0x25, 'L' to 0x26, 'M' to 0x32, 'N' to 0x31, 'O' to 0x18,
        'P' to 0x19, 'Q' to 0x10, 'R' to 0x13, 'S' to 0x1F, 'T' to 0x14,
        'U' to 0x16, 'V' to 0x2F, 'W' to 0x11, 'X' to 0x2D, 'Y' to 0x15, 'Z' to 0x2C
    )

    /** 数字 0-9 的扫描码。 */
    private val DIGIT_CODES = mapOf(
        '0' to 0x0B, '1' to 0x02, '2' to 0x03, '3' to 0x04, '4' to 0x05,
        '5' to 0x06, '6' to 0x07, '7' to 0x08, '8' to 0x09, '9' to 0x0A
    )

    fun letter(c: Char): RdpKey? {
        val up = c.uppercaseChar()
        return LETTER_CODES[up]?.let { RdpKey(up.toString(), it) }
    }

    fun digit(c: Char): RdpKey? {
        return DIGIT_CODES[c]?.let { RdpKey(c.toString(), it) }
    }

    // ---- 常用功能键 ----
    val F1 = RdpKey("F1", 0x3B)
    val F2 = RdpKey("F2", 0x3C)
    val F3 = RdpKey("F3", 0x3D)
    val F4 = RdpKey("F4", 0x3E)
    val F5 = RdpKey("F5", 0x3F)
    val F6 = RdpKey("F6", 0x40)
    val F7 = RdpKey("F7", 0x41)
    val F8 = RdpKey("F8", 0x42)
    val F9 = RdpKey("F9", 0x43)
    val F10 = RdpKey("F10", 0x44)
    val F11 = RdpKey("F11", 0x57)
    val F12 = RdpKey("F12", 0x58)

    val FUNCTION_KEYS: List<RdpKey> = listOf(F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12)

    // ---- 常用控制 / 编辑键 ----
    val ESC = RdpKey("Esc", 0x01)
    val TAB = RdpKey("Tab", 0x0F)
    val ENTER = RdpKey("Enter", 0x1C)
    val BACKSPACE = RdpKey("Backspace", 0x0E)
    val SPACE = RdpKey("Space", 0x39)
    val CAPS = RdpKey("CapsLock", 0x3A)
    val DELETE = RdpKey("Delete", 0x53, extended = true)
    val HOME = RdpKey("Home", 0x47, extended = true)
    val END = RdpKey("End", 0x4F, extended = true)
    val PAGE_UP = RdpKey("PageUp", 0x49, extended = true)
    val PAGE_DOWN = RdpKey("PageDown", 0x51, extended = true)
    val ARROW_UP = RdpKey("ArrowUp", 0x48, extended = true)
    val ARROW_DOWN = RdpKey("ArrowDown", 0x50, extended = true)
    val ARROW_LEFT = RdpKey("ArrowLeft", 0x4B, extended = true)
    val ARROW_RIGHT = RdpKey("ArrowRight", 0x4D, extended = true)
    val APPS = RdpKey("Menu", 0x5D, extended = true)

    val CONTROL_KEYS: List<RdpKey> = listOf(
        ESC, TAB, CAPS, ENTER, BACKSPACE, SPACE,
        INSERT_KEY, DELETE, HOME, END, PAGE_UP, PAGE_DOWN,
        ARROW_UP, ARROW_DOWN, ARROW_LEFT, ARROW_RIGHT, APPS
    )

    /** 供 UI 展示的一行“数字键”。 */
    val DIGIT_KEYS: List<RdpKey> = ('0'..'9').mapNotNull { digit(it) }

    /** 供 UI 展示的三行 QWERTY 字母键。 */
    val QWERTY_ROWS: List<List<RdpKey>> = listOf(
        listOf('Q', 'W', 'E', 'R', 'T', 'Y', 'U', 'I', 'O', 'P').mapNotNull { letter(it) },
        listOf('A', 'S', 'D', 'F', 'G', 'H', 'J', 'K', 'L').mapNotNull { letter(it) },
        listOf('Z', 'X', 'C', 'V', 'B', 'N', 'M').mapNotNull { letter(it) }
    )
}

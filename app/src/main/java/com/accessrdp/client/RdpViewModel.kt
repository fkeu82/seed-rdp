package com.accessrdp.client

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.accessrdp.client.keymodel.KeyAction
import com.accessrdp.client.keymodel.KeyTables
import com.accessrdp.client.keymodel.Modifier
import com.accessrdp.client.keymodel.RdpKey
import com.accessrdp.client.keymodel.StickyCombo
import com.accessrdp.client.keymodel.StickyKeyManager
import com.accessrdp.client.jni.AudioRedirect
import com.accessrdp.client.jni.FreerdpJni
import com.accessrdp.client.transport.ConnectionConfig
import com.accessrdp.client.transport.ConnectionResult
import com.accessrdp.client.transport.MockTransport
import com.accessrdp.client.transport.NativeTransport
import com.accessrdp.client.transport.RdpTransport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers

/** 一条已发送组合键的归档记录（用于“数据面板”回显）。 */
data class SentEntry(
    val description: String,
    val actions: List<KeyAction>,
    val timeMillis: Long
)

enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED }

/**
 * 把“粘滞键逻辑 / 传输层 / 连接状态 / 读屏播报”聚合到一处，供 Compose 屏幕消费。
 *
 * 设计要点：
 * - [manager] 是纯 Kotlin 状态机，本 ViewModel 只负责把 [StickyCombo.actions] 逐条交给 [transport]。
 * - 默认使用 [MockTransport]，这样没有 FreeRDP 原生库也能“跑通”整套键盘与数据回显；
 *   把 [useRealNative] 打开且 [FreerdpJni.load] 成功时，自动切换到 [NativeTransport]。
 * - 每次发射组合键后，除了重置修饰键（防卡键），还会更新 [announcement] 让 TalkBack 朗读。
 */
class RdpViewModel : ViewModel() {

    private val manager = StickyKeyManager()

    private val _useRealNative = MutableStateFlow(false)
    val useRealNative: StateFlow<Boolean> = _useRealNative.asStateFlow()

    /**
     * 传输层：检测到 FreeRDP 原生库（libfreerdp_client.so）时自动使用 [NativeTransport] 进入真实连接；
     * 否则回退到 [MockTransport] 演示模式。UI 与粘滞键逻辑无需任何改动。
     */
    private val _transport: RdpTransport
    val transport: RdpTransport get() = _transport

    private val _selected = MutableStateFlow<Set<Modifier>>(emptySet())
    val selected: StateFlow<Set<Modifier>> = _selected.asStateFlow()

    private val _connection = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connection: StateFlow<ConnectionState> = _connection.asStateFlow()

    private val _log = MutableStateFlow<List<SentEntry>>(emptyList())
    val log: StateFlow<List<SentEntry>> = _log.asStateFlow()

    private val _announcement = MutableStateFlow<String?>(null)
    val announcement: StateFlow<String?> = _announcement.asStateFlow()

    init {
        // 启动即尝试加载原生库（load() 自带安全气囊，绝不抛异常）。
        // 成功 → 真实连接模式；失败 → 退回演示模式（MockTransport）并播报原因，绝不闪退。
        val nativeLoaded = try {
            FreerdpJni.load()
        } catch (t: Throwable) {
            false
        }
        _useRealNative.value = nativeLoaded
        _transport = if (nativeLoaded) NativeTransport() else MockTransport()
        manager.onSelectionChanged = { _selected.value = it.toSet() }

        _announcement.value = when {
            !nativeLoaded -> {
                // 明确告知视障用户当前处于降级模式，以及可查看日志的位置。
                val detail = FreerdpJni.lastLoadError
                if (detail.isNullOrBlank()) {
                    "音频库加载失败，已进入演示模式：可完整体验键盘与数据回显"
                } else {
                    "音频库加载失败，已进入演示模式。原因：$detail"
                }
            }
            !FreerdpJni.isAudioBackendLoaded -> {
                "已连接 FreeRDP 原生库，但音频后端未加载，远端声音可能不可用：${FreerdpJni.lastLoadError ?: ""}"
            }
            else -> "已加载 FreeRDP 原生库，连接后将进入真实远程桌面"
        }
    }

    // ---------------- 粘滞键操作 ----------------

    fun toggleModifier(m: Modifier) {
        manager.toggle(m)
    }

    fun clearModifiers() {
        manager.clearAll()
    }

    /**
     * 直接发射一组预设组合键（如 Ctrl+Alt+Del）。会先选中指定修饰键再发射，发射后自动复位。
     * 用于“快捷组合”按钮，避免用户手动逐个勾选。
     */
    fun firePreset(modifiers: List<Modifier>, base: RdpKey): StickyCombo {
        modifiers.forEach { manager.select(it) }
        return fire(base)
    }

    /**
     * 发射一次组合键：把基础键与已选修饰键组合，逐条发送给传输层，然后自动复位修饰键。
     * @return 本次组合（含描述与逐条动作），便于 UI 即时展示。
     */
    fun fire(base: RdpKey): StickyCombo {
        val combo = manager.fire(base)
        combo.actions.forEach { transport.send(it) }
        val entry = SentEntry(combo.description, combo.actions, System.currentTimeMillis())
        _log.value = (listOf(entry) + _log.value).take(50)
        _announcement.value = "已发送 ${combo.description}"
        return combo
    }

    // ---------------- 连接操作 ----------------

    fun connect(config: ConnectionConfig) {
        viewModelScope.launch(Dispatchers.IO) {
            _connection.value = ConnectionState.CONNECTING
            val result = transport.connect(config)
            _connection.value = if (result is ConnectionResult.Success) {
                ConnectionState.CONNECTED
            } else {
                ConnectionState.DISCONNECTED
            }
            when (result) {
                is ConnectionResult.Success -> _announcement.value = "已连接到 ${config.host}"
                is ConnectionResult.Failure -> _announcement.value = "连接失败：${result.reason}"
            }
        }
    }

    fun disconnect() {
        transport.disconnect()
        _connection.value = ConnectionState.DISCONNECTED
        AudioRedirect.release()
        _announcement.value = "已断开连接"
    }

    // ---------------- 设置 ----------------

    fun setUseRealNative(enabled: Boolean) {
        _useRealNative.value = enabled
        // 真实环境可在此按需重建 transport；当前默认演示模式，便于体验。
    }

    fun getKeyTables() = KeyTables

    override fun onCleared() {
        super.onCleared()
        transport.disconnect()
        AudioRedirect.release()
    }
}

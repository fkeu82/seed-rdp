package com.accessrdp.client

import android.util.Log
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
 * ⚠️【v1.1.0 核心修复 —— 消灭假连接】
 *
 * 此前实现有两处致命缺陷，导致"填入虚构 IP 也显示已连接"：
 *   1. 原生库加载失败时，**静默降级到 MockTransport**，而 MockTransport.connect()
 *      恒返回 Success —— 于是永远"连接成功"，键盘永远弹出；
 *   2. 从头到尾**没有调用过 freerdp_connect()**。
 *
 * 现在的铁律：
 *   - 传输层**只有一个**：[NativeTransport]，无论原生库是否可用。绝不降级到假实现；
 *   - 连接**必须**经 JNI 调 `freerdp_connect()`，返回 FALSE 即判失败；
 *   - 失败时：状态回滚为 [ConnectionState.DISCONNECTED]、用 TalkBack 播报**底层真实原因**、
 *     **绝不**切换键盘区（UI 仅在 CONNECTED 时渲染键盘，从结构上杜绝"没连上却弹键盘"）。
 */
class RdpViewModel : ViewModel() {

    private companion object {
        const val TAG = "AccessRDP/ViewModel"
    }

    private val manager = StickyKeyManager()

    /** 原生库是否可用。false 时连接必定失败，且如实告知用户。 */
    private val _nativeAvailable = MutableStateFlow(false)
    val nativeAvailable: StateFlow<Boolean> = _nativeAvailable.asStateFlow()

    /**
     * 【唯一】传输层：永远是 [NativeTransport]。
     * 不再有 MockTransport 降级路径 —— 那是"假连接"的根源。
     */
    private val _transport: RdpTransport = NativeTransport()
    val transport: RdpTransport get() = _transport

    private val _selected = MutableStateFlow<Set<Modifier>>(emptySet())
    val selected: StateFlow<Set<Modifier>> = _selected.asStateFlow()

    private val _connection = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connection: StateFlow<ConnectionState> = _connection.asStateFlow()

    private val _log = MutableStateFlow<List<SentEntry>>(emptyList())
    val log: StateFlow<List<SentEntry>> = _log.asStateFlow()

    private val _announcement = MutableStateFlow<String?>(null)
    val announcement: StateFlow<String?> = _announcement.asStateFlow()

    /** 最近一次连接失败的原因（供 UI 常驻展示，读屏可随时回看）。 */
    private val _lastFailure = MutableStateFlow<String?>(null)
    val lastFailure: StateFlow<String?> = _lastFailure.asStateFlow()

    init {
        // 启动即尝试加载原生库（load() 自带安全气囊，绝不抛异常）。
        // 注意：加载失败**不再**降级为演示模式，而是明确标记不可用，连接时如实报错。
        val nativeLoaded = try {
            FreerdpJni.load()
        } catch (t: Throwable) {
            Log.e(TAG, "原生库加载抛异常", t)
            false
        }
        _nativeAvailable.value = nativeLoaded
        manager.onSelectionChanged = { _selected.value = it.toSet() }

        _announcement.value = when {
            !nativeLoaded -> {
                val detail = FreerdpJni.lastLoadError
                if (detail.isNullOrBlank()) {
                    "原生库未加载，当前无法连接远程主机"
                } else {
                    "原生库未加载，当前无法连接远程主机。原因：$detail"
                }
            }
            !FreerdpJni.isAudioBackendLoaded ->
                "已加载 FreeRDP 原生库，但音频后端未加载，远端声音可能不可用"
            else -> null
        }

        Log.i(
            TAG,
            "初始化完成：nativeLoaded=$nativeLoaded, " +
                    "audioBackend=${FreerdpJni.isAudioBackendLoaded}, " +
                    "version=${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE})"
        )
    }

    /**
     * 【版本号自检】防止"已安装更高版本 xxx"这类覆盖安装被拒的问题。
     *
     * 背景：曾有历史构建把 versionCode 写成异常偏高的大数（或与当前体系不一致），
     * 导致后续正常版本号（如 10006）反而被系统判定为"更低版本"而拒绝安装。
     *
     * 这里在启动时读取**本机已安装包的 versionCode**，与当前包对比：
     *   - 若已装 > 当前（说明设备上残留了更高 code 的旧包），给出明确提示，
     *     引导用户"卸载后重装"，避免装机失败却一头雾水。
     * 返回提示文本；一切正常时返回 null。
     */
    fun selfCheckInstalledVersion(context: android.content.Context): String? {
        return try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            @Suppress("DEPRECATION")
            val installedCode = info.versionCode
            val currentCode = BuildConfig.VERSION_CODE
            if (installedCode > currentCode) {
                "检测到本机已安装版本（版本代码 $installedCode）高于当前包（$currentCode）。" +
                        "这通常是历史测试包残留所致。请先卸载本应用，再重新安装当前版本。"
            } else {
                null
            }
        } catch (t: Throwable) {
            Log.w(TAG, "版本自检失败（忽略）", t)
            null
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
     */
    fun firePreset(modifiers: List<Modifier>, base: RdpKey): StickyCombo {
        modifiers.forEach { manager.select(it) }
        return fire(base)
    }

    /**
     * 发射一次组合键。**仅在已连接时才会真正发送**，未连接时如实提示。
     */
    fun fire(base: RdpKey): StickyCombo {
        val combo = manager.fire(base)
        if (_connection.value != ConnectionState.CONNECTED) {
            // 未连接时不允许"假发送"——这同样是欺骗。
            _announcement.value = "尚未连接，无法发送按键"
            Log.w(TAG, "未连接状态下尝试发送 ${combo.description}，已拒绝")
            return combo
        }
        combo.actions.forEach { transport.send(it) }
        val entry = SentEntry(combo.description, combo.actions, System.currentTimeMillis())
        _log.value = (listOf(entry) + _log.value).take(50)
        _announcement.value = "已发送 ${combo.description}"
        return combo
    }

    // ---------------- 连接操作 ----------------

    /**
     * 建立连接。全程走真实原生通道，绝不做任何"假成功"。
     *
     * 状态机严格遵循：
     *   DISCONNECTED -> CONNECTING -> (成功) CONNECTED
     *                              -> (失败) DISCONNECTED + 播报原因
     *
     * 失败路径**不会**、也不允许切到键盘界面（键盘仅在 CONNECTED 渲染）。
     */
    fun connect(config: ConnectionConfig) {
        viewModelScope.launch(Dispatchers.IO) {
            _connection.value = ConnectionState.CONNECTING
            _lastFailure.value = null

            Log.i(
                TAG,
                "开始连接：host=${config.host} port=${config.port} " +
                        "user=${config.username} domain=${config.domain} " +
                        "securityLevel=${config.securityLevel} audio=${config.enableAudio}"
            )

            // 「防弹衣」：底层可能因网络/协议/原生断言失败，全部兜住，绝不闪退。
            val result: ConnectionResult = try {
                transport.connect(config)
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "连接时原生符号缺失", e)
                ConnectionResult.Failure("原生库不完整：${e.message}")
            } catch (e: Throwable) {
                Log.e(TAG, "连接发生异常", e)
                ConnectionResult.Failure(e.javaClass.simpleName + ": " + e.message)
            }

            when (result) {
                is ConnectionResult.Success -> {
                    // 双保险：只有原生层确实报告"已连接"才置为 CONNECTED。
                    if (!transport.isConnected) {
                        Log.e(TAG, "结果矛盾：Success 但 transport.isConnected=false，按失败处理")
                        _connection.value = ConnectionState.DISCONNECTED
                        _lastFailure.value = "底层状态异常：连接未真正建立"
                        _announcement.value = "连接失败。底层状态异常，连接未真正建立。"
                        return@launch
                    }
                    _connection.value = ConnectionState.CONNECTED
                    Log.i(TAG, "连接成功：${config.host}:${config.port}")
                    _announcement.value = "已连接 ${config.host} 端口 ${config.port}"
                }

                is ConnectionResult.Failure -> {
                    // 【关键】失败即回滚为未连接，绝不保留任何"半连接"状态。
                    _connection.value = ConnectionState.DISCONNECTED
                    val reason = result.reason.ifBlank { "底层未提供原因" }
                    _lastFailure.value = reason
                    Log.w(TAG, "连接失败：${config.host}:${config.port} -> $reason")
                    // 播报真实原因（例如"端口拒绝连接""连接超时""认证失败"），
                    // 不再使用笼统的"请检查主机和端口"。
                    _announcement.value = "连接失败。$reason"
                    try {
                        AccessRdpApp.instance?.let { ctx ->
                            CrashLogger.log(
                                ctx, "连接失败",
                                "目标=${config.host}:${config.port} 原因=$reason"
                            )
                        }
                    } catch (t: Throwable) {
                        Log.e(TAG, "写连接失败日志失败", t)
                    }
                }
            }
        }
    }

    fun disconnect() {
        try {
            transport.disconnect()
        } catch (t: Throwable) {
            Log.e(TAG, "断开异常", t)
        }
        _connection.value = ConnectionState.DISCONNECTED
        AudioRedirect.release()
        _announcement.value = "已断开连接"
    }

    fun getKeyTables() = KeyTables

    override fun onCleared() {
        super.onCleared()
        try {
            transport.disconnect()
        } catch (_: Throwable) {
        }
        AudioRedirect.release()
    }
}

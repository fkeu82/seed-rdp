package com.accessrdp.client.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.accessrdp.client.ConnectionState
import com.accessrdp.client.RdpViewModel
import com.accessrdp.client.SentEntry
import com.accessrdp.client.keymodel.KeyActionType
import com.accessrdp.client.keymodel.KeyTables
import com.accessrdp.client.keymodel.Modifier as StickyModifier
import com.accessrdp.client.keymodel.RdpKey
import com.accessrdp.client.transport.ConnectionConfig

/**
 * 无障碍 RDP 客户端主界面。
 *
 * 无障碍设计概览（优先级第一）：
 * - 修饰键全部做成“复选框”，支持双击（TalkBack 的“激活”手势即双击）或长按来选中，
 *   选中后由 [semantics] 的 stateDescription 播报“已选中/未选中”，绝不出现未加标签控件。
 * - 顶部状态区是一个“实时播报区”，组合键发送后 TalkBack 会朗读“已发送 XX”。
 * - 焦点顺序通过 [FocusRequester] + [focusProperties] 显式串联每一“行”，
 *   TalkBack 滑动时严格按【从上到下、从左到右】移动，绝不会飞出键盘可聚焦区域。
 * - 每个基础键用 [Modifier.clearAndSetSemantics] 只暴露“字母 D”这类纯内容描述，
 *   不再附带“按钮”后缀，朗读更干净。
 * - 仅当连接成功后，底部复选框键盘才出现；未连接 / 连接中显示“请先连接”提示。
 */
@Composable
fun AccessRdpScreen(viewModel: RdpViewModel = viewModel()) {
    val selected by viewModel.selected.collectAsState()
    val connection by viewModel.connection.collectAsState()
    val log by viewModel.log.collectAsState()
    val announcement by viewModel.announcement.collectAsState()

    // 让 TalkBack 朗读最新状态/结果
    Announcer(announcement)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp)
    ) {
        ScreenTitle()
        Spacer(Modifier.height(8.dp))

        ConnectionCard(viewModel, connection)
        Spacer(Modifier.height(12.dp))

        // 实时播报区：朗读最近一次操作结果
        StatusRegion(selected, connection, announcement)
        Spacer(Modifier.height(12.dp))

        // 键盘区：连接成功后才显示；否则提示先连接
        KeyboardArea(viewModel, connection)
        Spacer(Modifier.height(12.dp))

        SentDataPanel(log)
        Spacer(Modifier.height(24.dp))
    }
}

// ----------------------------------------------------------------------------
// 顶部标题
// ----------------------------------------------------------------------------
@Composable
private fun ScreenTitle() {
    Text(
        text = "无障碍远程桌面键盘",
        fontSize = 22.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.semantics { heading() }
    )
    Text(
        text = "为使用 TalkBack 的视障用户设计：用复选框勾选修饰键，再按任意键即可组合发送。",
        fontSize = 13.sp,
        modifier = Modifier.semantics {
            contentDescription = "使用说明：用复选框勾选修饰键，再按任意键即可组合发送"
        }
    )
}

// ----------------------------------------------------------------------------
// 连接卡片
// ----------------------------------------------------------------------------
@Composable
private fun ConnectionCard(viewModel: RdpViewModel, connection: ConnectionState) {
    var host by remember { mutableStateOf("192.168.1.100") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var audio by remember { mutableStateOf(true) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("连接设置", fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(6.dp))

            OutlinedTextField(
                value = host, onValueChange = { host = it },
                label = { Text("远程 Windows 主机地址") },
                maxLines = 1,
                modifier = Modifier.fillMaxWidth()
                    .semantics { contentDescription = "远程主机地址输入框" }
            )
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = user, onValueChange = { user = it },
                label = { Text("用户名") }, maxLines = 1,
                modifier = Modifier.fillMaxWidth()
                    .semantics { contentDescription = "用户名输入框" }
            )
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = pass, onValueChange = { pass = it },
                label = { Text("密码") }, maxLines = 1,
                modifier = Modifier.fillMaxWidth()
                    .semantics { contentDescription = "密码输入框" }
            )
            Spacer(Modifier.height(6.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("远程音频重定向（把 Windows 声音传回手机）", fontSize = 13.sp)
                Spacer(Modifier.width(8.dp))
                Switch(checked = audio, onCheckedChange = {
                    audio = it
                    viewModel.transport.setAudioEnabled(it)
                })
            }

            Spacer(Modifier.height(8.dp))
            Row {
                Button(onClick = {
                    viewModel.connect(
                        ConnectionConfig(
                            host = host, username = user, password = pass,
                            enableAudio = audio
                        )
                    )
                }) {
                    Text("连接")
                }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { viewModel.disconnect() }) {
                    Text("断开")
                }
                Spacer(Modifier.width(8.dp))
                val stateText = when (connection) {
                    ConnectionState.DISCONNECTED -> "未连接"
                    ConnectionState.CONNECTING -> "连接中"
                    ConnectionState.CONNECTED -> "已连接"
                }
                Text(
                    stateText,
                    modifier = Modifier.semantics {
                        contentDescription = "连接状态：$stateText"
                    }.padding(top = 10.dp)
                )
            }
            Text(
                "提示：检测到 FreeRDP 原生库时，点“连接”即为真实连接；否则进入演示模式，可完整体验键盘与数据回显。",
                fontSize = 12.sp
            )
        }
    }
}

// ----------------------------------------------------------------------------
// 实时播报区（TalkBack 会朗读这里的文字）
// ----------------------------------------------------------------------------
@Composable
private fun StatusRegion(
    selected: Set<StickyModifier>,
    connection: ConnectionState,
    announcement: String?
) {
    val selText = if (selected.isEmpty()) "无" else selected.joinToString("、") { it.key.label }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                "当前已勾选的修饰键：$selText", fontSize = 14.sp,
                modifier = Modifier.semantics { contentDescription = "当前已勾选的修饰键：$selText" }
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = announcement ?: (if (connection == ConnectionState.CONNECTED) "已连接，可使用键盘" else "等待操作"),
                fontSize = 15.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.semantics {
                    contentDescription = announcement
                        ?: (if (connection == ConnectionState.CONNECTED) "已连接，可使用键盘" else "等待操作")
                }
            )
        }
    }
}

// ----------------------------------------------------------------------------
// 键盘区：连接成功才显示，否则提示先连接
// ----------------------------------------------------------------------------
@Composable
private fun KeyboardArea(viewModel: RdpViewModel, connection: ConnectionState) {
    if (connection != ConnectionState.CONNECTED) {
        NotConnectedHint()
        return
    }

    // 用 FocusRequester 把各“行”串成稳定的上下遍历链，避免滑动时焦点飞出键盘区。
    val rModifier = remember { FocusRequester() }
    val rPreset = remember { FocusRequester() }
    val rLetter = remember { Array(KeyTables.QWERTY_ROWS.size) { FocusRequester() } }
    val rDigit = remember { FocusRequester() }
    val rFkey = remember { FocusRequester() }
    val rControl = remember { FocusRequester() }
    val lastLetter = rLetter.lastIndex

    Column {
        SectionHeader("修饰键（复选框）：双击或长按勾选，再按任意键即组合发送")
        Spacer(Modifier.height(6.dp))
        ModifierToggleRow(
            requester = rModifier, upTarget = rModifier, downTarget = rPreset,
            selected = viewModel.selected.value, onToggle = viewModel::toggleModifier
        )
        Spacer(Modifier.height(6.dp))
        OutlinedButton(onClick = { viewModel.clearModifiers() }) {
            Text("清除所有修饰键")
        }
        Spacer(Modifier.height(12.dp))

        SectionHeader("快捷组合（一键发送）")
        Spacer(Modifier.height(6.dp))
        PresetRow(requester = rPreset, upTarget = rModifier, downTarget = rLetter[0], viewModel = viewModel)
        Spacer(Modifier.height(12.dp))

        SectionHeader("字母键")
        Spacer(Modifier.height(6.dp))
        KeyTables.QWERTY_ROWS.forEachIndexed { i, row ->
            KeyRow(
                keys = row, onFire = viewModel::fire, requester = rLetter[i],
                upTarget = if (i == 0) rPreset else rLetter[i - 1],
                downTarget = if (i == lastLetter) rDigit else rLetter[i + 1]
            )
            Spacer(Modifier.height(6.dp))
        }

        SectionHeader("数字键")
        Spacer(Modifier.height(6.dp))
        KeyRow(
            keys = KeyTables.DIGIT_KEYS, onFire = viewModel::fire, requester = rDigit,
            upTarget = rLetter[lastLetter], downTarget = rFkey
        )
        Spacer(Modifier.height(12.dp))

        SectionHeader("功能键 F1 - F12")
        Spacer(Modifier.height(6.dp))
        KeyRow(
            keys = KeyTables.FUNCTION_KEYS, onFire = viewModel::fire, requester = rFkey,
            upTarget = rDigit, downTarget = rControl
        )
        Spacer(Modifier.height(12.dp))

        SectionHeader("控制键（回车 / 退格 / 方向 / 删除 等）")
        Spacer(Modifier.height(6.dp))
        KeyRow(
            keys = KeyTables.CONTROL_KEYS, onFire = viewModel::fire, requester = rControl,
            upTarget = rFkey, downTarget = rControl
        )
        Spacer(Modifier.height(12.dp))
    }
}

/**
 * 未连接时显示的提示，带完整语义标签，方便读屏软件朗读。
 */
@Composable
private fun NotConnectedHint() {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("键盘已隐藏", fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(6.dp))
            Text(
                "尚未连接到远程 Windows 主机。请先在上方填写主机地址并点击“连接”，连接成功后这里会显示完整的无障碍复选框键盘。",
                fontSize = 14.sp,
                modifier = Modifier.semantics {
                    contentDescription = "尚未连接远程主机，键盘已隐藏，请先连接后再使用复选框键盘"
                }
            )
        }
    }
}

// ----------------------------------------------------------------------------
// 修饰键复选框行
// ----------------------------------------------------------------------------
@Composable
private fun ModifierToggleRow(
    requester: FocusRequester,
    upTarget: FocusRequester,
    downTarget: FocusRequester,
    selected: Set<StickyModifier>,
    onToggle: (StickyModifier) -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val values = StickyModifier.values()
        values.forEachIndexed { index, m ->
            StickyModifierToggle(
                modifier = m,
                checked = selected.contains(m),
                onToggle = { onToggle(m) },
                focusModifier = Modifier
                    .focusRequester(requester)
                    .then(if (index == 0) Modifier.focusProperties { up = upTarget } else Modifier)
                    .then(if (index == values.lastIndex) Modifier.focusProperties { down = downTarget } else Modifier)
            )
        }
    }
}

/**
 * 单个修饰键“复选框”。
 *
 * - 通过 [combinedClickable] 的 onClick 支持 TalkBack 的“双击激活”，onLongClick 支持“长按”。
 * - [semantics] 设置 role=Checkbox、contentDescription（朗读名称）、stateDescription（已选中/未选中），
 *   TalkBack 选中后会播报“已选中”。这里保留“复选框”角色，让视障用户明确知道它是可勾选的。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StickyModifierToggle(
    modifier: StickyModifier,
    checked: Boolean,
    onToggle: () -> Unit,
    focusModifier: Modifier = Modifier
) {
    val containerColor = if (checked) MaterialTheme.colorScheme.primaryContainer
    else MaterialTheme.colorScheme.surfaceVariant

    Column(
        modifier = focusModifier
            .clip(RoundedCornerShape(10.dp))
            .background(containerColor)
            .combinedClickable(
                role = Role.Checkbox,
                onClick = onToggle,
                onLongClick = onToggle
            )
            .semantics {
                contentDescription = modifier.spokenName
                stateDescription = if (checked) "已选中" else "未选中"
            }
            .padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 自定义勾选指示（避免内置 Checkbox 自带语义与外层冲突）
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(
                    if (checked) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outline
                ),
            contentAlignment = Alignment.Center
        ) {
            if (checked) Text("✓", color = MaterialTheme.colorScheme.onPrimary, fontSize = 18.sp)
        }
        Spacer(Modifier.height(4.dp))
        Text(modifier.key.label, fontWeight = FontWeight.Bold)
    }
}

// ----------------------------------------------------------------------------
// 快捷组合行
// ----------------------------------------------------------------------------
@Composable
private fun PresetRow(
    requester: FocusRequester,
    upTarget: FocusRequester,
    downTarget: FocusRequester,
    viewModel: RdpViewModel
) {
    val presets = listOf(
        Triple("Ctrl+Alt+Del", listOf(StickyModifier.CTRL, StickyModifier.ALT), KeyTables.DELETE),
        Triple("Win+D 显示桌面", listOf(StickyModifier.WIN), KeyTables.letter('D')!!),
        Triple("Alt+Tab 切换窗口", listOf(StickyModifier.ALT), KeyTables.TAB),
        Triple("Win+R 运行", listOf(StickyModifier.WIN), KeyTables.letter('R')!!),
        Triple("Ctrl+Shift+Esc 任务管理器", listOf(StickyModifier.CTRL, StickyModifier.SHIFT), KeyTables.ESC)
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        presets.forEachIndexed { index, (label, mods, base) ->
            Box(
                modifier = Modifier
                    .focusRequester(requester)
                    .then(if (index == 0) Modifier.focusProperties { up = upTarget } else Modifier)
                    .then(if (index == presets.lastIndex) Modifier.focusProperties { down = downTarget } else Modifier)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { viewModel.firePreset(mods, base) }
                    .semantics { contentDescription = "快捷组合：$label" }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(label, fontSize = 14.sp)
            }
        }
    }
}

// ----------------------------------------------------------------------------
// 基础键行（字母 / 数字 / 功能键 / 控制键 通用）
// ----------------------------------------------------------------------------
@Composable
private fun KeyRow(
    keys: List<RdpKey>,
    onFire: (RdpKey) -> Unit,
    requester: FocusRequester,
    upTarget: FocusRequester,
    downTarget: FocusRequester
) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        keys.forEachIndexed { index, key ->
            KeyButton(
                key = key,
                onKey = onFire,
                focusModifier = Modifier
                    .focusRequester(requester)
                    .then(if (index == 0) Modifier.focusProperties { up = upTarget } else Modifier)
                    .then(if (index == keys.lastIndex) Modifier.focusProperties { down = downTarget } else Modifier)
            )
        }
    }
}

/**
 * 单个基础键按钮。
 *
 * 关键无障碍改进：使用 [Modifier.clearAndSetSemantics] 只设置内容描述（例如“字母 D”），
 * 清掉 Material 按钮自带的“按钮”角色后缀，TalkBack 读出来就是干净的“字母 D”，不再啰嗦。
 * 同时重新挂上 [androidx.compose.ui.semantics.SemanticsPropertyKey] 的 onClick 动作，
 * 保证读屏用户“双击激活”依然可用。焦点链由调用方通过 [focusModifier] 传入。
 */
@Composable
private fun KeyButton(key: RdpKey, onKey: (RdpKey) -> Unit, focusModifier: Modifier = Modifier) {
    val spoken = when {
        key.label.length == 1 && key.label[0].isLetter() -> "字母 ${key.label}"
        key.label.all { it.isDigit() } -> "数字 ${key.label}"
        key.label.startsWith("F") && key.label.drop(1).all { it.isDigit() } -> "功能键 ${key.label}"
        else -> "按键 ${key.label}"
    }
    // 用基础 Box + 基金会 clickable 而非 Material Button：
    // 基金会 clickable 不会强加 Role.Button（“按钮”角色），读屏只朗读 contentDescription（如“字母 D”），
    // 同时保留“双击激活”能力；semantics 以合并方式补充内容描述，不清除 clickable 自带的点击动作。
    Box(
        modifier = focusModifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { onKey(key) }
            .semantics { contentDescription = spoken }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(key.label, fontSize = 15.sp)
    }
}

// ----------------------------------------------------------------------------
// 发送数据面板：直观展示“发了什么键、什么 RDP 扫描码”
// ----------------------------------------------------------------------------
@Composable
private fun SentDataPanel(log: List<SentEntry>) {
    SectionHeader("发送数据面板（直观查看发出的 RDP 扫描码）")
    Spacer(Modifier.height(6.dp))
    if (log.isEmpty()) {
        Text(
            "还没有发送过按键。先连接主机，再勾选修饰键并按下任意键试试。",
            fontSize = 13.sp,
            modifier = Modifier.semantics { contentDescription = "还没有发送过按键" }
        )
        return
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            log.take(8).forEach { entry ->
                Text("已发送：${entry.description}", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                val detail = entry.actions.joinToString("  ") {
                    val verb = if (it.type == KeyActionType.DOWN) "按下" else "抬起"
                    "$verb ${it.key.label}(0x${it.rdpCode.toString(16).uppercase()})"
                }
                Text(detail, fontSize = 12.sp)
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
            }
        }
    }
}

// ----------------------------------------------------------------------------
// 通用小组件
// ----------------------------------------------------------------------------
@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.semantics { heading() }
    )
}

/**
 * 把最新 [announcement] 交给 TalkBack 朗读。
 * 通过 Android View 的 announceForAccessibility 实现强制播报（即使焦点不在该控件上）。
 */
@Composable
private fun Announcer(announcement: String?) {
    val view = LocalView.current
    LaunchedEffect(announcement) {
        announcement?.let { view.announceForAccessibility(it) }
    }
}

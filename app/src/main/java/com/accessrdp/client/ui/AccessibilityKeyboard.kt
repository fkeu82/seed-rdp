package com.accessrdp.client.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.accessrdp.client.ConnectionState
import com.accessrdp.client.RdpViewModel
import com.accessrdp.client.keymodel.KeyTables
import com.accessrdp.client.keymodel.Modifier as StickyModifier
import com.accessrdp.client.keymodel.RdpKey
import com.accessrdp.client.transport.ConnectionConfig

/**
 * 无障碍 RDP 客户端主界面（v1.0.3 极简版）。
 *
 * 设计目标：**最纯粹的连接界面**。未连接时屏幕上只有连接表单；
 * 连接成功后才追加键盘区。绝不在未连接时用多余文字/控件干扰读屏焦点。
 *
 * - 未连接：[ConnectionForm]（主机 / 端口 / 用户名 / 密码 / 连接）
 * - 连接中：按钮变为“连接中…”，屏蔽重复点击
 * - 已连接：[ConnectionForm] + 键盘区 + 断开按钮
 */
@Composable
fun AccessRdpScreen(viewModel: RdpViewModel = viewModel()) {
    val selected by viewModel.selected.collectAsState()
    val connection by viewModel.connection.collectAsState()
    val announcement by viewModel.announcement.collectAsState()

    // 让 TalkBack 朗读最新状态/结果（含“连接失败，请检查主机和端口”）
    Announcer(announcement)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp)
    ) {
        // 1) 连接表单：始终显示，是唯一的主界面
        ConnectionForm(viewModel, connection)

        // 2) 键盘区：仅在连接成功后出现。
        //    未连接时不渲染任何键盘/提示，避免读屏焦点被无意义控件占据。
        if (connection == ConnectionState.CONNECTED) {
            Spacer(Modifier.height(16.dp))
            KeyboardArea(viewModel)
        }
    }
}

// ----------------------------------------------------------------------------
// 连接表单（主机 / 端口 / 用户名 / 密码 / 连接）
// ----------------------------------------------------------------------------
@Composable
private fun ConnectionForm(viewModel: RdpViewModel, connection: ConnectionState) {
    // 默认主机留空，由用户填写；端口默认 3389。
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("3389") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxWidth()) {

        // ---- 第一行：主机 + 端口（并排，端口默认 3389）----
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = host,
                onValueChange = { host = it },
                label = { Text("主机") },
                maxLines = 1,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier
                    .weight(1f)
                    .semantics { contentDescription = "远程主机地址输入框" }
            )
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(
                value = port,
                onValueChange = { input ->
                    // 只允许数字，最多 5 位，避免非法端口导致底层解析异常。
                    port = input.filter { it.isDigit() }.take(5)
                },
                label = { Text("端口") },
                maxLines = 1,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier
                    .width(110.dp)
                    .semantics { contentDescription = "端口输入框，默认 3389" }
            )
        }
        Spacer(Modifier.height(8.dp))

        // ---- 用户名 ----
        OutlinedTextField(
            value = user,
            onValueChange = { user = it },
            label = { Text("用户名") },
            maxLines = 1,
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "用户名输入框" }
        )
        Spacer(Modifier.height(8.dp))

        // ---- 密码 ----
        OutlinedTextField(
            value = pass,
            onValueChange = { pass = it },
            label = { Text("密码") },
            maxLines = 1,
            singleLine = true,
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "密码输入框" }
        )
        Spacer(Modifier.height(12.dp))

        // ---- 按钮区：未连接=连接；连接中=连接中…；已连接=断开 ----
        when (connection) {
            ConnectionState.DISCONNECTED -> {
                Button(
                    onClick = {
                        val portValue = port.toIntOrNull() ?: 3389
                        viewModel.connect(
                            ConnectionConfig(
                                host = host.trim(),
                                port = portValue,
                                username = user,
                                password = pass,
                                enableAudio = true
                            )
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "连接按钮" }
                ) {
                    Text("连接", fontSize = 16.sp)
                }
            }

            ConnectionState.CONNECTING -> {
                Button(
                    onClick = { /* 连接中忽略重复点击 */ },
                    enabled = false,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "正在连接，请稍候" }
                ) {
                    Text("连接中…", fontSize = 16.sp)
                }
            }

            ConnectionState.CONNECTED -> {
                OutlinedButton(
                    onClick = { viewModel.disconnect() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "断开按钮" }
                ) {
                    Text("断开", fontSize = 16.sp)
                }
            }
        }
    }
}

// ----------------------------------------------------------------------------
// 键盘区：仅连接成功后渲染
// ----------------------------------------------------------------------------
@Composable
private fun KeyboardArea(viewModel: RdpViewModel) {
    val selected by viewModel.selected.collectAsState()

    // 用 FocusRequester 把各“行”串成稳定的上下遍历链，避免滑动时焦点飞出键盘区。
    val rModifier = remember { FocusRequester() }
    val rPreset = remember { FocusRequester() }
    val rLetter = remember { Array(KeyTables.QWERTY_ROWS.size) { FocusRequester() } }
    val rDigit = remember { FocusRequester() }
    val rFkey = remember { FocusRequester() }
    val rControl = remember { FocusRequester() }
    val lastLetter = rLetter.lastIndex

    Column {
        Text(
            text = "键盘（已连接）",
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.semantics { heading() }
        )
        Spacer(Modifier.height(8.dp))

        // ---- 修饰键复选框 ----
        ModifierToggleRow(
            requester = rModifier, upTarget = rModifier, downTarget = rPreset,
            selected = selected, onToggle = viewModel::toggleModifier
        )
        Spacer(Modifier.height(6.dp))
        OutlinedButton(onClick = { viewModel.clearModifiers() }) {
            Text("清除修饰键")
        }
        Spacer(Modifier.height(12.dp))

        // ---- 快捷组合 ----
        PresetRow(
            requester = rPreset, upTarget = rModifier, downTarget = rLetter[0],
            viewModel = viewModel
        )
        Spacer(Modifier.height(12.dp))

        // ---- 字母键 ----
        KeyTables.QWERTY_ROWS.forEachIndexed { i, row ->
            KeyRow(
                keys = row, onFire = viewModel::fire, requester = rLetter[i],
                upTarget = if (i == 0) rPreset else rLetter[i - 1],
                downTarget = if (i == lastLetter) rDigit else rLetter[i + 1]
            )
            Spacer(Modifier.height(6.dp))
        }

        // ---- 数字键 ----
        KeyRow(
            keys = KeyTables.DIGIT_KEYS, onFire = viewModel::fire, requester = rDigit,
            upTarget = rLetter[lastLetter], downTarget = rFkey
        )
        Spacer(Modifier.height(6.dp))

        // ---- 功能键 ----
        KeyRow(
            keys = KeyTables.FUNCTION_KEYS, onFire = viewModel::fire, requester = rFkey,
            upTarget = rDigit, downTarget = rControl
        )
        Spacer(Modifier.height(6.dp))

        // ---- 控制键 ----
        KeyRow(
            keys = KeyTables.CONTROL_KEYS, onFire = viewModel::fire, requester = rControl,
            upTarget = rFkey, downTarget = rControl
        )
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
 * - [semantics] 设置 role=Checkbox、contentDescription（朗读名称）、stateDescription（已选中/未选中）。
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
        Triple("Win+D", listOf(StickyModifier.WIN), KeyTables.letter('D')!!),
        Triple("Alt+Tab", listOf(StickyModifier.ALT), KeyTables.TAB),
        Triple("Win+R", listOf(StickyModifier.WIN), KeyTables.letter('R')!!),
        Triple("Ctrl+Shift+Esc", listOf(StickyModifier.CTRL, StickyModifier.SHIFT), KeyTables.ESC)
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
// 基础键行
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
 * 单个基础键按钮：用基础 Box + 基金会 clickable，读屏只朗读 contentDescription（如“字母 D”），
 * 不附加“按钮”角色后缀，同时保留双击激活能力。
 */
@Composable
private fun KeyButton(key: RdpKey, onKey: (RdpKey) -> Unit, focusModifier: Modifier = Modifier) {
    val spoken = when {
        key.label.length == 1 && key.label[0].isLetter() -> "字母 ${key.label}"
        key.label.all { it.isDigit() } -> "数字 ${key.label}"
        key.label.startsWith("F") && key.label.drop(1).all { it.isDigit() } -> "功能键 ${key.label}"
        else -> "按键 ${key.label}"
    }
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

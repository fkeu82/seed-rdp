package com.accessrdp.client.ui

import com.accessrdp.client.BuildConfig

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
import androidx.compose.ui.semantics.clearAndSetSemantics
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
    val connection by viewModel.connection.collectAsState()
    val announcement by viewModel.announcement.collectAsState()

    // 让 TalkBack 朗读最新状态/结果（连接成功 / 连接失败的真实原因）
    Announcer(announcement)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp)
    ) {
        // 1) 连接表单：始终显示，是唯一的主界面
        ConnectionForm(viewModel, connection)

        // 2) 键盘区：**仅在真实连接成功后**出现。
        //    这是从 UI 结构上杜绝"假连接却弹键盘"——只要 connection 不是 CONNECTED，
        //    键盘组件根本不会进入组合树，不存在任何"假弹键盘"的可能。
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

    val lastFailure by viewModel.lastFailure.collectAsState()

    Column(modifier = Modifier.fillMaxWidth()) {

        // ---- 版本号（读屏可见，用来核对"装的到底是哪一版"）----
        // 背景：此前 Release 写着 v1.0.4，装出来却显示 1.0.3，
        // 有了这行，用户开屏即可确认实际运行版本，便于排查「版本号漂移」。
        Text(
            text = "版本 ${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    contentDescription =
                        "当前应用版本 ${BuildConfig.VERSION_NAME}，版本代码 ${BuildConfig.VERSION_CODE}"
                }
        )
        Spacer(Modifier.height(8.dp))

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

        // ---- 【连接失败原因常驻展示区】----
        // 只在失败后显示，且**原样呈现底层真实原因**（如「端口拒绝连接」「连接超时」
        // 「认证失败」），读屏可反复聚焦回看。这是"如实告知用户"的落点。
        lastFailure?.takeIf { it.isNotBlank() }?.let { reason ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .semantics {
                        contentDescription = "连接失败原因：$reason"
                    }
                    .padding(12.dp)
            ) {
                Text(
                    text = "连接失败：$reason",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
            Spacer(Modifier.height(12.dp))
        }

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
        SectionLabel("修饰键")
        ModifierToggleRow(
            requester = rModifier, upTarget = rModifier, downTarget = rPreset,
            selected = selected, onToggle = viewModel::toggleModifier
        )
        Spacer(Modifier.height(6.dp))
        OutlinedButton(
            onClick = { viewModel.clearModifiers() },
            modifier = Modifier.semantics { contentDescription = "清除已选修饰键" }
        ) {
            Text("清除修饰键")
        }
        Spacer(Modifier.height(12.dp))

        // ---- 快捷组合 ----
        SectionLabel("快捷组合键")
        PresetRow(
            requester = rPreset, upTarget = rModifier, downTarget = rLetter[0],
            viewModel = viewModel
        )
        Spacer(Modifier.height(12.dp))

        // ---- 字母键 ----
        SectionLabel("字母键")
        KeyTables.QWERTY_ROWS.forEachIndexed { i, row ->
            KeyRow(
                keys = row, onFire = viewModel::fire, requester = rLetter[i],
                upTarget = if (i == 0) rPreset else rLetter[i - 1],
                downTarget = if (i == lastLetter) rDigit else rLetter[i + 1]
            )
            Spacer(Modifier.height(6.dp))
        }

        // ---- 数字键 ----
        SectionLabel("数字键")
        KeyRow(
            keys = KeyTables.DIGIT_KEYS, onFire = viewModel::fire, requester = rDigit,
            upTarget = rLetter[lastLetter], downTarget = rFkey
        )
        Spacer(Modifier.height(6.dp))

        // ---- 功能键 ----
        SectionLabel("功能键")
        KeyRow(
            keys = KeyTables.FUNCTION_KEYS, onFire = viewModel::fire, requester = rFkey,
            upTarget = rDigit, downTarget = rControl
        )
        Spacer(Modifier.height(6.dp))

        // ---- 控制键 ----
        SectionLabel("控制键")
        KeyRow(
            keys = KeyTables.CONTROL_KEYS, onFire = viewModel::fire, requester = rControl,
            upTarget = rFkey, downTarget = rControl
        )
    }
}

/** 分区小标题：标记为 heading，方便读屏按标题快速跳转。 */
@Composable
private fun SectionLabel(text: String) {
    Spacer(Modifier.height(4.dp))
    Text(
        text = text,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.semantics { heading() }
    )
    Spacer(Modifier.height(4.dp))
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
                onClick = onToggle,
                onLongClick = onToggle
            )
            .clearAndSetSemantics {
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
                    .clearAndSetSemantics {
                        contentDescription = "快捷组合 $label"
                    }
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
 * 单个基础键按钮 —— 无障碍核心修复点。
 *
 * 【为什么不用 Button / 不用 Role.Button】
 * Material 的 Button 自带 `Role.Button` 语义，TalkBack 会在朗读内容后自动追加
 * “按钮”二字，于是字母 D 被读成“D，按钮”。对视障用户来说，
 * 每个键后面都挂个“按钮”是纯噪音，**只应读出字母本身**。
 *
 * 【方案】Box + clickable + clearAndSetSemantics
 *   - `clickable`：保留点击与 TalkBack“双击激活”能力；
 *     刻意**不指定 role**，避免任何“按钮”后缀。
 *   - `clearAndSetSemantics`：**先清空**子节点自动汇聚上来的所有语义
 *     （Text 文本、"按钮"角色等），**再只设置**我们给出的 contentDescription，
 *     确保朗读内容唯一且可控。
 *   - 每个键都带有明确、唯一的 contentDescription（逐个补全，无一遗漏）。
 */
@Composable
private fun KeyButton(key: RdpKey, onKey: (RdpKey) -> Unit, focusModifier: Modifier = Modifier) {
    val spoken = spokenDescription(key)
    Box(
        modifier = focusModifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { onKey(key) }
            .clearAndSetSemantics { contentDescription = spoken }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(key.label, fontSize = 15.sp)
    }
}

/**
 * 为每个键生成读屏文本。
 *
 * 规则（确保“读不出的字母”不存在）：
 *   - 单字母         -> “字母 A”
 *   - 全数字         -> “数字 5”
 *   - F1~F12         -> “功能键 F1”
 *   - 多字符控制键   -> “按键 Esc”/“按键 Tab”/“按键 Ctrl”…
 *   - 兜底           -> “按键 <标签>”（保证任何键都有非空描述）
 *
 * 注意：不返回空串。若未来新增键位忘了加规则，兜底分支也能保证有语音输出，
 * 不会出现“读不出的键”。
 */
private fun spokenDescription(key: RdpKey): String {
    val label = key.label
    return when {
        label.isEmpty() -> "未知按键"
        label.length == 1 && label[0].isLetter() -> "字母 $label"
        label.all { it.isDigit() } -> "数字 $label"
        label.length >= 2 && label[0].uppercaseChar() == 'F' && label.drop(1).all { it.isDigit() } ->
            "功能键 $label"
        else -> "按键 $label"
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

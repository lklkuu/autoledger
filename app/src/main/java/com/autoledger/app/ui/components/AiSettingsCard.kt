package com.autoledger.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.autoledger.app.di.AppContainer
import com.autoledger.app.ui.theme.LedgerPalette
import com.autoledger.core.model.AiMode
import kotlinx.coroutines.launch

/**
 * 设置页的「AI 判定」卡片。
 *
 * 产品语义（用户拍板）：**AI 是可选增强**——
 * - 关闭（默认）⇒ 全本地处理，通知原文不出设备；
 * - 开启 ⇒ 才有模式选择（兜底 / 全覆盖）、接口地址、密钥、模型名。
 *
 * 三条隐私硬约束在这里落地：
 * 1. 关闭时下方控件**禁用灰化但不清空已填内容**（用户下次开启不必重打）；
 * 2. 密钥输入框是密码框，且**任何地方都不回显**（含「测试连通性」结果）；
 * 3. 未填密钥时给警示条但**不阻断保存** —— 那是用户的知情选择，不是错误。
 */
@Composable
fun AiSettingsCard(container: AppContainer, enabled: Boolean) {
    val settings by container.settings.state.collectAsState()
    val scope = rememberCoroutineScope()

    var endpoint by rememberSaveable { mutableStateOf("") }
    var model by rememberSaveable { mutableStateOf("") }
    var apiKey by rememberSaveable { mutableStateOf("") }
    var probeResult by rememberSaveable { mutableStateOf("") }
    var probing by remember { mutableStateOf(false) }
    var addressError by remember { mutableStateOf("") }

    // 首次进入时用已保存的值填一次；用户之后在输入框里的编辑不被覆盖。
    var seeded by remember { mutableStateOf(false) }
    if (!seeded) {
        endpoint = settings.aiEndpoint
        model = settings.aiModel
        seeded = true
    }

    val keyPresent = container.aiApiKeyPresent()

    AppCard {
        SectionTitle("AI 判定", "默认关闭；关闭时通知原文不出设备")

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("启用 AI 判定", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "开启后，判不出的收支类型会问一次你配置的接口。关闭则全部本地处理。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = { on ->
                    // 开启时地址为空 ⇒ 即时提示但不阻断：先允许保存，用户可在卡片里继续补。
                    addressError = if (on && endpoint.isBlank()) "接口地址不能为空" else ""
                    container.settings.setAi(
                        enabled = on,
                        mode = settings.aiMode,
                        endpoint = endpoint.ifBlank { settings.aiEndpoint },
                        model = model.ifBlank { settings.aiModel },
                    )
                },
            )
        }

        if (enabled && !keyPresent) {
            Text(
                "未填密钥，AI 判定将始终回退到本地规则。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        // ---- 以下内容在关闭时禁用灰化，但**保留已填内容** ----
        Column(
            Modifier.padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("判定模式", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = settings.aiMode == AiMode.FALLBACK,
                    enabled = enabled,
                    onClick = {
                        container.settings.setAi(true, AiMode.FALLBACK, endpoint, model)
                    },
                    label = { Text("兜底（默认）") },
                )
                FilterChip(
                    selected = settings.aiMode == AiMode.ALWAYS,
                    enabled = enabled,
                    onClick = {
                        container.settings.setAi(true, AiMode.ALWAYS, endpoint, model)
                    },
                    label = { Text("全覆盖") },
                )
            }
            Text(
                if (settings.aiMode == AiMode.FALLBACK) {
                    "仅本地判不出（金额缺失）时才问 AI，日常通知不上传。"
                } else {
                    "每一笔都问 AI，通知原文会发送到接口地址。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = endpoint,
                onValueChange = {
                    endpoint = it
                    addressError = if (enabled && it.isBlank()) "接口地址不能为空" else ""
                },
                enabled = enabled,
                label = { Text("接口地址") },
                singleLine = true,
                isError = addressError.isNotBlank(),
                supportingText = {
                    Text(addressError.ifBlank { "OpenAI 兼容形状，如 https://…/v1/chat/completions" })
                },
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                enabled = enabled,
                label = { Text("API 密钥") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                supportingText = {
                    Text("经系统 Keystore 包裹后存在本机，不进备份、不随换机迁移。")
                },
                modifier = Modifier.fillMaxWidth(),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = enabled && apiKey.isNotBlank() && !probing,
                    onClick = {
                        if (!container.saveAiApiKey(apiKey)) {
                            probeResult = "保存密钥失败：请重试"
                            return@Button
                        }
                        apiKey = ""
                    },
                ) { Text("保存密钥") }
                OutlinedButton(
                    enabled = enabled && keyPresent,
                    onClick = {
                        container.clearAiApiKey()
                        apiKey = ""
                        probeResult = "密钥已清除"
                    },
                ) { Text("清除") }
            }

            OutlinedTextField(
                value = model,
                onValueChange = { model = it },
                enabled = enabled,
                label = { Text("模型名") },
                singleLine = true,
                supportingText = { Text("随请求一起发送，例如 gpt-4o-mini。") },
                modifier = Modifier.fillMaxWidth(),
            )

            Button(
                enabled = enabled && !probing,
                colors = ButtonDefaults.buttonColors(containerColor = LedgerPalette.Muted),
                onClick = {
                    if (endpoint.isBlank()) {
                        addressError = "接口地址不能为空"
                        return@Button
                    }
                    if (addressError.isNotBlank()) return@Button
                    container.settings.setAi(true, settings.aiMode, endpoint, model)
                    probing = true
                    probeResult = "正在测试…"
                    scope.launch {
                        // 探测结果只回显状态分类，绝不包含密钥或响应体。
                        probeResult = container.testAiConnectivity()
                        probing = false
                    }
                },
            ) { Text(if (probing) "测试中…" else "保存并测试连通性") }

            if (probeResult.isNotBlank()) {
                Text(
                    probeResult,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 「隐私与安全」分区第三条的文案（**条件化**）。
 *
 * 抽成纯函数是为了能脱离 Compose 做 JVM 单测 —— 这段文案是产品对用户的**承诺**
 * （「AI 关 = 数据不出设备」），不能只靠肉眼看 Compose 预览。
 *
 * @param enabled AI 总开关
 * @param endpoint 用户自填的接口地址（未填时按「未开启」处理）
 */
internal fun aiPrivacyLine(enabled: Boolean, endpoint: String): String =
    if (enabled && endpoint.isNotBlank()) {
        "已开启 AI 判定：通知正文会发送到你配置的接口地址（$endpoint）。除此之外不发任何数据上云。"
    } else {
        "不发任何数据上云；云同步仅有接口，当前是无操作的占位实现"
    }

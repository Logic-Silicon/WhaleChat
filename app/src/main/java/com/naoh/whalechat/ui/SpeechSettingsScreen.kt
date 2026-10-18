package com.naoh.whalechat.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.ButtonGroup
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ListSubHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.ScrollIndicator
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TextToggleButton
import com.naoh.whalechat.data.AsrEngine
import com.naoh.whalechat.data.ChatEngine

/**
 * 语音识别的引擎选择。**三档**，按 [AsrEngine.entries] 的顺序摆一排按钮。
 *
 * 为什么要有这一页：安卓那套语音 API 只负责把音频交给设备上装的
 * `RecognitionService`，**跑在本地还是联网由厂商决定，App 没有任何开关能指定**。
 * 于是设备上装的是谁，效果就是谁 —— 装的是 Google 的那套，在国内网络下
 * 就会表现为「经常报错」「提示没有可用的识别」「识别不准」。
 *
 * 想真正拿到稳定的联网识别，只能自己录、自己传。后两档就是那条路
 * （讯飞是流式，通用接口是录完整段再传）。
 *
 * ## 1.980：底部加 M3E 贴边「返回」
 *
 * `ScreenScaffold` 的 `edgeButton` 槽，参数**逐字对齐聊天页**（`ChatScreen.kt:315`）：
 * 默认尺寸（`EdgeButtonSize.Small`，56dp）+ 默认配色（⇒ `primary` / `onPrimary`，
 * 浅紫底）+ `labelLarge`。理由见 `DisplaySettingsScreen` 的同一段注释。
 *
 * ⚠️ **它由滚动位置驱动**（`ScrollInfoProvider.lastItemOffset`）：只有滚到列表末尾、
 * 或内容本来就短到末尾项可见，它才升起来。
 * 这一页**各档都超过一屏**（1.980 实测纠正过：「系统识别」那一档也是）⇒
 * **一进页面看不见它**，要滚到底才出现。这是官方设计 —— Wear Lists 指南原文：
 * 「使用 `edgeButton` slot 可确保按钮正确地贴在屏幕底部，**并在列表滚动时调整大小**」。
 *
 * ⚠️ 尾部留白 28dp → 10dp：`edgeButton` 已经把 `contentPadding` 底部撑开，
 * 再留 28dp 就是双份。
 *
 * ## 1.982：加回「通用接口」那一档
 *
 * 三处变化，别漏任何一处：
 *  1. 引擎按钮组**自动多一颗**（它遍历 [AsrEngine.entries]，✅ 不用改代码）；
 *  2. `engine_hint` 的穷尽 `when` **必须补 `GENERIC` 分支**（不补编译不过）；
 *  3. 多一整块 `if (settings.asrEngine == AsrEngine.GENERIC)` 的三格输入卡。
 *
 * ⚠️ 文案上注意圆屏：`HintText` 会折行，**多一行就可能把下面的内容顶出屏幕**，
 * 所以这里每一句都比 1.0 那版短，改完必须截图确认（见报告的需求 4 部分）。
 */
@Composable
fun SpeechSettingsScreen(
    onBack: () -> Unit,
    onEditAsrUrl: () -> Unit,
    onEditAsrKey: () -> Unit,
    onEditAsrModel: () -> Unit,
    onEditXfyAppId: () -> Unit,
    onEditXfyApiKey: () -> Unit,
    onEditXfyApiSecret: () -> Unit,
) {
    val settings by ChatEngine.settings.state.collectAsStateWithLifecycle()
    val listState = rememberScalingLazyListState()

    ScreenScaffold(
        scrollState = listState,
        edgeButtonSpacing = BackEdgeButtonSpacing,
        scrollIndicator = { ScrollIndicator(state = listState) },
        edgeButton = {
            // ⚠️ 官方 Wear Lists 指南示例里就有的那一句，五处贴边按钮一个都不能漏。
            EdgeButton(
                onClick = onBack,
                modifier = Modifier.edgeButtonScrollable(listState),
            ) {
                Text("返回", style = MaterialTheme.typography.labelLarge)
            }
        },
    ) { contentPadding ->
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = contentPadding,
            autoCentering = null,
        ) {
            item(key = "header") { ListHeader { Text("语音识别") } }

            item(key = "engine") {
                ButtonGroup(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(0.dp),
                ) {
                    AsrEngine.entries.forEach { engine ->
                        TextToggleButton(
                            checked = settings.asrEngine == engine,
                            onCheckedChange = { checked ->
                                if (checked) ChatEngine.settings.setAsrEngine(engine)
                            },
                            modifier = Modifier.weight(1f),
                            // 同设置页：选中的一档变圆角矩形，用形状说明「当前是哪个」。
                            shapes = selectionToggleShapes(),
                        ) {
                            Text(
                                text = engine.label,
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }

            item(key = "engine_hint") {
                HintText(
                    when (settings.asrEngine) {
                        AsrEngine.SYSTEM ->
                            "不用配置，直接用设备自带的识别服务。它准不准取决于设备上装的是谁，App 换不了也调不了。"

                        // 1.982 新增。这一句要同时干三件事：说清它是什么（兼容 OpenAI 的
                        // 识别接口）、说清**要用户自己有什么**（一个能用的识别接口 ——
                        // 用户手上没有可用 API 时，这句话是他判断「这一档我用不用得上」的
                        // 唯一依据）、以及指出下面那三格。刻意不写「自建」——
                        // 项目口径是这一档统称「通用接口」。
                        AsrEngine.GENERIC ->
                            "录完整段再传给一个兼容 OpenAI 的识别接口，所以它需要一个能用的识别接口。下面三格填地址、Key 和模型名。"

                        AsrEngine.XFYUN ->
                            "边说边出字，不用等录完，等待感最小。下面那三串凭证缺一样都调不通。"
                    },
                )
            }

            // ---------------------------------------------------------- 通用接口
            // 1.982 加回来的一整块。只在选中这一档时出现 —— 三格和讯飞那三格
            // 是两套完全不同的凭证，同时摆出来会让人不知道该填哪一组。
            if (settings.asrEngine == AsrEngine.GENERIC) {
                item(key = "generic_header") { ListSubHeader { Text("识别接口") } }

                item(key = "generic_url") {
                    ActionCard(
                        title = "接口地址",
                        // 只显示主机名：完整 URL（含 /v1/audio/transcriptions）在这块圆屏上
                        // 要折三四行，反而看不出是哪个服务。真正能区分「用的哪家」的信息
                        // 就在主机名里，要看全文点进去就有。
                        subtitle = shortHost(settings.asrUrl),
                        icon = AppIcons.Sparkle,
                        onClick = onEditAsrUrl,
                    )
                }

                item(key = "generic_key") {
                    ActionCard(
                        title = "识别 Key",
                        // 「自建服务可留空」这句必须写出来：本机 whisper.cpp 不鉴权，
                        // 而用户看到「Key」两个字会以为必填，然后卡在这一格上。
                        subtitle = if (settings.asrKey.isBlank()) {
                            "未填写（自建的可以留空）"
                        } else {
                            maskKey(settings.asrKey)
                        },
                        icon = AppIcons.Sparkle,
                        onClick = onEditAsrKey,
                    )
                }

                item(key = "generic_model") {
                    ActionCard(
                        title = "模型名",
                        subtitle = settings.asrModel.ifBlank { "未填写" },
                        icon = AppIcons.Keyboard,
                        onClick = onEditAsrModel,
                    )
                }

                item(key = "generic_note") {
                    // 压成一句：圆屏上多一行就可能把下面顶出去（见类注释末尾）。
                    // 原文（1.0 版）是「兼容 OpenAI 的 /v1/audio/transcriptions 接口
                    // 都能用，自建的 whisper.cpp 也可以。」—— 那两句在 `engine_hint`
                    // 里已经说过了，这里只留**操作上真正多出来的一条信息**：地址要带全路径。
                    HintText("地址要带全路径，比如结尾的 /v1/audio/transcriptions。")
                }
            }

            // ------------------------------------------------------------ 讯飞
            if (settings.asrEngine == AsrEngine.XFYUN) {
                item(key = "xfy_cred_header") { ListSubHeader { Text("讯飞听写") } }

                // 三串都是十六进制长串，在这块圆屏上逐字敲基本没法完成。
                // 手机扫码导入那张表里也有这三格，而且是从控制台复制过来的同一份东西，
                // 顺手提示一句，省得用户在手表上跟自己较劲。
                item(key = "xfy_import_hint") {
                    HintText("这三串在手表上敲很费劲。回设置页用「手机扫码改凭证」，手机上填一次更快。")
                }

                item(key = "xfy_appid") {
                    ActionCard(
                        title = "AppID",
                        subtitle = settings.xfyAppId.ifBlank { "未填写" },
                        icon = AppIcons.Sparkle,
                        onClick = onEditXfyAppId,
                    )
                }

                item(key = "xfy_apikey") {
                    ActionCard(
                        title = "APIKey",
                        subtitle = if (settings.xfyApiKey.isBlank()) {
                            "未填写"
                        } else {
                            maskKey(settings.xfyApiKey)
                        },
                        icon = AppIcons.Sparkle,
                        onClick = onEditXfyApiKey,
                    )
                }

                item(key = "xfy_apisecret") {
                    ActionCard(
                        title = "APISecret",
                        subtitle = if (settings.xfyApiSecret.isBlank()) {
                            "未填写"
                        } else {
                            maskKey(settings.xfyApiSecret)
                        },
                        icon = AppIcons.Sparkle,
                        onClick = onEditXfyApiSecret,
                    )
                }

                // ⚠️ 这里原来还有一条 `xfy_note`
                // （「三串都在讯飞控制台「我的应用」里，缺一样都调不通。」），**1.978 整条删掉**。
                // 三串凭证已经在上面三张卡里各占一格、各自带「未填写」提示，
                // 再去告诉用户「它们在哪」属于重复；而这句话还顺带把
                //「缺一样都调不通」讲了一遍 —— 那是失败时的提示该说的话，不是常驻说明。
                // **别再把它加回来。**
            }

            item(key = "limit_note") {
                // 1.978 压缩：「单次录音最长 60 秒，说短句效果最好。」→「单次最长 60 秒」。
                // 前半句是硬约束（说清上限），后半句是建议，圆屏上一行放不下两件事
                // ⇒ 只留约束。「录音」两个字也去掉：这一整页都是语音识别，
                // 上下文已经说明了限的是什么。
                //
                // ⚠️ 1.986：`inCard = false` —— 用户原话：「语音识别最底下文字描述卡片
                // 直接改成只剩文字，不用放在那个大空卡片里很丑」。
                HintText("单次最长 60 秒", inCard = false)
            }

            // 1.980：28dp → 10dp（理由见类注释末尾「尾部留白」那段）。
            item(key = "bottom_gap") { GapItem(10.dp) }
        }
    }
}

/**
 * 只留主机名。1.982 从 1.0 恢复，理由逐字沿用：
 *
 * 完整地址（含 `/v1/audio/transcriptions`）在手表卡片上要折三四行，
 * 而且真正能区分「用的哪家」的信息就只在主机名里。
 *
 * ⚠️ 它**只影响显示**，不改 `Settings.asrUrl` 里存的值 —— 提交给 [SpeechClient]
 * 的永远是用户填的那一串全文（地址少一截就会 404，这一点不能含糊）。
 */
private fun shortHost(url: String): String {
    if (url.isBlank()) return "未填写"
    val stripped = url.removePrefix("https://").removePrefix("http://")
    return stripped.substringBefore('/').ifBlank { url }
}

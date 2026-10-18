package com.naoh.whalechat.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.ScrollIndicator
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import com.naoh.whalechat.data.ChatEngine

/**
 * 「显示设置」二级页。
 *
 * ⚠️ **入口从主设置页隐藏了**（`SettingsScreen.SHOW_DISPLAY_SETTINGS_ENTRY`）。
 * **这一页本身没删、路由也还在** ——
 * 把那个常量改回 `true` 就恢复入口。隐藏期间三个开关的值由 `SettingsStore` 的
 * 默认值 + 两次一次性覆盖（`applyDisplayDefaults`，1911 那次 + 1979 那次）决定。
 *
 * 三个开关，各管一处，互不影响（括号里是**当前默认值**）：
 *
 *  1. [气泡时间][com.naoh.whalechat.data.Settings.showBubbleTime]（**默认开**）—— 对话页里
 *     **用户自己发出的**消息气泡右上角那个时间（助手回答不挂时间）；
 *  2. [对话中的用量][com.naoh.whalechat.data.Settings.showUsageInChat]（**默认开**）——
 *     对话页底部那行「已用 xx tokens · 命中 yy%」。
 *     ⚠️ **1.981 起只管 chat（人设会话）那一侧**：ask 的对话页一律不显示这一行（用户要求）；
 *  3. [角色列表用量][com.naoh.whalechat.data.Settings.showUsageOnPersona]（**默认开**）——
 *     通讯录角色卡片副标题左端那串 `12.4k tokens`。
 *
 * ## 为什么拆出一页，而不是把三个开关摆进主设置页
 *
 * 更早那版主设置页里是一个开关（`showTokenUsage`）管着第 2、3 两处。
 * 后来要能分开管，还多加了气泡时间 —— 三个 `SwitchButton` 竖着排，
 * 每个 ~64dp，加上组标题就是这一整页最占地方的一组，会把「关于」挤到很下面。
 * 收进二级页之后主设置页只留一张入口卡（一行），代价是多一次点击。
 *
 * ## 为什么不随来源分叉
 *
 * 主设置页有一整套 `origin`（ASK / CHAT）分叉，但**这一页不在其中**：
 * 三个开关各只有一份值，跟从哪一页进来的没有关系。分叉了反而会让人以为
 * 「从 ask 关掉、从 chat 进来还是开的」。
 *
 * ## 为什么第三个开关的副标题不提「时间」
 *
 * 角色卡片副标题是个 `Row`：左端用量、右端时间。**只有左端受这里管** ——
 * 右端那个时间没有开关，跟着卡片一起显示（见 `Settings.showUsageOnPersona` 的注释）。
 * 所以副标题只写用量，免得让人以为关掉连时间也没了。
 *
 * ## 1.980：底部加 M3E 贴边「返回」
 *
 * 用的还是 `ScreenScaffold` 的 `edgeButton` 槽（和聊天页/扫码页同一颗组件）。
 * ⚠️ 参数**逐字对齐聊天页**（`ChatScreen.kt:315`）：默认尺寸（`EdgeButtonSize.Small`，
 * 56dp）+ 默认配色（`ButtonDefaults.buttonColors()` ⇒ `primary` / `onPrimary`）+ `labelLarge`。
 * ⚠️ 1.992：**五处贴边按钮的配色已全部统一**为默认（`primary` / `onPrimary`）——
 * 扫码页那颗原来的 `surfaceContainer` 显式配色已删（1.980 新增各页时明确知道
 * 它不一致但没回头改，本轮补上）。
 *
 * ⚠️ 它**由滚动位置驱动**（`ScrollInfoProvider.lastItemOffset`）：滚到列表末尾才升起。
 * 1.980 实测纠正了一句想当然的话：这一页**内容比一屏高**（三个 64dp 的开关 + 组标题
 * + 提示），所以**一进页面看不见它**，要滚到底才出现 —— 这是官方设计
 * （Wear Lists 指南：「使用 `edgeButton` slot 可确保按钮正确地贴在屏幕底部，
 * **并在列表滚动时调整大小**」），不是没做。
 *
 * ⚠️ 尾部留白跟着从 28dp 减到 10dp：`edgeButton` 已经把 `contentPadding` 的底部撑开，
 * 列表里那 28dp 再留就是**双份留白**（1.978 聊天页栽过一次，48dp→10dp 才修好）。
 */
@Composable
fun DisplaySettingsScreen(
    onBack: () -> Unit,
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
            // 三个开关只有一屏多一点，不需要自动居中把某一项顶到屏幕正中。
            autoCentering = null,
        ) {
            item(key = "header") { ListHeader { Text("显示设置") } }

            item(key = "bubble_time") {
                SwitchButton(
                    checked = settings.showBubbleTime,
                    onCheckedChange = { ChatEngine.settings.setShowBubbleTime(it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = {
                        Text(
                            text = "气泡上的时间",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    },
                    secondaryLabel = {
                        // 说清「关掉长什么样」：不是变灰，是整块消失 ——
                        // 用户需要知道关掉之后名字那行会空出右边一整块，而不是一个灰时间。
                        //
                        // ⚠️ 文案要写「你发出的」：修订之后
                        // **只有用户自己的气泡有时间**，助手回答不挂时间。
                        // 写「每条消息」会让人去回答气泡上找时间。
                        Text(
                            text = if (settings.showBubbleTime) {
                                "你发出的消息右上角显示时间"
                            } else {
                                "已隐藏，随时可以再打开"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )
            }

            item(key = "usage_chat") {
                SwitchButton(
                    checked = settings.showUsageInChat,
                    onCheckedChange = { ChatEngine.settings.setShowUsageInChat(it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = {
                        Text(
                            text = "对话中的用量",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    },
                    secondaryLabel = {
                        Text(
                            text = if (settings.showUsageInChat) {
                                "对话页底部显示「本对话已用」"
                            } else {
                                "已隐藏，随时可以再打开"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )
            }

            item(key = "usage_persona") {
                SwitchButton(
                    checked = settings.showUsageOnPersona,
                    onCheckedChange = { ChatEngine.settings.setShowUsageOnPersona(it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = {
                        Text(
                            text = "角色列表的用量",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    },
                    secondaryLabel = {
                        // 只提用量，**不**提右边的钟点（那个没有开关，见类注释）。
                        Text(
                            text = if (settings.showUsageOnPersona) {
                                "通讯录里每个角色下方显示用量"
                            } else {
                                "已隐藏，随时可以再打开"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )
            }

            // ask 第一页那条老规矩**在这一页也要说清**：不然用户关掉第二个开关、
            // 回头看见 ask 的对话列表里本来就没有数字，会以为那才是「没关干净」。
            //
            // ⚠️ 文案按**当前界面事实**陈述（1.979 改）：1.978 第三轮已经把 ask 页那个
            // 「最近对话」`ListSubHeader` 整块删掉了（用户原话：「ask 页的『最近对话』
            // 四个字删去」），旧文案还在引用那四个字 —— 用户在这一页上根本找不到它。
            // 现在改成指名「Ask 那一页的会话列表」，那才是它真正管的地方。
            item(key = "scope_hint") {
                HintText("这三项只管看到的观感，不影响对话本身。Ask 那一页的会话列表从来不显示用量。")
            }

            // 1.980：28dp → 10dp。底部现在由那颗 M3E 贴边返回按钮占着，
            // `edgeButton` 自己已经给列表底部加了 75dp 的内边距；这里再留 28dp
            // 就是双份留白（和聊天页 `BOTTOM_GAP` 48dp→10dp 同一件事）。
            item(key = "bottom_gap") { GapItem(10.dp) }
        }
    }
}

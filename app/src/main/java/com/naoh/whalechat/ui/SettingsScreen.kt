package com.naoh.whalechat.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.ButtonGroup
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CardDefaults
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ListSubHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.ScrollIndicator
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TextToggleButton
import com.naoh.whalechat.data.AsrEngine
import com.naoh.whalechat.data.ChatEngine
import com.naoh.whalechat.data.ChatMode
import com.naoh.whalechat.net.DeepSeekException
import kotlinx.coroutines.launch

/**
 * 「显示设置」组标题**之上**的空隙。
 *
 * 这一组是**独立类别**、和上一组（语音识别）之间要有空隙排布，
 * 而不是两张卡挨着。空隙取 16dp —— 比对上一条 `GapItem(28.dp)`（页尾）小、
 * 比组内项间距大，刚好够在圆屏上把两组「断开」，又不会把这一组顶到屏幕外。
 */
private val DISPLAY_GROUP_GAP = 16.dp

/**
 * 「关于」这一组**之上**的空隙（1.982 新增）。
 *
 * 用户原话：「设置页底下清空对话的选项和关于靠太近了」。
 *
 * ## 根因
 *
 * `SettingsScreen` 的「数据」组（清空卡）和「关于」卡之间**什么都没有** ——
 * 原来是靠一个 `ListSubHeader { Text("关于") }` 分开的，而那个组标题
 * **1.978 特意删掉了**（它和卡片标题「关于 WhaleChat」重复）。
 * 标题一走，两张卡之间只剩 `ScalingLazyColumn` 的默认项间距 **4dp**，
 * 看起来就是「不相关的两张卡粘在一起」。
 *
 * ## 为什么取 16dp
 *
 * **与 [DISPLAY_GROUP_GAP] 一致**，理由是同一个：「这两处是不同类别，要断开」，
 * 且与项目里已有的分组间距约定统一（组内项间距仍是列表默认 4dp）。
 *
 *  * 比组内项间距（4dp）明显大 ⇒ 能读出「换了一组」；
 *  * 比页尾 `bottom_gap`（28dp）小 ⇒ 这是**组间**分隔，不是页尾留白，
 *    两处加起来不会在圆屏底部堆出太多空白；
 *  * 更大（24/28dp）会把「关于」卡再往下推 8~12dp，而这一页本来就长
 *    （ask 来源尤其），首屏能看见的内容只会更少。
 *
 * ⚠️ **修法是把「要断开」交回给空隙本身，不是把组标题加回来** ——
 * 那个标题是 1.978 明确删掉的（会显示成「关于 / 关于 WhaleChat」重复两次），
 * 别顺手恢复。
 *
 * ⚠️ 它**不随 `origin` 分叉**：从 chat 进来时上面不是「数据」组而是别的组，
 * 但这张关于卡**同样需要和上一组断开**，所以两处都给。
 */
private val ABOUT_GROUP_GAP = 16.dp

/**
 * 「清空对话」/「关于 WhaleChat」这两张**只有一行文字**的入口卡的高度。
 *
 * ## ⚠️⚠️ 这是本轮最典型的那个 Wear M3 公共陷阱
 *
 * `Card` 自带 `Modifier.cardSizeModifier()`（`Card.kt:929`）：
 * ```kotlin
 * private fun Modifier.cardSizeModifier(): Modifier =
 *     defaultMinSize(minHeight = CardDefaults.Height)   // = CardTokens.ContainerMinHeight = 64.dp
 * ```
 * ⇒ **内容再矮也会被撑到 64dp**（@2.0 = 128px），
 * 这正是用户说的「明明一行文字选项卡片却是两行高度」。
 *
 * ⚠️ **调 `contentPadding` 压不下去**：`Card` 的 `modifier` 是在
 * `CardImpl` 里先 `.fillMaxWidth().surface().combinedClickable()` 再把
 * `modifier` 接在**最前面**的（`CardImpl` 源码里 `modifier.fillMaxWidth()...`），
 * 于是 `defaultMinSize(64.dp)` 作用在**外层、还要再受一次外层约束**，
 * 把纵向内边距压到 0 也一样被撑到 64dp。
 *
 * ⚠️ **唯一有效杠杆是显式 `Modifier.height(h)`**：`Modifier.height` 是
 * `SizeElement(enforceIncoming = true)`，它把**外层约束**钉成 min=max=h
 * （`Size.kt` → `SizeNode.measure`：`enforceIncoming = true` 时用强制出的
 * `targetConstraints`）。内层 `defaultMinSize` 的判据是
 * `constraints.minHeight == 0`（`Size.kt:1085`，`UnspecifiedConstraintsNode`），
 * 而外层已经把它抬到 h ≠ 0 ⇒ **`defaultMinSize` 直接让位**，卡片就是 h。
 *
 * ## ⚠️⚠️ 配套：给了高度就**必须**把 `contentPadding` 纵向一起压到 0
 *
 * 两件事缺一不可（1.978 踩过一次，症状是「整张卡画不出来」）：
 * 卡片内部的 `Column` 先吃 `contentPadding`，`Text` 拿到的是**剩下的**高度。
 * 默认纵向内边距是 12 + 12 = 24dp，`h = 48dp` 时留给文字只剩 24dp ——
 * 加上 `titleMedium` 行盒 18sp（≈22dp）勉强够，再矮一点就是 **0 高、整个画不出来**
 * （截图空白，`uiautomator dump` 里连节点都没有）。
 *
 * ⚠️ **`fillMaxWidth()` 也要在**：居中/换行依赖它给的确定宽度。
 * 顺序上 `height` 与 `fillMaxWidth` 谁先谁后无所谓，但两个都要挂。
 *
 * ## 为什么是 48dp
 *
 * 这两张卡的内容都只有**一行** `titleMedium`：
 * 行盒 = `lineHeight` 18sp ⇒ 18 × 2 = 36px @2.0 = **18dp**（墨迹更低，约 12dp）。
 * `48 − 0（纵向内边距）= 48dp` 可用，是行盒的 2.7 倍 —— 量出来绝不会是 0 高。
 *
 * 为什么不取 [HomeScreen] 会话卡的 `56.dp`：那张卡是**两行**（标题 + 副标题），
 * 56dp 是「内容自然高 54dp 再加 2dp」的结果；这里只有一行，
 * 照抄 56dp 就等于把用户抱怨的「两行高度」从 64 换成 56，问题只解决了一半。
 *
 * 为什么不更矮（40dp）：圆屏上这块可点区域还要过 M3E 的触控与按压动效，
 * 而且 48dp 是 M3 体系里通用的最小触控尺寸，**别再往下压**。
 * 这个值**必须装机实测复核**（见报告：改前/改后高度 px）。 */
private val SINGLE_LINE_CARD_HEIGHT = 48.dp

/**
 * 单行卡片的内边距。**横向照旧 12dp，纵向必须是 0** —— 理由见
 * [SINGLE_LINE_CARD_HEIGHT] 的「配套」一段（纵向留着内边距会把文字挤成 0 高）。
 */
private val SINGLE_LINE_CARD_PADDING = PaddingValues(horizontal = 12.dp, vertical = 0.dp)

/**
 * 「显示设置」二级页入口是否可见。
 *
 * ⚠️ **暂时隐藏**（「把显示设置隐藏（先不删）」）。
 * 代码、路由（`WhaleChatApp.SETTINGS_DISPLAY`）和 `DisplaySettingsScreen` **全部保留**，
 * 把这个常量改回 `true` 就能恢复入口 —— 这也是当初没有直接删掉的原因。
 *
 * 隐藏期间三个显示开关的值由 `SettingsStore` 的默认值 + 它的**两次**一次性覆盖
 * （`applyDisplayDefaults`）决定：**对话用量开 / 气泡时间开 / 角色列表用量开**。
 *
 * ⚠️ 恢复入口时，下面 `if` 块里那套排布约定（独立类别、
 * `GapItem` 必须**在组标题之上**、组标题「显示」与卡片「显示设置」**不同名**）
 * 全部仍然有效，照它做就行 —— **不要**因为「看到一段被关掉的旧代码」就顺手删掉。
 */
private const val SHOW_DISPLAY_SETTINGS_ENTRY = false

/**
 * 设置页。
 *
 * 排序沿用一条原则：**先给「一次配完所有东西」的入口，再按「这块设置属于谁」分组。**
 *
 * 第 1 项是「手机扫码改凭证」，故意提出来放在最前面、不属于任何分组。
 * 理由是这个应用要用户配的是**两套完全不同的凭证** ——
 *  1. 跟模型对话用的 DeepSeek API Key；
 *  2. 把话转成字的语音识别凭证（讯飞听写的 AppID/APIKey/APISecret）。
 * 而扫码那一页**同时管这两套**。它原先挂在「语音识别」组里，看起来只管说话那一路，
 * 用户想改对话的 Key 时根本不会想到去点它；挪到最前面之后，它的定位是「凭证总入口」，
 * 和下面两个分组并列，谁都不隶属。
 *
 * **第 1 项的位置和文案不随来源变** —— 从 ask 还是 chat 进来，它都是第一项。
 *
 * ## 第 2 项只在「从 chat 进来」时出现
 *
 * 从通讯录那一页（chat）点齿轮进来时，「改凭证」下面会多一项「用手机管理人设」。
 * 理由：站在 chat 这一页，用户手上正在处理的是**人**，人设六块只能在手机上写好，
 * 所以这个入口在这里是刚需；而站在 ask 那一页，他手上处理的是**一次提问**，
 * 给他摆一个人设入口只会多一个要读、要判断的东西。
 *
 * ## 跟着 [origin] 分叉的不止这一项
 *
 * 同一个原则后来又推了三处，三处都是同一个理由 ——
 * **这一页摆出来的东西，得对它脚下这一页真的生效**：
 *
 *  1. **档位选择器一次只给一组**：从 ask 进来调 `mode_ask`，从 chat 进来调 `mode_chat`。
 *     两组并排会让人以为「改一个两边都变」，而它们是两个独立存的值。
 *  2. **「系统提示词」只在 ask 出现**：chat 的 prompt 是人设六块 + `CHAT_OUTPUT_RULES`
 *     每次发送现拼的，全局那套**根本不参与**（`ChatEngine.startStream` 的 `?:` 二选一）。
 *  3. **「数据 / 清空所有对话」整组只在 ask 出现**：清的是一次性提问的会话；
 *     chat 那边是「人」，要删只能一个一个删，不给批量删角色的入口。
 *
 * [origin] 就是这三件事的开关。**同一个路由、同一个页面**，
 * 只是多一项少一项 —— 不另开一条「chat 专属设置页」路由：
 * 那样两页的设置项从此要各改一遍，迟早会改漏。
 *
 * 下面各组各自把同类设置放在一起：**模型**（对话用）、**语音识别**（说话用）、
 * **数据**（清对话，只在 ask）、**显示设置**（二级页入口；
 * ⚠️ **该入口暂时隐藏**，见 [SHOW_DISPLAY_SETTINGS_ENTRY]）、**关于**。
 * 卡片标题写明「DeepSeek API Key」而不是笼统的「API Key」，免得和语音那侧的 Key 混起来。
 *
 * ## 历史改动
 *
 *  * **「用量与余额」这一组没了。** 里面那个 token 用量开关（原 `showTokenUsage`）已经
 *    拆成两个独立开关，挪进新的「显示设置」二级页；余额卡搬到「DeepSeek API Key」
 *    卡片正下方 —— 它俩是一件事的两头（都是「这个 Key 花掉/还剩多少」），
 *    摆在一起比挂在全页末尾更好找。
 *  * **「显示设置」入口取代了原「用量与余额」组的位置**：紧贴「关于」上方。
 *    它**不随 [origin] 分叉**：气泡时间、两处用量显示都是全局观感，跟从哪一页
 *    进来没关系。分叉了反而会让人以为「这一页关掉、那一页还开着」。
 *  * **「显示设置」入口整组隐藏**。把 [SHOW_DISPLAY_SETTINGS_ENTRY] 切回 `true` 即恢复；
 *    上面那些排布约定一条都没作废。
 */
@Composable
fun SettingsScreen(
    origin: HomePage,
    onEditApiKey: () -> Unit,
    onEditPrompt: () -> Unit,
    onImportApiKey: () -> Unit,
    onImportPersonas: () -> Unit,
    onOpenSpeech: () -> Unit,
    /**
     * 打开「显示设置」二级页。三个显示开关都在那边，这一页只留一个入口。
     *
     * ⚠️ 入口被 [SHOW_DISPLAY_SETTINGS_ENTRY] 关掉 ⇒ **这个回调暂时不会被调用**，
     * 但形参和导航分支都保留（「先隐藏、不删」）。恢复入口时不用改签名。
     */
    onOpenDisplay: () -> Unit,
    /**
     * 打开「关于」二级页。1.978 新增 —— 原来那一格是本页里的一段文字，
     * 现在只留一个入口。
     */
    onOpenAbout: () -> Unit,
) {
    val settings by ChatEngine.settings.state.collectAsStateWithLifecycle()
    // 订阅会话列表。以前这里直接读 ChatEngine.conversations.value —— 那不会触发重组，
    // 于是「清空所有对话」之后卡片上的「共 N 条」会一直停在旧数字上。
    val conversations by ChatEngine.conversations.collectAsStateWithLifecycle()

    val listState = rememberScalingLazyListState()
    var confirmClear by remember { mutableStateOf(false) }

    // 「从哪一页进来」决定这一页摆什么。下面三处分叉（档位选择器 / 系统提示词 / 数据组）
    // 全看这一个值 —— **同一页 + 一个 origin，不是两页**。
    //
    // 理由：站在 chat 这一页，用户手上的东西是**人**（角色、人设），
    // 全局那套 ask 配置对它一概不生效，摆出来只会让人白改一趟。
    val isChatOrigin = origin == HomePage.CHAT

    // 档位：一次只给一组，给哪组由来源决定。写档位的落盘那一跳在 SettingsStore，
    // 这里只负责「用户在哪儿调它」。
    val currentMode = if (isChatOrigin) settings.modeChat else settings.modeAsk
    val setMode: (ChatMode) -> Unit = { mode ->
        if (isChatOrigin) ChatEngine.settings.setModeChat(mode) else ChatEngine.settings.setModeAsk(mode)
    }

    // 时间胶囊由外层 AppScaffold 统一提供，这里只用管滚动指示器
    ScreenScaffold(
        scrollState = listState,
        scrollIndicator = { ScrollIndicator(state = listState) },
    ) { contentPadding ->
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = contentPadding,
            autoCentering = null,
        ) {
            item(key = "header") { ListHeader { Text("设置") } }

            // 第 1 项：**凭证总入口**。它同时管「模型」和「语音识别」两套凭证，
            // 所以不隶属下面任何一组，摆在全页最前面。
            //
            // 位置和文案**不随 [origin] 变**：不管从哪一页进来，它都是第一项。
            item(key = "import_key") {
                ActionCard(
                    // 文案跟着职责走：这一页会把表上已有的值摊出来，所以它是
                    // 「看 + 改」而不是单向「导入」—— 不说清的话用户想不到它能用来核对。
                    // 副标题点明**它管的是全部凭证**，包括下面「模型」组里的 Key。
                    title = "手机扫码改凭证",
                    subtitle = "同 Wi-Fi 扫码 · 两套凭证一起改",
                    icon = AppIcons.Phone,
                    onClick = onImportApiKey,
                )
            }

            // 第 2 项：**只在从 chat 进来时给**。理由见上面的类注释。
            //
            // 它和上一项**共用同一个服务、同一份 token**，只是二维码里的落地路径不同
            // （这个是 `/$token/p`，直接落在人设列表上）—— 详见 ImportKeyScreen 的参数说明。
            // 图标必须和上一项分开：两张卡片上下紧挨着，形状一样、只有文字不同的话，
            // 扫一眼分不出来哪个管凭证、哪个管人设。
            if (origin == HomePage.CHAT) {
                item(key = "import_persona") {
                    ActionCard(
                        title = "用手机管理人设",
                        // 副标题**刻意比上面那张短**，压成一行。
                        // 上面那张是「同 Wi-Fi 扫码 · 两套凭证一起改」，在这个宽度上要折两行；
                        // 两张都折两行的话，第二张的下半截会被圆屏下沿切掉、后面的「模型」
                        // 整组滚出首屏。用「·」并列是好看，但代价是首屏少看见一组设置 —— 不值。
                        subtitle = "同 Wi-Fi 扫码改人设",
                        icon = AppIcons.Person,
                        onClick = onImportPersonas,
                    )
                }
            }

            // -------------------------------------------------------------- 模型
            item(key = "model_header") { ListSubHeader { Text("模型") } }

            item(key = "key") {
                ActionCard(
                    // 「DeepSeek」必须写进标题：下面语音那一组也有 Key，
                    // 只说「API Key」两张卡就读不出区别
                    title = "DeepSeek API Key",
                    subtitle = if (settings.apiKey.isBlank()) {
                        "未填写 · 点这里粘贴 sk-…"
                    } else {
                        "已配置 ${maskKey(settings.apiKey)}"
                    },
                    icon = AppIcons.Sparkle,
                    onClick = onEditApiKey,
                )
            }

            // 余额卡紧贴 Key 卡下面。
            //
            // 原来它在全页末尾、挂在一个「用量与余额」组标题底下；后来把那一组拆了，
            // 它搬到这儿 —— 理由是**它和上面那张卡是同一件事的两头**：
            // 上面填 Key，下面看这个 Key 还剩多少钱。中间隔着一整页设置才找得到，
            // 是原来那套分组逻辑（按「属于谁」，余额不属于任何一组）留下的代价。
            //
            // 卡片内部逻辑一字未改，只是换了位置。
            item(key = "balance") {
                BalanceCard()
            }

            // 档位**一次只给一组**：给哪一组由「从哪一页进来」决定。
            //
            // 为什么不是两组并排：并排会让人以为「改一个两边都变」，
            // 或者以为那是同一个设置的两个入口 —— 而它们其实是两个独立存的值
            // （`Settings.modeAsk` / `modeChat`，prefs 的 `mode_ask` / `mode_chat`）。
            // 同一个设置摆两排，要么有一排是废的，要么两排互相矛盾，没有第三种可能。
            //
            // 标题**同时承载了两件事**：「档位」+「是哪一页的档位」。
            // 更早这里写的是「Ask 用哪一档 / Chat 用哪一档」，下面还挂一条
            // 「只管这一页：Ask 和 Chat 各存一档」的说明 —— 后来
            // 明确否掉了那条说明（「你的歧义点就是我想删的」）。
            // 删掉之后「两档独立」这件事**只能靠标题自己说清**，所以标题固定成
            // 「Ask 档位 / Chat 档位」—— 从 ask 进来就写 Ask，不写「这一页」。
            item(key = "mode_header") {
                Text(
                    text = if (isChatOrigin) "Chat 档位" else "Ask 档位",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            item(key = "mode") {
                ButtonGroup(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(0.dp),
                ) {
                    ChatMode.entries.forEach { mode ->
                        TextToggleButton(
                            checked = currentMode == mode,
                            onCheckedChange = { checked -> if (checked) setMode(mode) },
                            modifier = Modifier.weight(1f),
                            // 选中的那一档拉成圆角矩形，未选中保持整圆胶囊：
                            // 形状本身在说明「哪个是当前这一档」，不用只靠颜色。
                            shapes = selectionToggleShapes(),
                        ) {
                            Text(
                                text = mode.label,
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }

            // ⚠️ 档位这一格下面原来挂着**两块**说明文字，**1.978 一起删掉了**：
            //
            //  1. `mode_scope_hint`（「只管这一页：Ask 和 Chat 各存一档，改一个不动另一个。」）
            //     —— 用户原话「你的歧义点就是我想删的」。那句话本身就是先让人意识到有歧义、
            //     再解释，反而把「两档独立」讲成了一个需要说明的特例。删掉后靠上面
            //     `mode_header` 的标题（「Ask 档位 / Chat 档位」）承载同一件事。
            //  2. `mode_hint`（两档各自的介绍，快答那档原话是「直接作答，最快的一档。
            //     日常问答够用，适合在手表上快点看完。」）—— 用户原话「模型思考介绍文字删去」。
            //     按「**整块 mode_hint 不再显示**」执行：**两档都不再出介绍**。
            //     按钮自己的名字（快答 / 深度思考）已经把差别说清，多一段解释只是白占
            //     圆屏首屏下面本来就紧张的那一屏。
            //
            // ⚠️ **两块都别再放回来**，也**不要**「只恢复其中一档」——
            // 那会让从 ask 进来和从 chat 进来看到的这一页不一样长，
            // 而这两个页面的其余部分是完全同构的。

            // ★ 联网搜索（1.983）：**一级开关**，就摆在「模型」组里、档位下面。
            //
            // ## 为什么是「一级开关」而不是二级页
            // 用户原话：「在设置里同样给**一级开关**放在模型设置那块」。
            // ⇒ 直接一个 `SwitchButton` 摆在列表里，**不要再开二级页**
            //（对比「显示设置」那三个开关：它们摆不下才收进二级页的）。
            //
            // ## 为什么按来源分叉（和档位同一套）
            // 用户原话：「**chat 设置对应 chat 是否启用联网搜索，ask 就对应 ask**」。
            // 判据与 `mode` 那一格**逐字相同**（都用上面那个 `isChatOrigin`），
            // 落盘也是各写各的键（`search_ask` / `search_chat`）。
            //
            // ## ⚠️ 文案必须点明是 Ask 还是 Chat
            // 这正是当年「档位」那一格踩过的坑（见上面那段长注释）：
            // 用户看到「联网搜索」这一行时，**没有任何线索告诉他是哪一页的设置**，
            // 于是会以为「改一个两边都变」。
            // 所以 `secondaryLabel` 里必须出现 **Ask / Chat** 字样。
            //
            // ## ⚠️ 还必须让用户看到「它要额外花钱」
            // 搜索是**按次计费**的（官方 5 元 / 1000 次）。用户有权知道开这个开关
            // 会让每次提问多花一次钱。圆屏窄，所以压成极短的一句「按次计费」。
            //
            // ## ⚠️ 不要写解释性文案
            // 不要写「改一个不动另一个」那种说明 —— `SettingsScreen.kt` 原来那条
            // `mode_scope_hint` 就是被用户删掉的（原话「你的歧义点就是我想删的」）。
            // 靠「Ask / Chat」字样自己说清，别去解释「它们是独立的」。
            val searchOn = if (isChatOrigin) settings.searchChat else settings.searchAsk
            val setSearch: (Boolean) -> Unit = { on ->
                if (isChatOrigin) ChatEngine.settings.setSearchChat(on)
                else ChatEngine.settings.setSearchAsk(on)
            }

            item(key = "search") {
                SwitchButton(
                    checked = searchOn,
                    onCheckedChange = setSearch,
                    modifier = Modifier.fillMaxWidth(),
                    label = {
                        // ⚠️ label 只放四个字「联网搜索」—— 实测把「· 按次计费」也塞进 label
                        // 会在这块圆屏上**从词中间折行**（「联网搜索 · 按次计」/「费」），
                        // 很难看。「按次计费」挪到 secondaryLabel 里，那里本来就是小字。
                        Text(
                            text = "联网搜索",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    },
                    secondaryLabel = {
                        // ⚠️ 1.986：副标题固定成「由 Deepseek 提供搜索结果并按次收费」
                        //（用户原话，**不随开关状态变**）—— 说清「谁在提供服务」和「要花钱」。
                        // Ask/Chat 的归属由上面的「Ask 档位 / Chat 档位」标题承载。
                        Text(
                            text = "由 Deepseek 提供搜索结果并按次收费",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )
            }

            // 「系统提示词」只管 ask。chat 那边的 prompt 是**人设六块 + CHAT_OUTPUT_RULES**
            // 每次发送时现拼的（见 `ChatEngine.startStream`），全局这一套**根本不参与** ——
            // 从 chat 进来摆这个入口，就是在暗示「这里改的东西对这个角色生效」，而它并不生效。
            if (!isChatOrigin) {
                item(key = "prompt") {
                    ActionCard(
                        title = "系统提示词",
                        subtitle = if (settings.systemPrompt.isBlank()) {
                            "未设置 · 点这里给模型加人设"
                        } else {
                            settings.systemPrompt.take(24) +
                                if (settings.systemPrompt.length > 24) "…" else ""
                        },
                        icon = AppIcons.Keyboard,
                        onClick = onEditPrompt,
                    )
                }
            }

            // ---------------------------------------------------------- 语音识别
            item(key = "speech_header") { ListSubHeader { Text("语音识别") } }

            item(key = "speech") {
                ActionCard(
                    title = "识别引擎",
                    // ⚠️ 穷尽 `when`：1.982 加回 `AsrEngine.GENERIC` 之后**必须补这一档**，
                    // 否则编译不过（这也正是我们想要的 —— 编译器帮着抓漏）。
                    // 副标题只写「哪一档 + 它的特点」，**不写「不用配置」之类的操作指引**：
                    // 这一页是入口，具体怎么配在二级页里。
                    subtitle = when (settings.asrEngine) {
                        AsrEngine.SYSTEM -> "系统识别 · 不用配置"
                        AsrEngine.GENERIC -> "通用接口 · 自己填地址"
                        AsrEngine.XFYUN -> "讯飞听写 · 边说边出字"
                    },
                    icon = AppIcons.Mic,
                    onClick = onOpenSpeech,
                )
            }

            // ⚠️ 这里原来有一段跟着引擎变的长描述（`speech_hint`，讲「系统识别跑在本地
            // 还是联网由厂商决定」「讯飞是流式听写、等待感最小」），**已删**。
            // 理由：上面那张卡片 `subtitle` 已经把两档的区别说完了（「系统识别 · 不用配置」
            // / 「讯飞听写 · 边说边出字」），再加一段等于把同一件事说两遍，
            // 而且白占圆屏首屏下面本来就紧张的一屏。**别再把它加回来。**

            // -------------------------------------------------------------- 数据
            // 整组只属于 ask：它清的是**一次性提问**的那些会话。
            //
            // chat 那边是**人** —— 删角色只能一个一个删（通讯录长按 / 角色详情，
            // `HomeScreen` 里的 `deletePersona`）。这里**刻意不给**「一次清空所有联系人」。
            //
            // 整组连标题一起消失，不留一个空的「数据」ListSubHeader ——
            // 光秃秃一个组标题下面什么都没有，比没有更像出了 bug。
            if (!isChatOrigin) {
                item(key = "data_header") { ListSubHeader { Text("数据") } }

                item(key = "clear") {
                    // ⚠️⚠️ 1.982：`height` + `contentPadding` 纵向 0 **两件事缺一不可**，
                    // 理由（`Card` 自带的 `defaultMinSize(64.dp)` 陷阱）见
                    // [SINGLE_LINE_CARD_HEIGHT] 那段长注释。少给任何一样都会出事：
                    //  * 只给 `height` 不给 `contentPadding` ⇒ 纵向 24dp 内边距吃掉内容，
                    //    `Text` 量成 0 高、**整张卡画不出来**；
                    //  * 只给 `contentPadding` 不给 `height` ⇒ `defaultMinSize` 照样撑到 64dp，
                    //    用户抱怨的「一行文字两行高度」一点没改。
                    Card(
                        onClick = { confirmClear = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(SINGLE_LINE_CARD_HEIGHT),
                        contentPadding = SINGLE_LINE_CARD_PADDING,
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer,
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    ) {
                        // ⚠️⚠️ 1.982 补：**必须外面套一个撑满的 `Box` 并给 `contentAlignment`**，
                        // 光靠 `Text` 自己是垂直居中不了的。
                        //
                        // 第一版只给了 `Text(modifier = fillMaxWidth(), textAlign = Center)`，
                        // 实测 **上空隙 0px / 下空隙 53px** —— 文字贴在卡片上沿。
                        // 根因：纵向 `contentPadding` 被压到 0 之后，`Card` 内部那个
                        // `Column`（默认 `Arrangement.Top`）里就再没有东西把文字往下推，
                        // 多出来的高度**全落在文字下面**。
                        // **「卡片矮了」和「文字在卡片里居中」是两件事** —— 我上一轮只验了前者。
                        //
                        // 这里不用 `Text(fillMaxHeight)`：那只是把 `Text` 的**布局框**撑高，
                        // 文字在框内落在哪儿是 `BasicText` 内部的事，不该靠猜。
                        // 套 `Box` + `contentAlignment` 是**几何上确定**的居中。
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                // 空白的新对话不算记录，条数里也不该有它们
                                text = "清空对话 · 共 ${conversations.count { it.messages.isNotEmpty() }} 条",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Center,
                                // ⚠️ 1.978：卡片文案里去掉了「所有」两个字（原「清空所有对话 · 共 N 条」），
                                // 并补上 `maxLines = 1` + Ellipsis。
                                //
                                // 原因：这个卡片的可用宽只有 214dp 出头，`titleMedium` 下
                                // 「清空所有对话 · 共 999 条」已经超过一行 —— 而 Text 没有 maxLines
                                // 时**只会换行不会裁字**，于是卡片凭空长高一行，
                                // 把「数据」这一组整块往下顶，圆屏下沿开始切东西。
                                // 三位数条数是日常状态（不是极端值），不能靠「用户不会存那么多」搪塞。
                                //
                                // 两道一起上：改短文案（正常情况根本不触发截断）+ maxLines 兜底
                                // （真出现 5 位数也不会把版式顶歪）。**别再退回长文案。**
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            // ---------------------------------------------------------- 显示设置
            // ⚠️ 入口**暂时隐藏**（见 [SHOW_DISPLAY_SETTINGS_ENTRY]）。
            // 下面这套约定全部仍然有效，恢复入口时照做 —— 别当它是过时代码删掉。
            //
            // ⚠️ **显示设置是一个独立类别**，
            // 不是「挨着语音识别那张卡往下排」。原话：「我说放在语音识别下方
            // 不是挨着放啊。是有空隙排布，当作一个新的类别而不是放在一起。」
            //
            // 所以这里要三样：
            //  1. 顶部一个大空隙（`GapItem`）—— 和上一组拉开距离，视觉上断开；
            //  2. 一个组标题 `ListSubHeader("显示")` —— 明确它是一个新类别；
            //  3. 卡片本体。
            //
            // ⚠️ 组标题叫 **「显示」**、卡片叫 **「显示设置」**（**不同名**）。
            // 最初两处都写「显示设置」，后来反馈「视觉上略重复」⇒ 标题精简成
            // 「显示」（类别名），卡片保留「显示设置」（入口名）。**别再写成同名。**
            //
            // ⚠️ 空隙必须**在组标题之上**（不是卡片和标题之间）—— 要断开的是「上一组」
            // 和「本组标题」之间，标题和它的卡片是一体的，中间不能有空隙。
            //
            // **不随 [origin] 分叉**：气泡时间、两处用量显示都是全局观感，
            // 从哪一页进来都该看到、也都能改。分叉了反而会让人以为
            // 「这一页关掉、那一页还开着」—— 那三个值各只有一份。
            //
            // 更早这里是一个 `SwitchButton`（`showTokenUsage`）+ 上面的组标题。
            // 现在开关拆成三个（气泡时间 / 对话中的用量 / 角色列表用量），
            // 一个开关能直接摆在列表里，三个摆不下 —— 于是整组收进二级页。
            if (SHOW_DISPLAY_SETTINGS_ENTRY) {
                item(key = "display_gap") { GapItem(DISPLAY_GROUP_GAP) }
                item(key = "display_header") { ListSubHeader { Text("显示") } }
                item(key = "display") {
                    ActionCard(
                        title = "显示设置",
                        subtitle = "气泡时间 · 用量显示",
                        icon = AppIcons.Bubble,
                        onClick = onOpenDisplay,
                    )
                }
            }

            // ⚠️ 1.978 第二轮：原来这里还有一个 `ListSubHeader { Text("关于") }` 分组标题，
            // 和卡片标题重复（会显示成「关于 / 关于 WhaleChat」），已删。
            // 这一组只有这一张卡，卡片自己写着「关于 WhaleChat」，分组标题纯属多余。
            //
            // ⚠️⚠️ **1.982 用户反馈：「设置页底下清空对话的选项和关于靠太近了」** ——
            // 这就是上面那个组标题被删掉留下的**副作用**：
            // 标题一走，清空卡和关于卡之间就什么都不剩了，
            // 而 `ScalingLazyColumn` 的默认项间距只有 4dp（`spacedBy(4.dp)`）。
            //
            // 修法是**把「这一组要断开」这件事交回给空隙本身**（而不是把组标题加回来 ——
            // 那个是 1.978 特意删的，见上一段，**别顺手恢复**）。
            //
            // 取值 [ABOUT_GROUP_GAP] = 16dp，**和 [DISPLAY_GROUP_GAP] 一致**，
            // 理由是同一个：「这两处是不同类别，要断开」。组内项间距仍是列表默认的 4dp。
            // 为什么不取更大：这一页末尾还有 `bottom_gap`(28dp)，
            // 两处加起来在圆屏底部已经够空；16dp 刚好够断开、又不会把「关于」推出首屏。
            item(key = "about_gap") { GapItem(ABOUT_GROUP_GAP) }

            // 1.978：「关于」从一段三行的 `HintText` 换成**可点卡片**，点进二级页
            // （`AboutScreen`）。位置没变 —— 还在「数据」组下面、「关于」标题底下那一格。
            //
            // ⚠️ 1.978 第二轮：**副标题删掉**（用户要求「下面的说明文字删去，就只有标题」）。
            // 卡片上只剩标题「关于 WhaleChat」一行 —— 副标题原本写的是
            // 「软件介绍 · 使用说明 · 开发人员」，属于「先替用户概括一遍里面有什么」，
            // 而里面具体是什么用户自己会改（他自己的原话），概括反而是多余的噪音。
            item(key = "about") {
                ActionCard(
                    title = "关于 WhaleChat",
                    // 图标复用 AppIcons 里已有的对话气泡。
                    // ⚠️ 它和「显示设置」入口那张卡是同一个图标 —— 那个入口当前**是隐藏的**
                    // （`SHOW_DISPLAY_SETTINGS_ENTRY = false`），所以列表上看不到重复。
                    // 哪天把那个入口放回来，这两张卡要有一个换图标，否则上下两张同图标会认不出。
                    icon = AppIcons.Bubble,
                    onClick = onOpenAbout,
                    // ⚠️ 1.982：**两张单行卡一起压到同一高度**。
                    // 用户只点了「清空对话」那张，但这两张卡现在**本来就一样高**
                    // （都吃 `defaultMinSize(64.dp)`），只压一张会让下面这张莫名高出一截，
                    // 看起来像没对齐 —— 比两张都高更难看。所以一起压，理由写在
                    // [SINGLE_LINE_CARD_HEIGHT] 上。
                    //
                    // ⚠️ `AboutScreen` 里那四块 `AboutSection` 卡**不要动**：
                    // 它们是多行正文，64dp 对它们是合理的；用户说的是**设置页这张入口卡**。
                    modifier = Modifier.height(SINGLE_LINE_CARD_HEIGHT),
                    contentPadding = SINGLE_LINE_CARD_PADDING,
                )
            }

            item(key = "bottom_gap") { GapItem(28.dp) }
        }
    }

    if (confirmClear) {
        WhaleChatDialog(
            title = "清空所有对话？",
            body = "记录会从手表上删掉，无法恢复。",
            confirmLabel = "全部清空",
            destructive = true,
            onConfirm = {
                ChatEngine.clearAll()
                confirmClear = false
            },
            onDismiss = { confirmClear = false },
        )
    }
}

/**
 * 「账户余额」那张卡。
 *
 * 点一下就去 `GET /user/balance` 查一次并缓存。**不进首页自动查** —— 那会拖慢启动，
 * 手表网络本来就不稳；手动点、显示缓存值 + 更新时间，是这一档该有的分寸。
 *
 * 三态：
 *  * 没查过 → 「点这里查余额」；
 *  * 查过、有缓存 → 「¥110.00 · 3 分钟前更新」；
 *  * 查询中 → 「正在查…」；失败 → 显示错误（**不清掉旧缓存**）。
 *
 * 失败不清缓存是刻意的：用户只是这次没网，不是余额真变了；清成 0 或空串等于把
 * 上一次的正确答案抹掉，下次还得重查一遍。
 */
@Composable
private fun BalanceCard() {
    val usage by ChatEngine.usage.state.collectAsStateWithLifecycle()
    val settings by ChatEngine.settings.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val subtitle = when {
        busy -> "正在查…"
        error != null -> error
        usage.lastBalance.isNotBlank() -> {
            val symbol = if (usage.balanceCurrency.equals("CNY", ignoreCase = true)) "¥"
            else "${usage.balanceCurrency} "
            val ago = relativeTime(usage.balanceFetchedAt)
            "$symbol${usage.lastBalance} · ${ago}更新"
        }
        else -> "点这里查余额"
    }

    ActionCard(
        title = "账户余额",
        subtitle = subtitle,
        icon = AppIcons.Sparkle,
        onClick = {
            if (busy) return@ActionCard
            busy = true
            error = null
            scope.launch {
                error = try {
                    ChatEngine.usage.refresh(settings.apiKey)
                    null
                } catch (e: DeepSeekException) {
                    e.hint
                } catch (e: Exception) {
                    "查询失败"
                }
                busy = false
            }
        },
    )
}

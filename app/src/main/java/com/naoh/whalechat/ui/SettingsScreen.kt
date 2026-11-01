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
 * 设置页底部那两张单行入口卡（「清空对话」「关于 WhaleChat」）—— **2.2 回到 M3E 标准形态**。
 *
 * ## 这次改的是「和上面那组不是一套语言」，不是回退 1.982
 *
 * 1.982 因为用户原话「明明一行文字选项卡片却是两行高度」，把这两张卡从 `Card` 默认的
 * 64dp 压到了 **48dp**，并且「清空对话」是**居中**的一行字、**没有图标**。
 * 那一版的手感是对的，但它和上面那组 `ActionCard`（图标 + 左对齐标题）成了两套语言：
 * 高度不同、有没有图标不同、对齐不同 —— 这正是 2.2 用户说的
 * 「**实在不与上面的 m3e 设计贴近**」。
 *
 * ⇒ 本轮是**有意的第二次调整**，两件事要一起满足：
 *  1. 不能回到「一行文字两行高度」；
 *  2. 要和上面那组 M3E 卡片是同一套语言。
 *
 * ## 做法：两张卡都用 `ActionCard`，**不再手写 Card + Box + 居中 Text**
 *
 * `ActionCard`（`Components.kt`）本来就带着「图标 + 左对齐标题 + `Box(fillMaxSize,
 * CenterStart)` 垂直居中」，20 多个调用点都在用它 —— 复用它而不是再写一遍，
 * 是「同一套语言」最省事、也最不可能分叉的做法。
 *
 * ⚠️ **删掉了 `SINGLE_LINE_CARD_HEIGHT` / `SINGLE_LINE_CARD_PADDING` 两个常量**
 * （连同原来解释「为什么是 48dp」的那一大段注释）：那段注释解释的前提已经不存在，
 * 留着它就是一段**撒谎的注释**。现在高度由 `Card` 自己的
 * `defaultMinSize(minHeight = CardTokens.ContainerMinHeight = 64.dp)` 决定
 * —— 就是 M3E 的卡片标准高度，也就是上面那组卡片的高度，不用再写 `Modifier.height`。
 *
 * ⚠️ **仍然是「一行文字」，不是「两行高度」**：卡片里只有一行 `titleMedium`，
 * 多出来的 64 − 行盒(≈22dp) − 上下内边距(12+12dp) ≈ 18dp 由 `ActionCard` 内部的
 * `Box(CenterStart)` **上下均分**（实测见报告：上空隙 ≈ 下空隙）。
 * 这也正是 1.982 手写 `Box(Center)` 想解决的事，现在由组件统一负责。
 *
 * ⚠️ 破坏性语义不变：**「清空对话」的图标和文字是 error 色，但容器仍是
 * `surfaceContainer`**（不整块刷红）—— 与长按菜单里的破坏性项同一套判断。
 *
 * ⚠️ **「共 N 条」必须保留**：它是用户判断「要不要清」的唯一依据，
 * 所以卡片的 `title` 还是「清空对话 · 共 N 条」，并且 `titleMaxLines = 1` + Ellipsis
 * （1.978 实测过：可用宽约 214dp，三位数条数就会换行，换行就把整组顶下去）。
 */

/**
 * 设置页这两张单行卡的显式高度 = `CardTokens.ContainerMinHeight` 的 64dp（M3E 卡片标准高度）。
 *
 * ⚠️ **为什么明明「不需要写高度」还是要写**（2.2 实测踩到的）：
 * 不写 `Modifier.height` 时，`Card` 的 `defaultMinSize(64.dp)` 只是把**卡片最终尺寸**
 * 抬到 64dp，**没有把 64dp 作为最小约束下发给内容** —— 卡片内部 `ActionCard` 那个
 * `Box(Modifier.fillMaxSize(), contentAlignment = CenterStart)` 拿到的高度是内容自然高
 * （一行文字 ≈ 49px），`fillMaxSize` 撑不满、居中无处可移。
 * 实测（改前）：卡片 `y=[39,173]`、文字 `y=[64,113]` ⇒ **上空隙 25px / 下空隙 60px**，
 * 文字明显贴上沿。给了显式 `height(64.dp)` 之后高度变成有界约束，`Box` 才真的居中
 * （实测上下空隙均 33px，见报告）。
 *
 * ⚠️ 64dp 是 **M3E 卡片标准高度**，不是「两行文字的高度」—— 卡片里只有一行字，
 * 这正是 4.4.2 要求的判据。
 */
private val SETTINGS_CARD_HEIGHT = 64.dp


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

            // ★ 主动消息开关（2.35）：**角色聊天专属**，所以只在从 chat 进来时出现。
            // 理由和「系统提示词只在 ask 出现」是同一条：这一页两个来源本来就同构不同内容，
            // 把只对角色会话生效的开关摆在 ask 那份里，等于暗示它管 Ask。
            //
            // ⚠️ 文案：标题只放「她会主动开口」，副标题说清**什么时候**会开口。
            // 不要再写「每天最多两次 / 只在 7–22 点」这类规则说明 —— 圆屏放不下，
            // 而且那是实现细节，用户只要知道「隔久了会先说一句」。
            if (isChatOrigin) {
                item(key = "proactive_header") { ListSubHeader { Text("角色聊天") } }
                item(key = "proactive") {
                    SwitchButton(
                        checked = settings.proactiveEnabled,
                        onCheckedChange = { on -> ChatEngine.settings.setProactiveEnabled(on) },
                        modifier = Modifier.fillMaxWidth(),
                        label = {
                            Text(
                                text = "她会主动开口",
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        },
                        secondaryLabel = {
                            Text(
                                text = "打开会话时，隔得久了让她先说一句",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    )
                }
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
                    // ⚠️ 2.2：**不再手写 `Card` + `Box(Center)` + 居中 `Text`** ——
                    // 那张卡的形态（居中、无图标、48dp）就是用户说的「不与上面的 m3e 设计贴近」。
                    // 现在复用上面那组一模一样的 `ActionCard`：
                    // 图标 + **左对齐**标题 + 垂直居中，高度吃 `Card` 自己的 64dp 标准高度。
                    // 破坏性语义靠 `contentColor = error`（灰容器 + error 图标文字，
                    // 不整块刷红，和长按菜单里的破坏性项同一套判断）。
                    ActionCard(
                        title = "清空对话 · 共 " +
                            "${conversations.count { it.messages.isNotEmpty() }} 条",
                        icon = AppIcons.Delete,
                        onClick = { confirmClear = true },
                        contentColor = MaterialTheme.colorScheme.error,
                        // 「共 N 条」必须保留（用户判断要不要清的唯一依据），
                        // 但只能占一行：三位数条数在 214dp 可用宽里就会换行，
                        // 一换行整张卡长高、把这一组往下顶（1.978 实测）。
                        titleMaxLines = 1,
                        // 64dp（M3E 卡片标准）+ Box 垂直居中，理由见 [SETTINGS_CARD_HEIGHT]。
                        modifier = Modifier.height(SETTINGS_CARD_HEIGHT),
                    )
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
                    // ⚠️ 2.2：**不再压到 48dp**。这张卡和上面「清空对话」一起回到
                    // `Card` 的 M3E 标准高度（64dp），也就是上面那组 `ActionCard` 的高度 ——
                    // 这正是「和上面的 m3e 设计贴近」那条要求。
                    // 原来那对 `height(48.dp)` + 纵向 0 的 contentPadding 已删，
                    // 理由见文件上方那段「2.2 回到 M3E 标准形态」的注释。
                    //
                    // ⚠️ 显式高度仍然要给，但目的是**让纵向居中生效**（有界高度），
                    // 不是为了「压矮」——见 [SETTINGS_CARD_HEIGHT]。
                    //
                    // ⚠️ `AboutScreen` 里那四块 `AboutSection` 卡**不要动**：
                    // 它们是多行正文，64dp 对它们是合理的；用户说的是**设置页这张入口卡**。
                    modifier = Modifier.height(SETTINGS_CARD_HEIGHT),
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

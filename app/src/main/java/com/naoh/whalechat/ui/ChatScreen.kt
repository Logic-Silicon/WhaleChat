package com.naoh.whalechat.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.ButtonGroup
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CardDefaults
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.ScrollIndicator
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TextButton
import com.naoh.whalechat.data.ChatEngine
import com.naoh.whalechat.data.ChatMessage
import com.naoh.whalechat.data.ChatRole
import com.naoh.whalechat.voice.AudioRecorder
import com.naoh.whalechat.voice.VoicePhase
import com.naoh.whalechat.voice.rememberVoiceInput
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 底部操作区的宽度。
 *
 * 这块屏 240dp 见方、圆半径 120dp，越靠下越窄：按钮行滚到底时大致落在
 * 圆心下方 55~95dp，该处可用半宽 = sqrt(120² - dy²)，dy=95 时只剩约 73dp。
 * 取 168dp（含底部 48dp 留白后实际落在 dy≈70~90）能完整落在圆内，
 * 再往后加宽就会被圆边切角。
 */
private val ACTION_ROW_WIDTH = 168.dp

/**
 * 底部留白。
 *
 * 它是列表的**最后一个 item**，也就是滚到底时屏幕最下面那块空白，
 * 排在 `usage`（用量文字）**下面**。
 *
 * ⚠️ **它不决定「用量文字离输入胶囊多远」。** 实测（模拟器 480×480）：
 * 48dp 与 44dp 两种取值下，「胶囊底 → 文字顶」的间隙**都是 16px**，
 * 文字与胶囊之间的相对距离完全没变 —— 变的只是整块内容在屏上的绝对位置
 * （44dp 时整块往下沉 8px，离圆屏下沿更近）。
 *
 * 所以它**不是**「下边界」那件事的杠杆。用户说的「下边界有点点过了」
 * 靠 [UsageFooter] 上面的间隔来解（见那边的注释）。这里保持 48dp 不动。
 */
/**
 * 列表尾部留白。
 *
 * 1.978 第五轮：**48dp → 10dp**。底部现在由那颗 M3E 贴边返回按钮占着
 * （`ScreenScaffold` 的 `edgeButton` 槽），而 `BOTTOM_GAP` 是给「没有底部按钮」时
 * 留的尾部呼吸 —— 两份留白叠在一起，聊天页中段就空出一大片（实测 149px）。
 * 减掉之后内容变短、列表少滚一点，**顶部那条 `top_gap` 也就露出来了**
 * （用户要的「上边距和下边距协调」是同一件事的两面）。
 *
 * ⚠️ 别再往回调大：它排在 `usage` **下面**，是「用量那行」与屏幕底边之间唯一的分隔；
 * 调回 48dp 的话那个分隔会被贴边按钮挤没。
 */
private val BOTTOM_GAP = 10.dp

/**
 * 用量文字与它上面那颗「输入胶囊」之间的额外空隙。
 *
 * 这是「下边界有点点过了」那件事的**唯一有效杠杆**（见 `usage_gap` 那边的长注释）：
 * 两者本来就只隔一个列表行距，实测 16px（8dp），在 240dp 屏上像贴着。
 * 补 10dp → 实测间隙约 36px（18dp），文字从胶囊底下「浮」出来，
 * 又不会把文字推出圆屏安全区。
 */
private val USAGE_TOP_GAP = 10.dp

/**
 * 底部两颗按钮之间的间隙。
 *
 * 交给 ButtonGroup 的 `spacing`，不再自己写 `Arrangement.spacedBy` ——
 * 宽度分配是容器统一算的，间隙也得用同一个数，否则按下时变宽的那一边会把间隙算歪。
 */
private val ACTION_GAP = 4.dp

/**
 * 进对话页时，「最近一条回答」这张卡的**上沿**该落在屏幕顶部往下多少（dp）。
 *
 * ## 为什么是 66dp（340dpi 下 ≈140px）
 *
 * 圆屏 480px、半径 240、圆心 (240,240)。宽 `W` 的元素整条落进圆的纵向区间是
 * `y ∈ [240 ± sqrt(240² − (W/2)²)]` —— **越宽越窄**。
 * 消息卡是 `fillMaxWidth` 的：这屏的可用宽实测 **428px**（`[26,454]`），
 * 代入得 `y ∈ [131, 349]`。所以气泡上沿必须 **≥131px**，否则左右两侧会被圆边切掉
 * —— 这正是用户截图里「文字块左右都被圆屏边缘裁掉」的几何原因。
 *
 * 取 **145px**（68dp）：比下界高 14px、留得出安全边；又远在时间胶囊（墨迹底约 36px）
 * 之下；而且 140px 在 `[131,349]` 的中段偏上，正好是「回答开头就在那儿」的观感。
 *
 * ⚠️ 这个数是**目标值**，实现上并不能直接给 `scrollOffset` —— 语义见下面 effect
 * 里那段推导（`scrollToItem` 是把**中心**对到视口中心，不是把上沿对到某处）。
 */
private val CHAT_REPLY_TOP = 68.dp

/**
 * 2.382 多气泡的几何常量（**统一在这里调，别散落在各个调用点**）。
 *
 * ⚠️ **上下内边距是刻意不对称的。**
 * 在模拟器（480×480 / 340dpi）上对截图做像素量测：同一颗气泡里，
 * 文字墨迹到气泡上沿 11.8dp、到下沿 10.4dp —— 字体本身的 ascent 比 descent 大，
 * 内边距给成一样的 7/7 时，**肉眼看到的就是「文字偏下、下边挤」**。
 * 所以上收 1dp、下加 1dp（6 / 8），实测两边的**视觉间距相等**（±0.5dp 内）。
 * 改字号 / 换字体后要重新截图量一次，别沿用。
 *
 * 其余三个数一起看：
 *  * 段间距 [BUBBLE_GAP] 必须明显大于内边距的一半，否则相邻两颗气泡看起来像一张卡被切开；
 *  * 名字行与第一颗气泡之间留 [BUBBLE_LABEL_GAP]，不要用 Card 的内边距兼职。
 */
private val BUBBLE_GAP = 6.dp
private val BUBBLE_PAD_H = 12.dp
private val BUBBLE_PAD_TOP = 6.dp
private val BUBBLE_PAD_BOTTOM = 8.dp
private val BUBBLE_LABEL_START = 4.dp
private val BUBBLE_LABEL_GAP = 4.dp



@Composable
fun ChatScreen(
    conversationId: String,
    onBack: () -> Unit,
    /**
     * 打开文字输入页。
     *
     * [draft] 是带过去待改的初始文字（语音识别结果，无则空串）。
     * [rewriteIndex] 非 null 表示这次是「编辑重发」：改完发送时要作废第 index 条
     * 用户消息及其之后的全部内容（见 ChatEngine.rewrite）。2.1 起**任意一条**用户
     * 消息都能进这里 —— 后果警告（后面会删掉几条）在 ChatScreen 的弹窗里先给过。
     */
    onOpenTextInput: (draft: String, rewriteIndex: Int?) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val conversations by ChatEngine.conversations.collectAsStateWithLifecycle()
    val streamingId by ChatEngine.streamingId.collectAsStateWithLifecycle()
    val settings by ChatEngine.settings.state.collectAsStateWithLifecycle()

    val conversation = conversations.firstOrNull { it.id == conversationId }
    val streaming = streamingId == conversationId
    val messages = conversation?.messages.orEmpty()

    /**
     * 助手气泡那一行身份标签写什么。
     *
     * 角色会话里写**角色名**（「周野」），普通会话里照旧写「DeepSeek」。
     *
     * 这正是「每条气泡各自带身份」的落点 —— 所以**聊天页顶部不需要常驻角色名**：
     * 屏幕上每一句 TA 说的话上面都已经写着是谁说的了。
     *
     * 角色被删掉之后这里会退回「DeepSeek」，看着像串了。但那种情况本来就会把
     * 这一页送走（见下面的 `gone`），用户看不到那一帧。
     */
    val assistantLabel = conversation?.personaId
        ?.let { ChatEngine.findPersona(it)?.name }
        ?.takeIf { it.isNotBlank() }
        ?: "DeepSeek"

    // 2.383：多气泡只给角色会话。Ask 是「一问一答」，显示保持 2.35 的原样
    //（一张卡 + 「DeepSeek」标签），见 MessageCard 的 `personaChat`。
    val personaChat = conversation?.personaId != null

    /**
     * 会话真的被删掉时，**自己退回上一页**，不停在这块空屏上。
     *
     * 以前这里显示「这条对话已经不在了」然后就没了下文 —— 那是个死页面：
     * 两个按钮都发不出去（没有会话可发），用户唯一能做的只有返回，可屏幕上
     * 什么都没提示他这一点，看起来就是「卡住了」。
     *
     * **判据是「被明确删过、且那时已经有过 DS 回复」，不是「列表里找不到」。**
     * 这一条是踩出来的：「找不到」至少有三条原因，只有一条该说话 ——
     *  1. 被删了 / 被「清空所有对话」清了，而且那时它已经是条真对话 → 该提示、该退回去；
     *  2. 刚点「＋」进来的空壳（DS 还没回过话）→ 用户进打字页一个字没打就退出，
     *     或从这儿绕去设置里清了个空，回到这条空白会话时它当然不在列表里 ——
     *     这时候弹一句「这个对话已经不在了」纯属胡说，因为**这个对话根本还没建立**。
     *     规矩就是这个：**有 DS 回复才算一条新对话**；
     *  3. **重建空窗期** —— 最阴的一条：Wear 的边缘滑动返回（SwipeDismissableNavHost）
     *     会让这一页短暂重建，重建那一瞬间 `conversations` 还没读回来、列表是空的，
     *     于是「找不到」成立，红卡片就从首页中间劈下来。用户只是正常返回了一次
     *     （用户实测的触发动作是「往右滑想返回上一页、又中途往左滑回打字页」），
     *     什么都没做错。
     *
     * 所以换成 `ChatEngine.isGone()`：**要正面证据（id 进过删除名单），不要缺席证据。**
     * 而删除名单本身也按 `hasReply` 闸过一道，第 2 类连进去的机会都没有。
     *
     * 代价说清楚：进程被回收、导航栈恢复出一个从没落过盘的假 id 时，这一页会停在
     * 空白状态而不是自动退回。这是可接受的 —— 那种情况下用户看到的就是一个空会话，
     * 和刚点＋进来长得一样，返回一下就走了；比每个正常返回的人都劈一张红卡片好得多。
     *
     * 留一句提示给首页：用户被弹回列表总得知道为什么，否则只会更困惑。
     *
     * key 用「该不该走」这个布尔而不是 conversation 本身：流式输出时
     * conversation 每个 token 都换一个新实例，拿它做 key 等于每一片都重建一次
     * 这个 effect。布尔只在真的该走那一次翻转。
     */
    val gone = ChatEngine.isGone(conversationId)
    LaunchedEffect(gone) {
        if (gone) {
            ChatEngine.postNotice("这个对话已经不在了")
            onBack()
        }
    }
    var hint by remember { mutableStateOf<String?>(null) }

    // ---------------------------------------------------- 主动消息（2.35）
    //
    // **只在进入会话时调一次**：key 用 `conversationId`，同一页重组不会重复触发。
    // 从输入页 / 设置页返回也算「重新进入」—— 这正是想要的（那时可能已经隔了很久）。
    //
    // ⚠️ 不要把这里的 key 换成 `messages.size` 或 `conversation`：
    //  * 用 messages.size：她插完 3 条会把 size 推大，effect 重跑，再插一次 → 刷屏；
    //  * 用 conversation：流式时每个 token 都换新实例，等于每片都重跑一次。
    //
    // 判定（隔了多久 / 几点 / 今天第几次）全在 ChatEngine.onChatOpened 里，
    // 这一行只负责说「打开了」。
    LaunchedEffect(conversationId) {
        ChatEngine.onChatOpened(conversationId)
    }

    /** 用户长按了某条提问、警告确认后要改的，值是那条消息的下标。 */
    var pendingRewrite by remember { mutableStateOf<Int?>(null) }

    /**
     * 长按唤出的消息菜单：值为被长按那条消息的**下标**（不记 message 对象 ——
     * 列表 item 的 key 是 `"m$index-${timestamp}"`，用下标才能和列表对上）。
     * 2.1 重做：所有用户消息和助手消息都能长按唤菜单，不再直接执行动作。
     */
    var menuFor by remember { mutableStateOf<Int?>(null) }

    /**
     * 「重新生成」的警告确认状态：值为待重新生成那条助手消息的下标。
     * 只有「后面还有内容」的情形才弹警告（后面什么都没有时直接执行，不弹）。
     */
    var pendingRegenerate by remember { mutableStateOf<Int?>(null) }

    /**
     * 「删除此信息」的警告确认状态：值恒为最后一条用户消息的下标。
     */
    var pendingDeleteExchange by remember { mutableStateOf<Int?>(null) }

    // ⚠️ 流式一开始就自动关掉菜单/警告弹窗：菜单挂在某个下标上，而流式期间
    // 列表内容还在变（那个下标指向的消息随时可能被替换或消失），
    // 菜单开着等于悬在一个正在变的列表上。
    LaunchedEffect(streaming) {
        if (streaming) {
            menuFor = null
            pendingRegenerate = null
            pendingDeleteExchange = null
        }
    }

    /**
     * 复制成功后的短暂反馈。
     *
     * ⚠️ **刻意不复用列表里的 `hint`**：那个提示自带一个「出现就滚到底」的 effect，
     * 复制完把用户从正在读的地方拽走，比不给反馈还糟。所以复制反馈做成**覆盖层**，
     * 不进列表、不碰滚动位置。
     *
     * ⚠️ **2.1 重做观感**（用户对旧版的原话：「它做的复制文本功能就很垃」）：
     * 旧版是一张居中的 `secondaryContainer` 卡片，糊在屏幕正中 —— 菜单刚关、
     * 视线还在那条消息上，突然中间糊上一块卡片，还盖住了正在读的内容。
     * 新版改成**顶部时间胶囊下方的一行小字**（覆盖层，不进列表）：
     * 不遮挡正文、不移动列表、颜色压暗不抢眼。
     *
     * 手表没有系统级的剪贴板提示（Toast 在 Wear 上时灵时不灵），只能自己说一声。
     */
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            // 2.2：时长改用两处共用的 `TransientLabelHoldMs`（原来是本页写死的 1400）。
            // ⚠️ 别再写回字面量 —— 「已复制」和模式提示的停留长短必须同源。
            delay(TransientLabelHoldMs)
            copied = false
        }
    }
    val context = LocalContext.current

    /** 把一条消息的正文写进剪贴板。只拿 `content`，不带思维链（思维链是模型的草稿，不是回答）。 */
    fun copyMessage(body: String) {
        val manager = context.getSystemService(ClipboardManager::class.java) ?: return
        manager.setPrimaryClip(ClipData.newPlainText("WhaleChat", body))
        copied = true
    }
    // 引擎侧的提示（「上一条还在回答，先等它说完」「请先填 API Key」）以前只在首页显示，
    // 可触发它们的动作恰恰都发生在聊天页（语音、文字输入页发出的消息），
    // 结果就是用户点了发送却看不到任何反馈 —— 这里也订阅一份。
    val engineNotice by ChatEngine.notice.collectAsStateWithLifecycle()

    val listState = rememberScalingLazyListState()

    /** 进页定位要按像素算落点（[CHAT_REPLY_TOP] 是 dp，`scrollOffset` 是 px）。 */
    val density = LocalDensity.current

    /**
     * 是否让视图自动钉在最后一条。
     *
     * 只在「用户亲手滚动结束、且没停在底部」时置 false，滑回底部再恢复。
     * 这里必须丢掉 snapshotFlow 的首次发射：进页面时列表本来就是可滚的、
     * 又没在底部，直接把初始状态当作用户操作的话 follow 会立刻变 false，
     * 打开旧会话就再也不会定位到底部了。
     */
    var follow by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .drop(1)
            .filter { !it }
            .collect { follow = !listState.canScrollForward }
    }

    // 打开会话时的第一次定位：本 effect 在首个组合帧之后才启动，
    // 那时列表还没测量，totalItemsCount 是 0，直接读会落空。等它变正数再滚。
    //
    // **空会话不滚。** 空会话的列表只有三项：顶部留白、「按下面的按钮问点什么」、那排按钮
    // （还有可能一张「还没填 API Key」的卡片）。滚到底等于把只有一句话的空状态推到屏幕上方
    // ——实测它会被时间胶囊压掉一半（截图里「按下面的按钮问点什么」和 11:20 叠在一起），
    // 用户看到的是一个残废的提示。空会话本来就该老老实实停在顶部。
    //
    // 发第一条消息时不用担心：下面那个 follow effect 会接管（follow 初始为 true），
    // 它按「条数 + 最后一条长度」触发，一样会跟到底。
    //
    // ## ⚠️⚠️ 2.0：定位目标从「列表最底」改成「**最后一条回答**」（用户原话：
    // 「跳转后的页面位置就尽量刚好让最近一个回答位于页面的较上方处，差不多刚好能让
    // 对方名字能显示屏幕 1/3 处，现在进去是在最底下，想看完整最近的回答得滑动」。
    // 后续补充：「大概那个位置就行，以美观和可靠为主，不要全依赖 1/3」）。
    //
    //  * 旧行为 `scrollToItem(count - 1)` 停在**列表最末**（那排按钮/用量行）——
    //    最后一条回答被顶到视口外或贴着底沿，要看它得往上滑。
    //  * 新行为：找到**最后一条 ASSISTANT 消息**的索引，`scrollToItem(它, 向上偏移)`。
    //    `ScalingLazyListState.scrollToItem` 的语义（库源码 ScalingLazyListState.kt）：
    //    「把目标 item 带到**视口中心**，再按 scrollOffset 像素继续偏移，正值向上」⇒
    //    负向偏移把中心抬向上方一点。偏移量取 **-140px**（视口 480 的 ~30%）：
    //    目标行中心落在屏高 ~35% 处，保守取值——偏得再多（如 -170）在消息卡
    //    很矮时会把它顶到时间胶囊底下。以「美观可靠」为先，不精确卡 1/3。
    //  * **可靠性关键（1.993 实测踩了两版才踩对）**：`messages` 是异步加载的，
    //    且它是**普通 val**（重组时重新求值）——**不能在 effect 闭包里读它**
    //    （闭包捕获的是首帧的空列表，snapshotFlow 也不会重算普通 val）。
    //    正确做法是把 `messages.size` 挂进 **effect 的 key**：数据加载完成 ⇒
    //    size 0→N 变化 ⇒ effect 自动重启，此时闭包里的 `messages` 才是全的。
    //  * **只定位一次**：用 `positioned` 旗子挡住后续重启。没有它的话，
    //    流式追加（size 再变）会反复把用户拽回「最后一条回答」，
    //    覆盖流式跟随 effect 的滚动。
    //  * 没有 ASSISTANT 消息（只有用户问了还没回）⇒ 退回旧行为滚到最底，
    //    因为此时「最新内容」就是那排操作按钮。
    //  * 与下面那个「流式跟随」effect 的执行顺序：数据加载完成后两个 effect
    //    都会重启（key 都变了）。重组期 effect 按声明顺序启动 ⇒ **本 effect 先滚**，
    //    跟随 effect 后滚。跟随滚的是 `count-1`（最底）⇒ 会覆盖定位！
    //    ⇒ `positioned` 旗子必须在这里**先置位**，让跟随 effect 那次重启
    //    看到旗子直接 return。
    var positioned by remember { mutableStateOf(false) }
    LaunchedEffect(conversationId, messages.size) {
        if (positioned) return@LaunchedEffect
        if (messages.isEmpty()) return@LaunchedEffect
        val count = listState.layoutInfo.totalItemsCount
        if (count <= 1) return@LaunchedEffect
        positioned = true
        val lastAssistant = messages.indexOfLast { it.role == ChatRole.ASSISTANT }
        if (lastAssistant >= 0) {
            // 消息 items 前面还有 1 个 top_gap ⇒ 列表索引 = 消息下标 + 1。
            //
            // ## ⚠️⚠️ 2.2：为什么不能只给一个 `scrollOffset`
            //
            // `ScalingLazyListState.scrollToItem` 的**真实语义**（1.5.6 源码
            // `ScalingLazyListState.kt:499-519`，默认 `anchorType = ItemCenter`）：
            //
            // ```
            // offset = (beforeContentPadding − 视口中心线) + item.size / 2 + scrollOffset
            // ```
            //
            // 即「把这一项的**中心**放到视口中心，再按 scrollOffset 继续偏移」。
            // 而一条长回答比视口高得多（实测 2000px 左右）⇒ 中心对位只能让它**中段**入屏：
            // 这就是用户截图里「一进来看到的是回答中段、开头被切掉」的根因。
            //
            // 2.1 用 `-40` 想把它再往上抬一点，实际是在「中段」基础上再微调 40px ——
            // 方向对、量级差两个数量级，所以怎么调都是「看到中段」。
            //
            // ## 现在的做法（两步，先量再定位）
            //
            //  1. 先 `scrollToItem(index, 0)` —— 让目标项进视口并**被测量**；
            //  2. 从 `layoutInfo` 读出它自己的高度，反算出「让它的**上沿**落在
            //     [CHAT_REPLY_TOP] 处」需要的偏移量，再 `scrollToItem` 一次。
            //
            // 偏移量推导：中心对位时上沿在 `视口中心 − size/2`；要把它挪到 `target`，
            // 需要把这一项**往下**挪 `(视口中心 − size/2) − target`，
            // 而 scrollOffset 越大项越靠上 ⇒ `scrollOffset = 视口中心 − size/2 − target`。
            // 实测（480px 屏、长回答）：`size ≈ 2050` ⇒ offset ≈ −825，落点顶沿
            // y≈140px，见报告里的 dump/像素数字。
            //
            // ⚠️ 用 `unadjustedSize`（缩放前的布局高度）而不是 `size`（缩放后）：
            // 中心对位的公式里本身就是 `item.size`，但缩放会随位置变化 ——
            // 一轮即可（实测误差 < 2px），别写成循环去追，那会在流式期间反复抖动。
            val targetIndex = lastAssistant + 1
            listState.scrollToItem(index = targetIndex, scrollOffset = 0)
            // ⚠️ **必须等布局把这一项测出来再读 `layoutInfo`**：实测 `scrollToItem` 返回时
            // `visibleItemsInfo` 还是**空列表**（`withFrameNanos` 一次也不够），
            // 此时 `firstOrNull { … }` 拿到 null，后面那次定位就整个被跳过 ——
            // 症状是「改了 offset 但画面一点没变」，极易误判成 offset 值不对。
            // 所以用 snapshotFlow 等它出现，并加 1s 兜底：绝不允许这个 effect 无限挂起。
            val info = withTimeoutOrNull(1_000L) {
                snapshotFlow {
                    listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == targetIndex }
                }.filterNotNull().first()
            }
            if (info != null) {
                val viewport = listState.layoutInfo.viewportSize.height
                val target = with(density) { CHAT_REPLY_TOP.toPx() }
                val offset = (viewport / 2f - info.size / 2f - target).roundToInt()
                listState.scrollToItem(index = targetIndex, scrollOffset = offset)
            }
        } else {
            listState.scrollToItem(count - 1)
        }
    }

    // 流式追加时跟随：以「条数 + 最后一条长度」为触发点
    val lastLength = messages.lastOrNull()?.content?.length ?: 0
    LaunchedEffect(messages.size, lastLength, streaming) {
        // **空会话一个都不跟。** 这条守卫是实测出来的，不是保险：
        // 这个 effect 的 key 在**首次组合**时就已经是稳定的（0/0/false），所以它一进来
        // 就会拿 `follow` 的初始值 true 跑一遍、直接滚到列表末尾 —— 列表末尾是底部留白，
        // 于是只有一句话的空状态被顶到屏幕最上面，正好压在时间胶囊底下（截图里
        // 「按下面的按钮问点什么」和 11:20 叠在一起，第一行根本读不出来）。
        // 空会话没有任何「最后一条」值得跟，直接返回。
        if (messages.isEmpty()) return@LaunchedEffect
        if (!follow) {
            return@LaunchedEffect
        }
        // ⚠️ 2.0：数据加载完成的那次重启（size 0→N）**让位给上面的定位 effect**——
        // 否则它会 `scrollToItem(count-1)` 滚到最底，把「最后一条回答居上」的定位覆盖掉
        //（1.993 实测踩过：定位 effect 先滚、跟随 effect 后滚，结果停在最底）。
        // 流式期间（streaming=true）本 effect 的重启才是真正的「跟随」场景，照常工作。
        if (!streaming && positioned) {
            return@LaunchedEffect
        }
        val count = listState.layoutInfo.totalItemsCount
        if (count > 0) listState.scrollToItem(count - 1)
    }

    // 提示出现时，把它滚进视野。**提示在列表末尾**，所以要滚到底。
    //
    // 提示项以前插在**列表最前面**（消息循环之前，索引 1），于是每次语音识别失败
    // 都要把用户从底部**闪到顶端**去看那条红卡 —— 报错之后还得自己再滑回底部。
    // 现在提示项搬到了消息循环**之后**，紧贴底部，报错不需要移动视线。
    //
    // 搬位置的同时，这里也必须跟着改：原来滚到索引 1，现在要滚到**最后一项**
    // （末尾是底部留白，见 `bottom_gap` —— 滚到留白处正好把提示那一项完整带进视野）。
    //
    // ## 为什么仍然值得主动滚一下
    //
    // 即使提示就在底部附近，LazyColumn 插入新项时以「首个可见项」为锚，列表已经滚在
    // 半途时新插进来的项可能刚好落在视口下方一点点。主动滚到底是最稳的读法，
    // 也顺带保证「提示一定被看见」这件事不依赖用户当前滚到哪。
    //
    // ## 代价（说清楚，别当成 bug）
    //
    // 这次程序化滚动会让上面那段 snapshotFlow 认为「用户滚了」，于是 follow 变成 false。
    // 之后滚的是**底部**（以前是顶部），所以 follow 很快会在下一次
    // 「用户滑回底部」时自己恢复；而且滚到底本来就更接近流式输出的落点。
    // 这是可接受的 —— 有提示的时候本来就更该先看提示。
    val noticeText = hint ?: engineNotice
    LaunchedEffect(noticeText) {
        val count = listState.layoutInfo.totalItemsCount
        if (noticeText != null && count > 0) {
            // 滚到**最后一项**。减 1 是索引补偿：totalItemsCount 是「数量」不是「最大下标」。
            listState.scrollToItem(count - 1)
        }
    }

    /**
     * 对话页的麦克风：**默认说完就发，不中转**；但在没发出去之前留一条退路。
     *
     * 这一档固定说完就发，不跟任何设置走。以前它开着「先编辑」时，识别结果会被直接
     * 带进文字输入页 —— 用户按的是「说话」，结果界面跳走、键盘还弹起来整屏盖住，
     * 动作和预期完全是两回事（真机反馈的原话是「割裂」）。
     *
     * 现在按**动作**分职责：
     *  * 按左边结束录制 → 识别完直接发送，一个字都不多等；
     *  * 录的时候按右边键盘 → 先把这一句正常录完（等最后一片结果，跟发送那次
     *    一样只等一次识别），再把完整文字带进编辑页让用户改。
     *
     * 这样「介绍 deepseek」被听成「deep thick」时，用户不用重说一遍，
     * 也不用等它发出去再想办法 —— 没按发送就还有救。
     */
    val voice = rememberVoiceInput(
        onResult = { spoken -> ChatEngine.send(conversationId, spoken) },
        onMessage = { message -> hint = message },
        onEditResult = { spoken -> onOpenTextInput(spoken, null) },
    )

    // 时间胶囊由外层 AppScaffold 统一提供，这里只用管滚动指示器
    //
    // ⚠️ 1.978 第五轮（用户要求）：底部加 **M3E 的贴边返回按钮**。
    // 用的是 `ScreenScaffold` 的 `edgeButton` 槽（和扫码页那颗「返回」同一个组件），
    // 所以它会**自动把 `contentPadding` 的底部撑开** —— 麦克风/键盘那一行因此被顶到
    // 按钮上方，不用手写 padding。
    //
    // ⚠️ **它的官方语义是「随列表滚动调整大小」** —— 官方 Wear Lists 指南「列表中的边缘按钮」
    // 一节原文：「使用 `edgeButton` slot 可确保按钮正确地贴在屏幕底部，
    // **并在列表滚动时调整大小**」。聊天页默认就停在最底下（进来会 `scrollToItem(count-1)`），
    // 所以正常情况下是满高；把对话往上翻到顶时它会缩掉 —— 这是组件行为，不是没做。
    //
    // ⚠️ **1.981（用户纠正）：不要去「钉死」这个高度。** 我一度用自定义
    // `ScrollInfoProvider` 把 `lastItemOffset` 换成常数，把按钮固定成满高 —— 那是**反设计**的，
    // 用户原话「谁让你把底部返回直接固定的」。已回退成官方的 `scrollState = listState`。
    //
    // 真正漏掉的是**官方示例里那一句 `Modifier.scrollable(...)`**：
    // 不挂它，按钮会把落在它身上的触摸整个吃掉、和列表的滚动状态对不上，
    // 于是 `ScreenScaffold` 的高度在 `snapTo / animateTo` 两个分支之间来回切。
    // 现在五处贴边按钮统一挂 `Modifier.edgeButtonScrollable(listState)`（见它的 KDoc）。
    // 外面这层 Box 只为「已复制」那个覆盖层服务 —— 它必须浮在列表**之上**，
    // 又不许进列表（进了列表就要跟着滚动，还会被 LazyColumn 的高度算进去）。
    Box(modifier = Modifier.fillMaxSize()) {
    ScreenScaffold(
        scrollState = listState,
        edgeButtonSpacing = BackEdgeButtonSpacing,
        scrollIndicator = { ScrollIndicator(state = listState) },
        edgeButton = {
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
            // 1.978 第五轮：20dp → 26dp。用户原话「上边距和下边距不协调了适当加一点点」。
            // 底部现在由那颗贴边返回按钮占着，顶部还是原来那点留白，两边一比就偏紧。
            item(key = "top_gap") { GapItem(26.dp) }

            if (conversation == null) {
                // 到这里都是「看不到内容」但**没被明确删掉**的情况 —— 主要是下面两种，
                // 都不该说话，只留一块留白让页面别跳：
                //  * 刚点＋进来、DS 还没回过话的空壳（`hasReply` 为 false）；
                //  * 这一页正在重建的那一瞬（conversations 还没读回来）。
                // 真被删了的话，上面那个 LaunchedEffect 已经在把这一页送走了。
                item(key = "missing") { GapItem(20.dp) }
                return@ScalingLazyColumn
            }

            // ⚠️ 提示项（`NoticeCard`）**搬到消息循环之后**了，见下面
            // 「item(key = "notice")」那一处。别搬回这里 ——
            // 放在消息前会让每次语音识别失败都「闪到顶端」，用户还得滑回底部。

            if (messages.isEmpty()) {
                item(key = "empty") {
                    Text(
                        text = "还没有消息\n按下面的按钮问点什么",
                        modifier = Modifier.width(ACTION_ROW_WIDTH),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            // **2.1：长按只负责唤出菜单，不再直接执行动作。**
            //
            // 旧版只有「最新一条提问」能长按（直接弹编辑警告）、其余消息长按直接复制 ——
            // 用户 2026-10-24 推翻了这套：「两模式所有对话对话页都可以长按唤出新菜单，
            // 旧菜单删除重做」。现在的规则：
            //  * 用户消息、助手消息**都能长按** → 唤出消息菜单（按角色给不同动作）；
            //  * 流式期间一律不给（内容还在变，复制到的会是半截、菜单也会悬在变动的列表上）；
            //  * 错误卡不给（它有自己的「重试」按钮）；
            //  * 空/空白内容不给（没得复制也没得编辑）。
            messages.forEachIndexed { index, message ->
                item(key = "m$index-${message.timestamp}") {
                    // 长按要震一下（2.2 §1.5：Wear 上没触感＝用户以为没点上）。
                    // 同一个 lambda 给两种角色复用，语义都是「唤出这条消息的菜单」。
                    val openMenu = rememberLongPressHaptic { menuFor = index }
                    val longAction: (() -> Unit)? = when {
                        streaming -> null
                        message.error -> null
                        message.role == ChatRole.USER && message.content.isNotBlank() -> openMenu

                        message.role == ChatRole.ASSISTANT && message.content.isNotBlank() -> openMenu

                        else -> null
                    }
                    MessageCard(
                        message = message,
                        assistantLabel = assistantLabel,
                        // 2.383：只有角色会话拆气泡；Ask 保持原样（见 MessageCard 的注释）。
                        personaChat = personaChat,
                        // 气泡右上角那个时间显不显示，由设置里那个开关决定。
                        // **由调用方传进来**（这一层已经订阅过 settings 了），
                        // 不在 MessageCard 内部再订阅一次 —— 每条消息各订阅一次状态，
                        // 长对话滚动时会掉帧。
                        showBubbleTime = settings.showBubbleTime,
                        onRetry = if (message.error) {
                            { ChatEngine.retry(conversationId) }
                        } else {
                            null
                        },
                        onLongClick = longAction,
                        // 读屏会念出来。现在所有可长按的消息都是「唤出菜单」，
                        // 不再按角色写死成「编辑重发 / 复制」两种。
                        onLongClickLabel = "消息操作",
                    )
                }
            }

            // 本地提示（语音识别失败）优先，其次是引擎提示。
            //
            // ⚠️ **位置在消息循环之后**。原来是列表第 1 项，后果是
            // 每次提示出现都要把用户从底部闪到顶端，然后他自己再滑回底部。
            // 搬到末尾 + 那个 `LaunchedEffect` 滚到底，
            // 提示出现在视线落点附近，不打断阅读。
            //
            // `hint`（语音/本地）和 `engineNotice`（缺 Key 等）**共用这一个 item**，
            // 必须一起搬 —— 只搬一半会让两处提示出现在不同位置。
            (hint ?: engineNotice)?.let { text ->
                item(key = "notice") {
                    NoticeCard(
                        text = text,
                        onDismiss = {
                            hint = null
                            ChatEngine.consumeNotice()
                        },
                    )
                }
            }

            // 只在第一条增量到达之前显示「正在思考…」。
            // 正文或思维链一开始进，消息卡片自己就是进度指示，再叠一张反而乱。
            val pending = streaming && messages.lastOrNull()
                ?.let { it.content.isEmpty() && it.reasoning.isEmpty() } == true
            if (pending) {
                item(key = "thinking") { ThinkingCard() }
            }

            // 录音 / 上传期间给一条明确反馈：状态一行 + 讯飞那条的「边说边出字」。
            //
            // ⚠️⚠️ **1.984：录音电平条已整个删除**（用户原话：「干脆把声音起伏指示条删了，
            // 不仅有延迟还卡，位置也不要做保留」）——
            // 它在真机上每 ~80ms 触发一次这张卡所在 item 的重组+重绘，**持续掉帧**，
            // 而信息量远低于 [VoiceStatusCard.partial] 的「边说边出字」。
            // **连它的常驻占位也一起删了**（那是为断「果冻」回路付的代价，条子没了占位就没理由存在；
            // 回路不会回来 —— 这一条 item 删掉后与用量条互不影响，见 VoiceStatusCard 的 KDoc）。
            if (voice.phase != VoicePhase.IDLE) {
                item(key = "voice") {
                    VoiceStatusCard(
                        phase = voice.phase,
                        partial = voice.partial,
                    )
                }
            }

            item(key = "actions") {
                if (streaming) {
                    Button(
                        onClick = { ChatEngine.stop() },
                        modifier = Modifier.width(ACTION_ROW_WIDTH),
                    ) {
                        Icon(
                            imageVector = AppIcons.Stop,
                            contentDescription = null,
                            modifier = Modifier
                                .size(18.dp)
                                .padding(end = 4.dp),
                        )
                        Text("停止生成", maxLines = 1)
                    }
                } else {
                    // 两个按钮放进 **ButtonGroup** —— Wear M3 里给「一排按钮」准备的组合容器。
                    //
                    // 换掉原来的 Row + weight 不是为了省几行代码，而是因为
                    // `Modifier.animateWidth()` **只在 ButtonGroup 里才有意义**：
                    // 按下时这颗按钮按 ExpansionWidth 变宽、旁边那颗同步变窄，**总宽一点不变**。
                    // 宽度缩到接近高度时，胶囊形状自然就变成一个圆 —— 参考 app 里
                    // 那两个胶囊按下后的形变就是这个，不用自己画动画（是官方容器自带的反馈）。
                    ButtonGroup(
                        modifier = Modifier.width(ACTION_ROW_WIDTH),
                        spacing = ACTION_GAP,
                        // 默认的 fullWidthPaddings() 是给「占满全屏宽」的用法算的
                        // （左右各留屏高的 5.2%）。这里总宽已经按圆屏几何钉死在 168dp 了，
                        // 再吃一遍内缩两颗都会变窄、还往圆屏边沿挤。
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        // 每颗按钮一个独立的 interactionSource：animateWidth 靠它听按压状态。
                        // 共用一颗的话，按左边会把右边也一起撑开。
                        val voicePress = remember { MutableInteractionSource() }
                        val textPress = remember { MutableInteractionSource() }

                        FilledTonalButton(
                            // 一下开始、一下结束。手表上「按住说话」很难按住，
                            // 而且系统手势会跟长按抢事件，点按切换才稳。
                            onClick = {
                                if (voice.phase == VoicePhase.RECORDING) {
                                    voice.stop()
                                } else {
                                    voice.start()
                                }
                            },
                            // 识别中再点没有意义（请求已经发出去了，取消不了它），
                            // 置灰比「能点但没反应」诚实
                            enabled = voice.phase != VoicePhase.TRANSCRIBING,
                            modifier = Modifier.animateWidth(voicePress),
                            interactionSource = voicePress,
                            // 宽度由 ButtonGroup 定，内边距清零，居中交给里面那个 fillMaxWidth。
                            //
                            // 以前是靠一组**对称 padding**（水平 29dp）凑居中的，那个数只在
                            // 「宽度恒为 82dp」时成立 —— 现在按下会变宽/变窄，硬编码的 padding
                            // 立刻就歪了。ButtonGroup 会给每颗按钮一个固定宽度约束，
                            // fillMaxWidth 这才撑得开（Button 内部是 intrinsicSize 宽度的 Row，
                            // 约束不定宽时它只按内容量，那才是以前 fillMaxWidth 失效的原因）。
                            contentPadding = PaddingValues(0.dp),
                        ) {
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = if (voice.phase == VoicePhase.RECORDING) {
                                        AppIcons.Stop
                                    } else {
                                        AppIcons.Mic
                                    },
                                    contentDescription = when (voice.phase) {
                                        VoicePhase.RECORDING -> "结束录音并识别"
                                        VoicePhase.TRANSCRIBING -> "正在识别"
                                        VoicePhase.IDLE -> "语音输入"
                                    },
                                    modifier = Modifier.size(24.dp),
                                )
                            }
                        }
                        Button(
                            onClick = {
                                when (voice.phase) {
                                    // 正录着：先停录、等这一趟识别完，再带着完整文字进编辑页。
                                    //
                                    // 这里必须"先收尾再跳"，不能直接带屏幕上那半句 partial 跳走：
                                    // 跳页会销毁这个页面，DisposableEffect 里那两下（掐录音、
                                    // 取消协程）跟着执行，服务端随后补发的最后一片就永远收不到，
                                    // 用户带过去的是半句话。多等的这一次识别，按左边「结束录制」
                                    // 发送时本来也要等，所以不多花时间。
                                    VoicePhase.RECORDING -> voice.stopToEdit()
                                    // 已经在等结果了：马上就带着字跳过去，再点没有去处
                                    VoicePhase.TRANSCRIBING -> Unit
                                    // 没在录：空框进编辑页，跟以前一样
                                    VoicePhase.IDLE -> onOpenTextInput("", null)
                                }
                            },
                            enabled = voice.phase != VoicePhase.TRANSCRIBING,
                            modifier = Modifier.animateWidth(textPress),
                            interactionSource = textPress,
                            contentPadding = PaddingValues(0.dp),
                        ) {
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = AppIcons.Keyboard,
                                    contentDescription = if (voice.phase == VoicePhase.RECORDING) {
                                        "结束录音，带文字去修改"
                                    } else {
                                        "文字输入"
                                    },
                                    modifier = Modifier.size(24.dp),
                                )
                            }
                        }
                    }
                }
            }

            if (settings.apiKey.isBlank()) {
                item(key = "no_key") {
                    ActionCard(
                        title = "还没填 API Key",
                        subtitle = "点这里去设置",
                        icon = AppIcons.Settings,
                        onClick = onOpenSettings,
                    )
                }
            }

            // 划到最底下才出现的那行用量小字。
            //
            // ⚠️⚠️ **1.983：「果冻」的根因就在下面这几行里**（用户原话：
            // 「底部返回按钮果冻自跳动 bug 没修 chat 页的」）。
            // `visible` 传的是 `!listState.canScrollForward`（滚动状态），
            // 所以 `UsageFooter` **绝不能拿它驱动布局** ——
            // 一驱动布局就是「显隐改变内容高度 → 内容高度又决定 canScrollForward」的自激回路。
            // 修法（只驱动 alpha）与完整机理见 `UsageFooter` 的 KDoc。**别改回去。**
            //
            // ⚠️ **1.980 第二轮（用户要求）：ask 的对话页不再显示这一行。**
            // 用户原话：「把 ask 的对话页的具体 token 用量和命中率显示删掉，难看」。
            // 判据沿用这一页本来就在用的那一条 —— `personaId` 为空 = 一次性问答（ask），
            // 有角色 = 人设会话（chat，见 `WhaleChatApp` 里齿轮那个分叉用同一个判据）。
            // **chat（人设会话）那一侧照旧显示**，`showUsageInChat` 开关的语义不变。
            //
            // 「显示设置」里的**「对话中的用量」**只管这一处。
            // 关掉时是**整行不存在** —— 不是变灰、也不是显示 0：变灰等于
            // 「有数据但我藏起来」，那是在替用户决定什么该看；用户关它就是因为不想看。
            //
            // ⚠️ ask 页这一行删掉之后，**数据仍然照记照存**（`promptTokens` 等键一个都没动），
            // 只是不摆出来。**不要**顺手去删 `ConversationStore` 里的读写 ——
            // 那是「读的改了、写的没跟上」的反向版本，会让 chat 侧也一起没数据。
            val showUsageHere = settings.showUsageInChat && conversation.personaId != null
            val totalTokens = conversation.totalTokens
            if (showUsageHere && totalTokens > 0L) {
                // ★ 这一条空隙就是「下边界」那件事的真正杠杆。
                //
                // 用量文字是列表里的一个 item，紧跟在 `actions`（输入胶囊那颗 item）后面，
                // 两者之间只有列表默认的行距 —— 实测：胶囊底 → 文字顶 **16px（8dp）**，
                // 在 240dp 的屏上看着像被胶囊压着。
                //
                // 为什么不去调 `BOTTOM_GAP`：它排在 `usage` **下面**，跟胶囊之间的
                // 距离一点关系都没有。实测 48dp 与 44dp 两种取值下间隙**都是 16px**，
                // 只是整块内容在屏上的绝对位置变了 —— 改它等于白改（还会把文字往圆屏下沿推）。
                //
                // 加这一条 10dp 空隙，把文字从胶囊底下「拉出来」：
                // 间隙 16px → 约 36px。同时下面的 `BOTTOM_GAP` 兜住尾部留白，
                // 保证文字不会被圆边切到（用户硬约束：「更不要收缩到显示不全 token 消耗量」）。
                item(key = "usage_gap") { GapItem(USAGE_TOP_GAP) }
                item(key = "usage") {
                    // ⚠️⚠️ `visible` 这里传的是**滚动状态**（`!canScrollForward`），
                    // 而 `UsageFooter` 内部**只拿它驱动 alpha、不碰布局** ——
                    // 这两件事必须成对存在，否则就是 1.982 那个自激振荡（「果冻」）。
                    // 详见 `UsageFooter` 的 KDoc：**别把那个函数改回 `AnimatedVisibility`**。
                    //
                    // ⚠️⚠️ 1.987：**命中率那一段整个删掉了**（用户原话：「chat对话页
                    // 命中率删去」）。`UsageFooter.hitRate` 参数与 `usageText()` 里
                    // 拼「· 命中 NN%」的分支一并移除 —— 数据（cacheHit/missTokens）
                    // **照常记录**，只是不再摆出来；别顺手去删 ConversationStore 里的读写。
                    UsageFooter(
                        visible = !listState.canScrollForward,
                        tokens = totalTokens,
                    )
                }
            }

            item(key = "bottom_gap") { GapItem(BOTTOM_GAP) }
        }
    }

        // 复制反馈（2.1 重做，2.2 统一）：**一行小字浮在屏幕上部**，不再是糊在正中的卡片。
        // 设计依据见上面 `copied` 的注释 —— 不遮挡正文、不移动列表、不进列表
        // （进列表会触发「出现就滚到底」的 effect）。
        //
        // ⚠️⚠️ 2.2：这里原来是本页自己写的 `if (copied) Text(...)`（labelSmall /
        // onSurfaceVariant×0.85 / top=30dp / **没有动画** / 1.4s），而首页把模式提示
        // 写成了另一份（labelMedium / 不透明 / top=17.5dp / 有 fade / 2s）。
        // 用户原话「复制文本后的『已复制』提示显示和切换模式的提示文本显示效果统一」
        // ⇒ 两处改用同一个 `TransientLabel`；字号/颜色/落点/动画/时长都在组件里，
        // **本页不再出现第二份样式**。时长由调用方计时（组件保持无状态），
        // 用的是两处共用的 `TransientLabelHoldMs`（2 秒，原来是 1.4 秒）。
        TransientLabel(
            text = "已复制",
            visible = copied,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = TransientLabelTop),
        )
    }

    // ── 消息菜单（2.1 重做）──────────────────────────────────────────────
    //
    // ⚠️ 从菜单项触发动作前**必须重新取一次** `messages.getOrNull(index)`：
    // 菜单开着时列表可能变（比如流式刚结束、列表重建），下标可能已经指向别的消息
    // 或干脆越界 —— 取不到就静默关掉菜单，绝不能拿旧引用动作，更不能崩。
    menuFor?.let { index ->
        val message = messages.getOrNull(index)
        if (message == null || message.error || message.content.isBlank()) {
            // 目标已经不在/不可操作了，安静收场。
            menuFor = null
        } else {
            val isUser = message.role == ChatRole.USER
            // 「删除此信息」只出现在最后一条用户消息上。非最后一条：这一项**干脆不出现**
            // （而不是变灰）—— Wear 上三项已经很挤，多一项灰的既占位置又要额外解释
            // 「为什么不能点」。
            val isLastUser = index == messages.indexOfLast { it.role == ChatRole.USER }
            WhaleChatMenuDialog(
                title = if (isUser) "你的消息" else assistantLabel,
                onDismiss = { menuFor = null },
                actions = buildList {
                    if (isUser) {
                        add(
                            // 键盘图标：编辑重发的动作终点是文字输入页。
                            DialogAction("编辑重发", icon = AppIcons.Keyboard) {
                                menuFor = null
                                pendingRewrite = index
                            },
                        )
                    } else {
                        add(
                            // 星形（Sparkle）：和「生成」相关的动作在别处也用它，
                            // 重新生成是同一族的语义。
                            DialogAction("重新生成", icon = AppIcons.Sparkle) {
                                menuFor = null
                                // 后面还有内容 ⇒ 必须先警告（后果和「编辑重发中间那句」
                                // 完全一样）；就是最后一条 ⇒ 直接重答，不弹窗打扰。
                                if (messages.size - index - 1 > 0) {
                                    pendingRegenerate = index
                                } else {
                                    ChatEngine.regenerate(conversationId, index)
                                }
                            },
                        )
                    }
                    add(
                        // 气泡：复制的是一段话，用对话气泡比抽象的「复制」符号好认
                        // （AppIcons 没有现成的 copy 图标，宁可用贴切的、不硬凑）。
                        DialogAction("复制文本", icon = AppIcons.Bubble) {
                            menuFor = null
                            copyMessage(message.content)
                        },
                    )
                    if (isUser && isLastUser) {
                        add(
                            DialogAction("删除此信息", destructive = true, icon = AppIcons.Delete) {
                                menuFor = null
                                pendingDeleteExchange = index
                            },
                        )
                    }
                },
            )
        }
    }

    // ── 编辑重发警告 ────────────────────────────────────────────────────
    // 原文一并摆出来：用户得先看清自己要改的是哪句（弹窗是全屏的，背后那条气泡会被盖住）。
    // N = 那条消息后面还有几条（真实条数，不许写成模糊的「后面的内容」）。
    pendingRewrite?.let { index ->
        val original = messages.getOrNull(index)?.content.orEmpty()
        val dropped = messages.size - index - 1
        WhaleChatDialog(
            title = "改一下这句？",
            body = if (dropped == 0) {
                "改完发送，就用新的问法重新答一遍。"
            } else {
                "改完发送，后面 $dropped 条内容会一起删掉，再重新答。"
            },
            confirmLabel = "编辑",
            quote = original,
            onConfirm = {
                pendingRewrite = null
                onOpenTextInput(original, index)
            },
            onDismiss = { pendingRewrite = null },
        )
    }

    // ── 重新生成警告（只弹「后面还有内容」的情形）────────────────────────
    pendingRegenerate?.let { index ->
        // 弹窗出现到用户点确认之间列表可能又变了，重新取一次真实条数。
        val dropped = messages.size - index - 1
        if (dropped <= 0) {
            // 后面已经没有内容了（比如那条回答又被截断了），直接执行，不必警告。
            pendingRegenerate = null
            ChatEngine.regenerate(conversationId, index)
        } else {
            WhaleChatDialog(
                title = "重新生成这条？",
                body = "后面 $dropped 条内容会一起删掉，再重新答这条。",
                confirmLabel = "重新生成",
                onConfirm = {
                    pendingRegenerate = null
                    ChatEngine.regenerate(conversationId, index)
                },
                onDismiss = { pendingRegenerate = null },
            )
        }
    }

    // ── 删除此信息警告 ──────────────────────────────────────────────────
    pendingDeleteExchange?.let {
        WhaleChatDialog(
            title = "删掉这条？",
            body = "这条提问和它的回答都会从记录里删掉，无法恢复。",
            confirmLabel = "删除",
            destructive = true,
            onConfirm = {
                pendingDeleteExchange = null
                ChatEngine.deleteLastUserExchange(conversationId)
            },
            onDismiss = { pendingDeleteExchange = null },
        )
    }
}

/**
 * 一条消息。
 *
 * ## 第一行：名字（左）+ 时间（右）
 *
 * 原来是名字独占一行。现在名字和时间并排、**同一水平线**：
 * ```
 * 你                   14:32
 * 问的内容……
 * ```
 *
 * ⚠️ 这一行必须是 `Row`，不能把两者拼成一个字符串：名字要 `weight(1f) + Ellipsis`
 * （长名字先截断、时间永远在），而拼串做不到这一点 —— 名字长了时间会被挤出去或换行。
 *
 * 时间用和名字**同一套 `when` 配色**（user / error / 普通三种容器色各配一个前景色），
 * 否则在 `primaryContainer`（用户气泡）上会是一块读不清的灰。
 *
 * ⚠️ **只有用户自己的气泡出时间**：判据是
 * `isUser && showBubbleTime`。助手回答不挂时间 —— 响应很快，那是噪音；
 * 用户要回顾的是「我什么时候问的」。
 *
 * [showBubbleTime] 由调用方传入（那里已订阅 settings），**不在这里内部订阅**。
 * 关掉时**整块时间不渲染** —— 名字那行 `weight(1f)` 会自然占满右边，
 * 看上去像「本来就没有时间」，而不是留一个空位。
 */
@Composable
private fun MessageCard(
    message: ChatMessage,
    /** 助手气泡顶上那一行写谁说的：角色会话是角色名，普通会话是「DeepSeek」。 */
    assistantLabel: String,
    /**
     * 这条会话是不是**角色会话**（`Conversation.personaId != null`）。
     *
     * ⚠️ 2.383：多气泡**只对角色会话生效**。
     * Ask（一次性问答）的回复仍然走原来那张单卡 —— 它是「一问一答」，
     * 拆成一句一个气泡既没有「她在发消息」的语境，也违背「不动 Ask」这条红线。
     * 判据由调用方从 `conversation.personaId` 算好传进来，**不要**在这里读引擎状态。
     */
    personaChat: Boolean,
    /** 气泡右上角那个时间显不显示（`Settings.showBubbleTime`）。 */
    showBubbleTime: Boolean,
    onRetry: (() -> Unit)?,
    onLongClick: (() -> Unit)?,
    /** 长按的动作说明（「编辑重发」/「复制」）。无障碍读屏会念出来。 */
    onLongClickLabel: String,
) {
    val scheme = MaterialTheme.colorScheme
    val isUser = message.role == ChatRole.USER

    val container = when {
        message.error -> scheme.errorContainer
        isUser -> scheme.primaryContainer
        else -> scheme.surfaceContainerHigh
    }
    val content = when {
        message.error -> scheme.onErrorContainer
        isUser -> scheme.onPrimaryContainer
        else -> scheme.onSurface
    }

    /** 名字和时间共用这一个前景色 —— 两者在同一条线上，颜色不一样会很跳。 */
    val labelColor = when {
        message.error -> scheme.onErrorContainer
        isUser -> scheme.onPrimaryContainer
        else -> scheme.onSurfaceVariant
    }

    // 有思维链就展开/收起，顺手给整张卡片的点击一个去处
    var reasoningOpen by remember { mutableStateOf(false) }
    val hasReasoning = message.reasoning.isNotBlank()

    val body = message.content.ifBlank { if (message.error) "出错了" else "…" }

    // ------------------------------------------------------- 2.382 多气泡（修好版）
    //
    // 2.36 的多气泡有两个硬伤（用户 2026-11-01 反馈）：
    //  1. **文字上下间距不一致**：名字行塞在第一个气泡里，首泡比后续高；
    //     短句又被 Card 的最小高度撑成大空泡；
    //  2. **视觉不协调**：每段都是全宽卡，段间距 5dp 小于卡片内边距，像一张卡被切开。
    //
    // 2.382 的修法（三件事一起做，缺一不可）：
    //  * **名字移到整组气泡上方**（不进任何气泡）⇒ 每个气泡结构完全一致；
    //  * 气泡用 `Box + clip + background + 显式 padding`，**不用 Card** ——
    //    Card 的最小高度会把短句撑开，而这里短句就该是一个小药丸；
    //  * 气泡**按内容收缩**（不 `fillMaxWidth`），段间距 6dp > 内边距的视觉厚度。
    //
    // 用户消息与错误消息仍然走下面那张 Card：它们是「一次发言」/系统提示，
    // 不该被拆，也不该改成小气泡。
    // ⚠️ 2.383：**Ask 会话也不拆**（`personaChat == false`）—— Ask 是「一问一答」，
    //    保持原来那张带「DeepSeek」标签的单卡；多气泡只属角色会话。
    if (!isUser && !message.error && personaChat) {
        AssistantBubbles(
            label = assistantLabel,
            reasoning = message.reasoning,
            reasoningOpen = reasoningOpen,
            onToggleReasoning = { reasoningOpen = !reasoningOpen },
            parts = remember(body) { splitForBubbles(body) },
            bubbleColor = container,
            textColor = content,
            labelColor = labelColor,
            onLongClick = onLongClick,
            onLongClickLabel = onLongClickLabel,
            onRetry = onRetry,
        )
        return
    }

    Card(
        onClick = { if (hasReasoning) reasoningOpen = !reasoningOpen },
        modifier = Modifier.fillMaxWidth(),
        // 长按只挂在能干的活上（见上面的循环）。传 null 时卡片行为和以前完全一样，
        // 不会多出一个点不出东西的长按手势。
        onLongClick = onLongClick,
        onLongClickLabel = if (onLongClick != null) onLongClickLabel else null,
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                // ⭐ 库里的 `Row` 默认 verticalAlignment 是 Top；这一行是一个 16sp 的名字
                // 配一个 13sp 的时间，贴顶对齐会在基线附近差一截，居中才对得上。
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    // 角色会话里这一行是角色名 —— 用户得看得见「刚才是谁在说话」。
                    // 长名字已经在 PersonaStore 里削到 6 字，这里不再二次截断，
                    // 免得出现两个地方各削一刀、结果都不一样的怪事。
                    text = if (isUser) "你" else assistantLabel,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = labelColor,
                    // ⭐ weight 必须给名字、**不能给时间**。Row 先量非 weight 的子项，
                    // 名字会把想要的宽度全拿走、把时间挤成 0 宽（表现为「时间不见了」，
                    // 而短名字时看起来完全正常）。weight 给名字 + Ellipsis 之后，
                    // 长名字自己截断，时间永远稳稳待在右边。
                    modifier = Modifier.weight(1f),
                )

                // ⭐ **只给用户自己的气泡显示时间**。
                // 理由：接收方的响应很快，给回答气泡也挂时间只是噪音；用户真正想回顾的是
                // 「我什么时候问的」。所以这里判 `isUser && showBubbleTime`，
                // 助手气泡（含 ask 页的 DeepSeek 气泡）一律不出时间。
                //
                // 关掉时整块不渲染（`if` 在 Row 里，不是 `Text("")`）——
                // 空 Text 仍会占宽度、也会多一个可测量的子项，等于留了个空位。
                if (isUser && showBubbleTime) {
                    Text(
                        text = bubbleTime(message.timestamp),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        color = labelColor,
                    )
                }
            }

            if (hasReasoning) {
                Text(
                    text = if (reasoningOpen) message.reasoning else "思考过程 · 点击展开",
                    style = MaterialTheme.typography.labelSmall,
                    color = when {
                        isUser -> scheme.onPrimaryContainer
                        else -> scheme.onSurfaceVariant
                    },
                    maxLines = if (reasoningOpen) Int.MAX_VALUE else 1,
                )
            }

            // 2.31：模型回复里的 Markdown / LaTeX 在手表上没法直接读
            // （`\(\mathrm{S}^{2-} + 4H_2O \rightarrow …\)` 会原样显示成一堆反斜杠和大括号）。
            // 只在**显示**时收成纯文本；用户自己的消息不动，消息原文 / 复制 / 上下文也不动。
            val shown = if (isUser) body else remember(body) { formatForWatch(body) }
            Text(
                text = shown,
                style = MaterialTheme.typography.bodyMedium,
            )

            if (onRetry != null) {
                TextButton(onClick = onRetry) { Text("重试") }
            }
        }
    }
}

/**
 * 助手消息的「多气泡」渲染（2.382 修好版）。
 *
 * ```
 * 贴吧老哥                     ← 名字只出现一次，在气泡组外面
 * ┌──────────┐
 * │ 又是你    │               ← 每个气泡：同一个 shape/颜色/padding
 * └──────────┘
 * ┌──────────┐
 * │ 说        │               ← 短句按内容收缩，不再拉满全宽
 * └──────────┘
 * ```
 *
 * ## 为什么不用 `Card`
 *
 * Wear M3 的 `Card` 带最小高度与默认内边距，短句（「说」「嗯」）会被撑成一个大空泡 ——
 * 这正是用户说的「字体上下间距不一致」。这里直接用 `Box + clip + background`，
 * 内边距由 [BUBBLE_PAD_H] / [BUBBLE_PAD_V] 明确给定，所有气泡完全一致。
 *
 * ## 交互
 *
 *  * 长按任意一颗气泡 = 复制**整条消息**（`onLongClick` 由调用方按整条内容实现）；
 *  * 点击第一颗气泡 = 展开/收起思维链（思维链挂在名字下面，不占气泡）；
 *  * 气泡**不 fillMaxWidth**：短句就是一个小药丸，长句在可用宽度处自动换行。
 *
 * @param parts 已经按行切好的段（[splitForBubbles]），至少一段
 */
@Composable
private fun AssistantBubbles(
    label: String,
    reasoning: String,
    reasoningOpen: Boolean,
    onToggleReasoning: () -> Unit,
    parts: List<String>,
    bubbleColor: Color,
    textColor: Color,
    labelColor: Color,
    onLongClick: (() -> Unit)?,
    onLongClickLabel: String,
    onRetry: (() -> Unit)?,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = labelColor,
            modifier = Modifier.padding(start = BUBBLE_LABEL_START),
        )

        if (reasoning.isNotBlank()) {
            Text(
                text = if (reasoningOpen) reasoning else "思考过程 · 点击展开",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (reasoningOpen) Int.MAX_VALUE else 1,
                modifier = Modifier.padding(start = BUBBLE_LABEL_START, top = 2.dp),
            )
        }

        Spacer(Modifier.height(BUBBLE_LABEL_GAP))

        Column(verticalArrangement = Arrangement.spacedBy(BUBBLE_GAP)) {
            parts.forEachIndexed { index, part ->
                Box(
                    modifier = Modifier
                        // shape 用主题的 large：和项目里其它 M3E 容器是同一套圆角。
                        .clip(MaterialTheme.shapes.large)
                        .background(bubbleColor)
                        // combinedClickable 而不是 Card 的 onClick/onLongClick：
                        // Card 会强制最小尺寸，这里要「短句就是小药丸」。
                        .combinedClickable(
                            onLongClick = onLongClick,
                            onLongClickLabel = if (onLongClick != null) onLongClickLabel else null,
                            // 只有第一颗挂思维链展开；没有思维链时点击等于空操作
                            // （和 2.35 单气泡的 Card 行为一致，不会多出一个可点的语义）。
                            onClick = { if (index == 0) onToggleReasoning() },
                        )
                        .padding(
                            start = BUBBLE_PAD_H,
                            end = BUBBLE_PAD_H,
                            // 上下**不等**：抵消字体 ink 偏下（见 [BUBBLE_PAD_TOP] 的测量注释）。
                            top = BUBBLE_PAD_TOP,
                            bottom = BUBBLE_PAD_BOTTOM,
                        ),
                ) {
                    Text(
                        // 2.31 的显示层清洗：先按行拆（splitForBubbles），再逐段格式化。
                        // 顺序不能反 —— 先格式化会把换行/记号吃掉。
                        text = remember(part) { formatForWatch(part) },
                        style = MaterialTheme.typography.bodyMedium,
                        color = textColor,
                    )
                }
            }
        }

        if (onRetry != null) {
            TextButton(onClick = onRetry) { Text("重试") }
        }
    }
}

/**
 * 对话页底部那行「已用 xx tokens」。
 *
 * ## ⚠️⚠️ 1.983：**这个函数的实现是修「果冻」的核心，别改回去**
 *
 * 1.982 及以前这里写的是：
 * ```kotlin
 * AnimatedVisibility(visible = visible, enter = fadeIn(spec), exit = fadeOut(spec)) { Text(…) }
 * ```
 * 而调用点传进来的 `visible` 是 **`!listState.canScrollForward`** ——
 * 于是构成了一条**自激振荡回路**：
 * ```
 * canScrollForward 变化
 *   → visible 变化
 *     → AnimatedVisibility 增删/改变高度      ← 它在淡入淡出期间**高度也在连续变**
 *       → 列表内容总高度变化
 *         → canScrollForward 变化   ←──── 回到起点，自激、每帧都在推
 * ```
 * 症状就是用户两轮都在说的：**底部返回按钮「像果冻一样自己一直上下起伏」**，
 * 而**滑到最底下就正常**（那时 `canScrollForward == false` 是稳定解，回路失去驱动力）。
 *
 * ### 修法的唯一要点：**`visible` 只驱动 `alpha`，绝不再驱动布局**
 *
 * 现在这个 item **永远存在、高度恒定**（`Text` 一直在，只是透明），
 * 所以 `canScrollForward` 不再被它影响 ⇒ **回路从根上断开**。
 *
 * ⚠️⚠️ **不要再改回 `AnimatedVisibility`**，也不要改成任何
 * 「显隐会改变列表内容高度」的写法。这条已经在提示词 §D.5 升格为**硬约束**：
 * > 一个 item 的「显隐」如果改变列表内容高度，而它的显隐又是读列表滚动状态算出来的，
 * > 就会自激振荡。这类「visibility 由它自己影响的量驱动」的写法，
 * > 在 `ScalingLazyColumn` / `LazyColumn` 里**一律禁止**。
 *
 * ### 代价（**这是必要取舍，不是缺陷**）
 *
 * 底部会**常驻一条约一行高**的空位（`alpha=0` 时 `Text` 仍在、仍占位）。
 * 不这么做就只能让布局随显隐变化，而那正是回路本身。
 * 实测满高 = 一行 `labelSmall` ≈ 39px（18dp），在 240dp 的屏上是可接受的代价；
 * 换来的是**按钮高度极差从 33px 变成 0px**。
 *
 * ## 为什么不干脆「常显、连 alpha 都不要」（提示词里的备选 B）
 *
 * **查证结果：「划到最底下才出现」是用户明确要求过的，不是实现者的自作主张。**
 * 证据在会话目录 `whalechat-1.907-prompt.md:364`：
 * > | `UsageFooter` 只在 `canScrollForward == false` 时出现 | **用户明确定过：「得划到最底下才触发显示」** |
 * ⇒ 备选 B（去掉 `visible` 参数、恒定 `alpha = 1`）**会删掉一个用户明确要过的行为**，
 * 所以**不采用**。保留淡入淡出观感、只把驱动方式从「布局」换成「透明度」，
 * 是既守住用户要求、又断掉回路的唯一组合。
 *
 * ## 其它保持不变的语义（都别顺手改）
 *
 * 只显示最终总量，**不显示「上轮 xx」** —— 那会把一行小字塞进两个数字，反而更费读。
 *
 * ## ⚠️⚠️ 1.987：**缓存命中率整段删除**（用户原话：「chat对话页命中率删去」）
 *
 * 原来 `UsageFooter` 有第三个参数 `hitRate`（[usageText] 拼 `· 命中 NN%`），
 * 现在参数和那个分支**一并移除**。
 *
 * ⚠️ **数据照常记录**：`Conversation.cacheHitTokens` / `cacheMissTokens` 的
 * 记录链路（`StreamEvent.Usage` → `addUsage` → `ConversationStore`）**一个字没动**——
 * 用户要的是「不显示」，不是「不统计」。别顺手去删那些读写。
 *
 * ⚠️ **联动改过的注释**：1.979 那段「缓存命中率默认看得见」的历史说明
 * 已随本条失效（那段说的就是现在删掉的东西），保留在 [usageText] 删除处
 * 的历史注释里，不再在 KDoc 里保留「它看得见」的表述。
 *
 * **显不显示由「对话中的用量」控制**（`Settings.showUsageInChat`）。
 * 那个开关关掉时**整行不存在**（调用点直接不渲染这个 item），不是变灰、也不是显示 0。
 * 注意：原来那个管两处的 `showTokenUsage` 已拆成两个开关，
 * 这一处归「对话中的用量」，角色列表那一处归「角色列表的用量」。
 *
 * ⚠️ **1.981：ask 的对话页连这个 item 都不渲染**（用户原话「把 ask 的对话页的具体
 * token 用量和命中率显示删掉，难看」）。判据是 `conversation.personaId != null` ——
 * 也就是**只有 chat（人设会话）那一侧还看得到这一行**。所以现在有两道闸：
 * `personaId != null`（哪一侧）**且** `showUsageInChat`（开关）。
 *
 * ⚠️ **这个开关默认开**（1.979 从「默认关」翻过来的），而它的入口
 * （「显示设置」二级页）**仍然隐藏** ⇒ **默认状态下 chat 那一侧是看得到的**，这是预期。
 * 改默认值看 `SettingsStore.applyDisplayDefaults`（**第 1979 次覆盖**那一段），
 * 恢复入口看 `SettingsScreen.SHOW_DISPLAY_SETTINGS_ENTRY`。
 *
 * 淡入淡出用 Wear M3 的动效规范 `defaultEffectsSpec`（专管透明度/颜色这类「效果」），
 * 不手写 `tween`。理由和首页那行模式提示一样：把 M3 在低功耗设备上对时长与缓动的
 * 取舍丢掉、也失去跟主题一起变的能力，不划算。
 * ⚠️ 1.983 换掉 `fadeIn/fadeOut` 之后，这个 spec 仍然是**同一个**
 * （`defaultEffectsSpec<Float>()` 本来就是给透明度这类效果用的），只是改成喂给
 * `animateFloatAsState` —— 动效观感与之前一致，**别换成 `tween`**。
 *
 * ⚠️ 这个 `Text` **故意不加 `maxLines` / `overflow` / 固定 `width`**：
 * 它是 `fillMaxWidth()` + 居中，放不下时只会**换行**（信息不丢），而不是被裁掉。
 * 之前字被裁成 `06:`，是因为那里硬写了 `Modifier.width(22dp)` 把盒子压窄了 ——
 * 这里没有那个东西，**也不要为了「防换行」把它加上去**（那才会真的裁字）。
 *
 * ⚠️ **1.987 命中率删掉后，这行文字变短了** —— 原来那句「已用 … · 命中 87%
 * 会换行、处理方式是缩文案」的历史教训随 hitRate 一起失效，
 * 现在「已用 xx tokens」在任何宽度下都是单行。
 *
 * ⚠️ **换行会让这个 item 变高**（一行 ↔ 两行）—— 这**不会**重新引入回路：
 * 高度只由**文案长度**决定，而文案只跟 `tokens` 有关，
 * **跟滚动位置无关**。回路的前提是「驱动量由它自己影响」，
 * 这里驱动量是数据、不是滚动状态。
 */
@Composable
private fun UsageFooter(visible: Boolean, tokens: Long) {
    // ⚠️ 用 `defaultEffectsSpec<Float>()` —— 它就是 M3 给「透明度/颜色这类效果」用的，
    // 和原来 `fadeIn/fadeOut(spec)` 拿到的是同一条曲线。
    val spec = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = spec,
        label = "usageFooterAlpha",
    )
    Text(
        text = usageText(tokens),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            // ★★ 只动 alpha，**不碰布局** —— 这是断掉自激回路的那一下。
            // ⚠️ 不要用它换回 `AnimatedVisibility` / `if (visible) { … }` 之类
            // 任何会改变高度的写法。
            .graphicsLayer { this.alpha = alpha },
        textAlign = TextAlign.Center,
    )
}

/**
 * 底部那行用量文字的拼法。
 *
 * ⚠️ **前缀是「已用」不是「本对话已用」**（实测后定的，见下）。
 *
 * 240dp 圆屏里这一行的**实测可用宽只有 189dp**（`fillMaxWidth` 拿到的
 * bounds 是 378px ÷ density 2.0 —— 两侧 contentPadding 比提示词估的还多）。
 * 原来 ` · 命中 87%` 那段还会再占 ~50dp，把整行挤成两行 ——
 * **1.987 命中率整段删掉后这个问题连根消失**（用户原话：「chat对话页命中率删去」），
 * 剩下的「已用 xx tokens」在最窄的宽度上也放得下。
 *
 * 这里**没有缩字号、没有加 `maxLines`、没有加固定宽度** ——
 * 那三样都会把「换行」变成「裁字」，是这个项目栽过的坑。
 *
 * ## ⚠️ 已删除的 `hitRate` 参数（1.987）
 *
 * 原来这里有第二个参数 `hitRate: Float?`（null = 没统计过，不显示），
 * 拼 `· 命中 NN%`。用户要求整段删去 ⇒ 参数与分支一并移除。
 * **数据链路（cacheHit/missTokens 的记录）没有动** —— 只是不再显示。
 */
private fun usageText(tokens: Long): String = "已用 ${formatTokens(tokens)} tokens"

/**
 * 「正在思考…」那张卡。
 *
 * 1.978 在文字上面补一个 **M3E 波浪形圆形进度指示器**（`WavyProgressIndicator`，
 * 移植自 `material3_indicators` 的 `WavyCircularProgressIndicator`）。
 * 原来这里只有一行字，但「正在思考」本质上就是一次不确定的等待 —— 没有动的东西
 * 用户没法判断应用是活着还是卡住了（尤其思考档首包本来就慢）。
 * 与捏人等待页用的是同一个组件，两处的等待语言从此一致。
 *
 * 直径取 34dp（不是等待页的 56dp）：这张卡是**列表里的一项**，会被
 * `ScalingLazyColumn` 按位置缩放，本身还占着一屏；小一圈才不至于把消息列表挤掉一行。
 */
@Composable
private fun ThinkingCard() {
    // Wear M3 的 Card 只有「可点击」一种重载，没有静态版本 ——
    // 静态卡片的写法是 onClick = {} + enabled = false（官方 KDoc：not enabled 就是不可点）。
    // 光写 onClick = {} 会给出水波纹，等于骗用户去点一张什么都没发生的卡。
    Card(
        onClick = {},
        enabled = false,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            WavyProgressIndicator(diameter = 34.dp)
            Text(
                text = "正在思考…",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * 录音 / 识别状态。
 *
 * 做成静态卡片（`enabled = false`）是刻意的：它只报状态、没有任何点击行为，
 * 而 Wear M3 的 Card 只有「可点击」一种重载，不加 enabled = false 就会带水波纹，
 * 看起来像能点。理由和 ThinkingCard 完全一样。
 *
 * [partial] 是流式识别的中间结果（讯飞那条才有）。把它显示出来是有意为之：
 * 「边说边出字」如果只在识别完才给结果，用户没法判断机器到底听对没有，
 * 而这个页面又没有输入框可以回看 —— 中间结果就是唯一的证据。
 *
 * ## ⚠️⚠️ 1.984 → 1.985：电平条「修好 → 删除」的完整轮回（防止后人再走一遍）
 *
 *  * **1.984 修好过一次**：原 `LinearProgressIndicator` 的公开包装**内部带补间动画**
 *    （库源码 `LinearProgressIndicator.kt:85-94`，`Animatable` + `animateTo`，靠 vsync 帧
 *    回调推进），而 `AudioRecorder.dbfs()` 又用 −60 dB 当下限把静音一律夹成 0 ⇒
 *    **条子恒为 0 且动画在慢设备上推不动**，双重原因叠加成「静止不动」；
 *  * 修法是「自绘无补间 + 重映射 −75~−10 dB」—— **模拟器上确实画出来了**（53px 稳定段），
 *    但**真机用户实测：「不仅有延迟还卡」**。真机的电平每 ~80ms 跳一次、
 *    每次都触发这张卡所在 item 的重组 + 重绘，在手表的低端 GPU 上就是持续掉帧；
 *    而它提供的信息量（「麦克风收到了多大声音」）远低于 `partial` 的「边说边出字」
 *    —— 用户要看的是**识别得对不对**，不是**声音有多大**；
 *  * **1.985 用户拍板删除**：「干脆把声音起伏指示条删了，不仅有延迟还卡，位置也不要做保留」。
 *    电平条与其数据管线（`AudioRecorder` 的 `level` StateFlow、`dbfs()` 及全部映射常量）
 *    **整条删除**；同时 `AudioRecorder` 录音循环里那段每 80ms 推一次 UI 的逻辑也删了
 *    —— 它正是「卡」的来源。替换它的是 [RecordingCountdownBar]（**每秒才变一次**的
 *    倒计时条），见那个函数的 KDoc（为什么它不会重蹈覆辙）。
 *
 * ⇒ **结论：删除 + 换成倒计时条。** 1.984 那条**常驻占位**（为断「果冻」回路
 * 而引入的 ~39px 空位）也随电平条一起删了；现在这张卡的高度**只由文字与 partial 决定**。
 *
 * ⚠️⚠️ **「果冻」回路会不会回来？—— 不会。** 上一轮的回路是
 * 「`visible` 读滚动状态 → `AnimatedVisibility` 改变高度 → 又影响滚动状态」。
 * 现在用量条那两个 item（`usage`/`usage_gap`）在 `showUsageHere=false` 时不渲染
 * （1.981 起如此），与本卡互不影响 ⇒ **没有反馈回路**。
 * 验证见 `WHALECHAT_1.984_实现报告.md` 的果冻复测（三位置逐帧差异 0px）。
 *
 * @param phase 录音 / 识别阶段（决定文案 + 要不要出倒计时条）。
 * @param partial 流式识别的中间结果（讯飞那条才有；通用接口/系统识别为空）。
 */
@Composable
private fun VoiceStatusCard(phase: VoicePhase, partial: String) {
    val scheme = MaterialTheme.colorScheme
    Card(
        onClick = {},
        enabled = false,
        modifier = Modifier.width(ACTION_ROW_WIDTH),
        colors = CardDefaults.cardColors(
            containerColor = scheme.surfaceContainerHigh,
            contentColor = scheme.onSurface,
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = if (phase == VoicePhase.RECORDING) "正在聆听…" else "正在识别…",
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
            )
            if (phase == VoicePhase.RECORDING) {
                Spacer(Modifier.height(6.dp))
                RecordingCountdownBar()
            }
            if (partial.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = partial,
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurface,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * 录音倒计时条：显示 **60 秒上限还剩多少**。**1.985 新增**（用户原话：
 * 「刚启动语音识别没有输出的时候留白过大有点丑，干脆就做个 60s 的录音倒计时指示条，
 * 有点走回头路的感觉（但只是显示剩余输入时间）」）。
 *
 * ## 为什么这次**不会**重蹈电平条的覆辙（「又延迟又卡」）
 *
 * 电平条卡的三个原因，这条倒计时**一个都不沾**：
 *
 *  1. **更新频率**：电平值 ~12 次/秒地跳 ⇒ 每跳都重组+重绘；倒计时**每 1 秒才变一次**
 *     （60 秒里只重组 60 次），对手表 GPU 是零负担；
 *  2. **动画依赖**：电平条当时撞上的是「库的补间动画等 vsync 帧回调、模拟器不给帧」。
 *     这条**不用库组件**（那次的另一个坑：`LinearProgressIndicator` 的
 *     `trackBrush`/`DotRadius` 等全是 internal，1.984 编译错实测），
 *     而是**自己按 M3 语义画**（几何与颜色 token 全部对齐库源码，与 1.984 那版一致），
 *     **值变当帧就画**，中间没有任何动画/帧回调依赖；
 *  3. **确定进度**：它画的是「已录时长 / 60s」这个**单调确定**的量，
 *     不会像电平值那样忽上忽下，观感是「平稳走条」而不是「闪」。
 *
 * ## 语义
 *
 * 「走回头路」用户自己也说了 —— 对，它**看起来**像最早那版的不定进度条，
 * 但表达的东西不同：**单次录音最多 60 秒**（[AudioRecorder.MAX_SECONDS]，讯飞那边
 * 整个会话也 60 秒封顶）⇒ 剩余时间是一条**真实存在**的硬约束。
 * 把它画出来，用户就知道「还能说多久」，也解释了「为什么说着说着自己停了」。
 *
 * ## 实现
 *
 * * **每秒自推**（`LaunchedEffect` + `delay(1000)`），不押在帧回调上 ——
 *   理由同 `WavyProgressIndicator` 的 KDoc（模拟器不给帧，`delay` 走主线程 Handler
 *   会自己把帧要出来）；
 * * **进度从满往空走**（剩余比例）：开始录音时是满的，随时间变短，
 *   走完即 60 秒到顶（`AudioRecorder` 会自己停）。
 * * `remember(stopRequestedAtMs)` 以「进 RECORDING」为 key 重置计时。
 */
@Composable
private fun RecordingCountdownBar() {
    // 已录秒数。key 给 Unit：进这张卡（= 进 RECORDING）时从头计。
    var elapsedSec by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (elapsedSec < AudioRecorder.MAX_SECONDS) {
            delay(1_000)
            elapsedSec += 1
        }
    }

    val scheme = MaterialTheme.colorScheme
    val strokeWidth = 8.dp   // 与库 StrokeWidthSmall 一致（token 是 internal，值对齐）
    val total = AudioRecorder.MAX_SECONDS.toFloat()
    val remaining = (total - elapsedSec).coerceAtLeast(0f)

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(strokeWidth)
            .padding(2.dp),   // 库的 OuterHorizontalMargin（internal，值对齐）
    ) {
        val progressPx = (remaining / total) * (size.width - strokeWidth.toPx())
        val capOffset = strokeWidth.toPx() / 2f
        val cy = size.height / 2f

        // 底轨（满宽，画的是「总时长」）
        drawLine(
            color = scheme.surfaceContainer,
            start = Offset(capOffset, cy),
            end = Offset(size.width - capOffset, cy),
            strokeWidth = strokeWidth.toPx(),
            cap = StrokeCap.Round,
        )
        // 剩余时间（从满往空走）
        if (progressPx > 0f) {
            drawLine(
                color = scheme.primary,
                start = Offset(capOffset, cy),
                end = Offset(capOffset + progressPx, cy),
                strokeWidth = strokeWidth.toPx(),
                cap = StrokeCap.Round,
            )
        }
    }
}

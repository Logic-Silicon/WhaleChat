package com.naoh.whalechat.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyColumnDefaults
import androidx.wear.compose.foundation.lazy.ScalingParams
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.foundation.pager.HorizontalPager
import androidx.wear.compose.foundation.pager.rememberPagerState
import androidx.wear.compose.material3.AnimatedPage
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonGroup
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CardDefaults
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.HorizontalPagerScaffold
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.PagerScaffoldDefaults
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TitleCard
import com.naoh.whalechat.data.ChatEngine
import com.naoh.whalechat.data.Conversation
import com.naoh.whalechat.data.Persona
import kotlinx.coroutines.delay

/**
 * 顶部两个主操作按钮的尺寸。
 *
 * ## ⚠️ 宽度：90dp → 70dp → **76dp**（两轮调整的理由都在这）
 *
 * **第二轮**：用户要求「适当做小上面的两个按钮，**尤其是水平方向做窄**」，
 * 并给了另一款手表 app 的顶部按钮作参照（`Screenshot_20261011_074751_wysappmarket.png`）。
 * 那张参考图 480×480，实测（`m978_top_btn.py`）：
 *
 * ```
 *   左 146px × 高 97px ｜ 间隙 10px ｜ 右 146px      ⇒ 整行 302px
 *   按 2.125 px/dp 换算：68.7dp × 45.6dp ｜ 间隙 4.7dp ｜ 整行 142dp ｜ 上沿 73px
 * ```
 *
 * 照着收成 **70dp**（整行 144dp ≈ 参考的 142dp）。
 *
 * **第五轮**：用户问「和图例匹配吗」，量完的结论是**宽度已经和图例基本一致**
 * （70 vs 68.7dp，还略宽 1.3dp），但用户仍要「适当做宽一小点点」。
 * ⇒ 定 **76dp**（整行 156dp，+6dp/颗，+4%）。
 *
 * ## ⚠️ 加宽之前算过的余量（别凭感觉加，加到被切角就是恶性结果）
 *
 * ⚠️⚠️ **1.978 第一版就是被切了上角**（用户原话「显示不全」）。原因见下面「圆屏几何」那段的
 * ⚠️ **关键修正**：卡住按钮的**不是**按钮行的上沿，而是它**上沿往下约 9dp 的那一行** ——
 * 圆角半径 26dp **大于**半高 22dp，所以按钮顶边并不是「尖的」，顶边那一行就已经有相当的宽度了。
 * 实测（70dp 时逐行量「整行半宽」与「圆屏在该行的允许半宽」之差）：
 *
 * ```
 *   y=74（顶行）  画的半宽 113.5  允许 173.3   余量 +59.8px
 *   y=94（最紧）  画的半宽 145.5  允许 190.5   余量 +45.0px   ← 瓶颈在这
 *   y=120（最宽） 画的半宽 153.5  允许 209.0   余量 +55.5px
 * ```
 *
 * 加宽 Δdp 只是把「画的半宽」在**每一行**上各加 Δ（圆角半径是绝对值，不随宽度变），
 * 所以瓶颈那行还剩 `21dp − Δ`。**上限 ≈ 21dp/颗**（行宽 186dp）。
 * 本轮取 **Δ = 6dp** ⇒ 瓶颈余量仍有 **+32px（≈15dp）**，留足了安全边。
 * 320dpi 下同一处的余量更大（R=120dp），不构成更紧的约束。
 *
 * ## 高度保持 44dp 不动
 *
 * 参考图的高度是 45.6dp，本来就比我们高；用户说的「做小」指的是水平方向。
 * 高度那一档还是提示词里「≈88px」（= 44dp @ 320dpi）。
 *
 * ## 圆屏几何（改宽度必须重算这条）
 *
 * 这块屏是 **240dp 见方**（模拟器 480px @ density 340）；取最不利密度时
 * 圆屏半径只有 **112.9dp**。整行 144dp 全部落进圆里需要
 * `2·sqrt(112.9² − (112.9−y)²) ≥ 144` ⇒ 上沿 ≥ 25.9dp —— 见 [TOP_GAP]。
 *
 * ⚠️ **判定要拿上沿那条弦**，不是行中心线（第一版就是栽在这，上角被切）。
 *
 * 注意 [TOP_ACTION_WIDTH] 是**静止**宽度。按下时 ButtonGroup 会让被按的那颗
 * 按 ExpansionWidth（默认 24dp）涨出去、旁边那颗同步缩回来，总宽始终 144dp ——
 * 所以圆屏几何这条约束不会被按坏。（70dp 的静止宽 + 24dp 展开 = 94dp，
 * 两颗加起来仍然 144dp，不会顶到圆边。）
 *
 * **两页共用这几个常量**：第二页（通讯录）的按钮行和第一页几何完全相同，
 * 差一个字都会让两页在左右滑动时跳一下。
 */
private val TOP_ACTION_WIDTH = 76.dp
private val TOP_ACTION_HEIGHT = 44.dp

/**
 * 两颗胶囊的间隙。
 *
 * 交给 ButtonGroup 的 `spacing`，不再自己写 `Arrangement.spacedBy` ——
 * 宽度分配是容器统一算的，间隙也必须用同一个数，否则按下时变宽的那一边会把间隙算歪。
 */
private val TOP_ACTION_GAP = 4.dp

/** 两个胶囊并排后的总宽。写成显式常量，免得 `a * 2 + b` 这种式子读起来含糊。 */
private val TOP_ACTION_ROW_WIDTH = TOP_ACTION_WIDTH * 2 + TOP_ACTION_GAP

/** 顶部图标尺寸（比初版的 26dp 略小，配合更小的胶囊）。 */
private val TOP_ACTION_ICON_SIZE = 22.dp

/**
 * 第一项留白：把两颗胶囊从屏幕最顶上「让」下来。
 *
 * ## ⚠️ 这个数由**圆屏几何**卡死，不能靠感觉调（1.978 第一版就栽在这）
 *
 * 两颗胶囊并排总宽 [TOP_ACTION_ROW_WIDTH] 是**矩形**，而屏幕是圆 ——
 * 在高度 y 处只有 `2·sqrt(R² − (R−y)²)` 那么宽。
 * **要点是拿按钮的「上沿」去比，不是拿按钮行的中心线去比**：
 * 上沿那一行弦最窄，只要它放不下整行宽，两颗胶囊的**上角就会被圆边切掉**。
 *
 * 第一版把 `TOP_GAP` 压到 3dp（按钮上沿 ≈ 31dp），当时只按「行中心线」验算，
 * 结果上角真的被切了（用户原话：「紧、还显示不全」）。
 *
 * 现在按上沿、取最不利密度（**340dpi ⇒ 屏幕半径只有 240px/2.125 = 112.9dp**）重算：
 * ```
 *   需要 2·sqrt(112.9² − (112.9 − y)²) ≥ 144dp  ⇒  y ≥ 25.9dp   （行宽 70+4+70）
 * ```
 * 第五轮按参考 app 对齐到 73px（`TOP_GAP = 7.4dp`），但那条带子被压到只剩 37px，
 * 闪字被挤（第六轮反馈）⇒ **第七轮回调到 [TOP_GAP] = 10dp**：
 * 按钮上沿 ≈ 37.2dp = **79px**（比参考图低 6px），带子 44px，闪字上下各留 10px；
 * 该处弦宽 ≈ 168dp ≥ 156dp ✓（余量 12dp，仍远大于 0）。
 * ⚠️ 往下挪同时会让圆屏弦**变宽**（y 越靠近圆心越宽），所以这一步是双赢：
 * 闪字松了、圆边余量也多了。
 * 320dpi（半径 120dp）下同一点弦宽 ≈ 168dp ✓。
 *
 * ⚠️ 它与 [TOP_ACTION_WIDTH] 一样是**两页共用**的，别只改一页。
 * ⚠️ 它同时决定「模式提示」那条闪字能不能待在上面的空档里 —— 见 [MODE_FLASH_TOP]。
 */
private val TOP_GAP = 10.dp

/**
 * 顶部两颗胶囊与**下面第一块内容**（「最近对话」标题 / 第二页的首张角色卡）之间的间距。
 *
 * 1.976 是第一页 12dp、第二页 0dp；1.978 第一版把它整个删掉，想让内容尽量上提。
 * **删过头了** —— 用户的反馈是「两按钮和最近对话/角色列表的间距太小了，看着很紧凑」。
 * 现在两页都给 **10dp**（原来第二页是 0dp，最挤的就是页二）。
 *
 * ⚠️ 这个数是**上下都有代价**的：往上一格是按钮下沿、往下一格是内容。
 * 调大它会把首卡和底部「共 N tokens」那行一起往下推 —— 首屏内容不多时
 * 那行会被下边缘裁掉一部分。改之前先看 [MODE_FLASH_TOP] 和底部汇总那条注释。
 */
private val ACTIONS_GAP = 10.dp

/**
 * 列表里每张卡片的**内容内边距**和**固定高度**。
 *
 * 1.978 第三轮：用户要求「适当做小卡片显示面积」。这里动的是**盒子**，
 * 字号、行距、层级一个字没改。
 *
 * ## ⚠️ 高度必须显式给 —— 这是 `ListSubHeader` 那个坑的**同一个**坑
 *
 * 第一版只把 `contentPadding` 的纵向从默认（约 11dp）收到 8dp，实测**卡片高度只小了 9px**：
 *
 * ```
 *   改前 68dp(145px) → 给 8dp 内边距后 64dp(136px)   （只省了 9px，不是预期的 25px）
 * ```
 *
 * 原因：`TitleCard` 和 `ListSubHeader` 一样带 `defaultMinSize(minHeight = 64.dp)`
 * （`CardTokens.ContainerHeight`）。只要内容算出来**小于** 64dp，卡片就被撑在 64dp ——
 * **内边距调得再小也没用**。杠杆仍然是那一个：**显式给高度**
 * （`Modifier.height(h)` 把外层约束钉成 min=max，里面的 `defaultMinSize` 见 minHeight
 * 已经不是 0，就不再抬上去）。
 *
 * ## 为什么是 56dp（不是更小，也不是 64dp）
 *
 * 这张卡片是「标题一行 + 副标题一行」，内容自然高度实测约 **54dp**
 * （标题行盒 23dp + 副标题行盒 19dp + 两者之间那点间距 + 上下各 6dp 内边距）。
 * 所以 56dp 是「内容放得下 + 只比内容多 2dp」的那个值：
 *
 *  * 再矮就**开始挤文字** —— 和 `ListSubHeader` 那个「标题整个画不出来」是同一类事故
 *    （盒子小于 padding + 内容时，Text 会被量成 0 高）；
 *  * 不给高度就永远是 64dp（= 136px），等于没改。
 *
 * 340dpi 实测：145px → **119px**，一屏多露约半张卡。
 *
 * ## ⚠️ 两页的卡片（会话 / 角色）**共用这两个值**
 *
 * 两页外观不一致的话，左右滑动的那一下会明显「一张卡片鼓出来」。
 * 两张卡都是「两行」结构，所以同一个高度对两者都成立。
 */
private val CARD_CONTENT_PADDING = PaddingValues(horizontal = 12.dp, vertical = 6.dp)

/**
 * 卡片高度。**必须显式给**，理由见 [CARD_CONTENT_PADDING] 上面那段
 * （`TitleCard` 自带 `defaultMinSize(64.dp)`，不给高度就永远 64dp）。
 * ⚠️ 改它之前先量一下内容自然高度 —— 低于内容高度会把文字挤没。
 *
 * ## ⚠️⚠️ 1.986：**改回 56dp**（1.985 曾改到 50dp）
 *
 * 1.985 为了修「标题过于靠上」把卡高压到 50dp（贴合内容自然高 49.4dp），
 * **用户实测：「最近对话和联系人卡片怎么改的好小，做回前几个版本大小」**。
 *
 * ## 1.986 的取舍：**回到 56dp + 用「卡内整体居中」解决「靠上」**
 *
 * 56dp 卡里内容自然高 105px、剩余 ~14px 被 `CardImpl` 的 `Column` 落到底部 ⇒ 偏上。
 * 50dp 是「消灭剩余空间」；**56dp 下剩余空间仍在** ⇒ 改用另一个杠杆：
 * `ConversationRow` 的 title 槽给 `Text` 挂 **`Modifier.align(Alignment.CenterVertically)`**
 * （RowScope 的垂直居中，把 title 行盒在 Row 的可用高里居中；
 * `Card.kt:1096` 那个 title Row 本身是 `Alignment.Top`，不挂就贴顶）。
 * 副标题槽是 `ColumnScope`、没有垂直 align 可用 ⇒ 它保持不动
 * （它贴在 title 行下面，title 下移之后整组观感已经回正）。
 *
 * **不影响 `ScalingLazyColumn` 缩放效果**（用户原话要求）：卡高只改这一张卡的
 * 布局尺寸，缩放参数仍是 `HOME_SCALING`（`edgeScale = 0.6`），条目的
 * 排布/缩放曲线一概没动 —— 缩放发生在 `graphicsLayer` 层，与 item 的固有高度无关。
 */
private val CARD_HEIGHT = 56.dp

/** 底部「共 N tokens」汇总行离列表上一项的间距。理由见那个 item 里的注释（别删）。 */
private val ASK_TOTAL_TOP_GAP = 12.dp

/**
 * 两页列表共用的缩放参数 —— **1.982 把「随滑动放大缩小」调明显了**。
 *
 * ## 用户原话
 *
 * > 「最近对话的卡片随滑动页面放大缩小显示的效果做的不够明显，
 * >   稍微加强一点放缩变化的显示效果」
 *
 * ## 这个特性叫什么、旋钮是哪个（读库源码得出的确切答案，不是猜的）
 *
 * 官方名字是 **`ScalingLazyColumn` 的缩放效果**，参数是它构造参数里的
 * **`scalingParams: ScalingParams`**。KDoc 原话（`ScalingLazyColumn.kt:755-759`）：
 *
 * > "Items in the ScalingLazyColumn have **scaling and alpha effects** applied to them
 * > depending on their position in the viewport. The closer to the edge (top or bottom)
 * > of the viewport that they are the greater the **down scaling and transparency** that is applied."
 *
 * ⇒ 「越靠屏幕上下沿，缩得越小、越透明；中间最大」，和用户描述完全一致。
 *
 * 缩放量的算法（`ScalingLazyColumnMeasure.kt:317-358`）：
 * ```
 * scaleToApply = lerp(1.0f, scalingParams.edgeScale, scalingInterpolated)
 * ```
 * ⇒ **`edgeScale` 是唯一的「缩放强度」旋钮**：`1.0` = 完全不缩，`0.7` = 缩到七成。
 *
 * ## 取值：0.7（库默认） → 0.6
 *
 * 库默认 `ScalingLazyColumnDefaults.scalingParams(edgeScale = 0.7f)`，用户说不够明显。
 * 这里改成 **`0.6f`** —— 缩到六成，比默认明显一档。
 *
 * ⚠️ **只动 `edgeScale` 这一个变量**，理由：
 *  * `edgeAlpha`（靠边时的透明度，默认 0.5）**刻意不动** —— 用户只说了「放大缩小」，
 *    没提透明度。两个一起改，下次反馈「还是不够」时就分不清是哪一个起的作用，
 *    也看不出该往哪边调；
 *  * `minTransitionArea` / `maxTransitionArea`（默认 0.35 / 0.55）**刻意不动** ——
 *    它们决定「从哪个位置开始缩」，动它们改变的是缩放的**起止范围**，
 *    比动 `edgeScale` 副作用大得多（本页底部还有「共 N tokens」汇总行和翻页圆点，
 *    1.976~1.978 为它们打过三四轮，**别再撞回去**）。
 *
 * 为什么不干脆给 0.5：小卡片上「忽大忽小」会读出眩晕感。先给 0.6，
 * 明显但不过头；用户要更强再往下走。
 *
 * ## ⚠️ 应用范围：只给 HomeScreen 的两页（Ask 首页 + Chat 通讯录）
 *
 * 用户点名的是「**最近对话**的卡片」（Ask 首页）。**通讯录那一页也给**，理由：
 * 两页是左右滑动切换的，缩放强度不一致会「跳一下」——
 * 项目硬约定「两页的按钮行几何差一个字都会让两页在左右滑动时跳一下」，
 * 同一页的两个面更不该有两套观感。
 *
 * **别的页面（ChatScreen / 各设置页）不给**：用户只提了「最近对话的卡片」；
 * 而且那些页面（设置页那种）卡片本来就填满宽度，缩放只会让它们的边缘「抖」。
 *
 * ## ⚠️ 一条必须知道的性质
 *
 * `scalingParams` 只是**显示效果**（走 `graphicsLayer`），**不改变布局占位** ——
 * 列表的可滚动高度、item 间距都不变，但视觉上靠边的卡片会变小、留白变多。
 * item 位置由 `autoCentering`/锚点决定、缩放是之后套上去的，所以
 * **理论上不影响底部那行汇总与翻页圆点** —— 但必须实测确认（见报告需求 3）。
 */
private val HOME_SCALING: ScalingParams = ScalingLazyColumnDefaults.scalingParams(
    edgeScale = 0.6f,
)

/**
 * ⚠️ 1.978 第三轮：`HEADER_HEIGHT` 与 `recentHeaderPadding` **已删除**。
 *
 * 它们是为「最近对话」那个 `ListSubHeader` 服务的；用户要求把那四个字删掉，
 * 两个常量就没用了。**这里把踩坑记录留下**，因为这个坑以后还会有人踩：
 *
 *  * `ListSubHeader` 自带 `defaultMinSize(minHeight = 48.dp)`
 *    （`ListSubHeaderTokens.Height`），而且里面的文字**在整块里垂直居中** ——
 *    所以它上下那 16dp / 8dp 的 `contentPadding` 改了也没用（48dp 的下限压着），
 *    文字中心恒在块顶下方 24dp。48dp 的块一摆下去，标题就被顶到屏幕中间
 *    （这正是 1.976 及以前的现象）。
 *  * 唯一的杠杆是**显式高度**：给 `Modifier.height(h)` 会把外层约束钉成 min=max，
 *    里面的 `defaultMinSize(48.dp)` 见 minHeight 已经不是 0 就不再抬上去。
 *  * ⚠️⚠️ **只给高度不够，`contentPadding` 必须一起改**（两件事缺一不可）：
 *    它默认纵向内边距 16 + 8 = **24dp**，`wrapContentSize` 把内层 max 约束传下去之后
 *    这 24dp 被内边距吃光，留给文字的高度是 **0** —— 标题不是「位置不对」，
 *    是**整个画不出来**（实测：那一行全空，`uiautomator dump` 里连节点都没有）。
 *
 * ⇒ 本轮直接把这一块拿掉了：**能用「不给」解决的问题，就不要用「压尺寸」解决。**
 * 拿掉之后按钮与列表之间那块空白由 [ACTIONS_GAP] 一处说了算。
 */

/**
 * 首页的两页。`ordinal` 直接当页码用，加一页只要在这里加一项
 * （页数读 `entries.size`，不会再出现「枚举加了、页数忘了改」）。
 *
 *  * [ASK] —— 最近对话。问一句就走，不挂人设。
 *  * [CHAT] —— 通讯录。跟角色聊，人设是主角。
 *
 * 这两个名字**同时也是给用户看的**：滑过去时顶部会闪一行 `Ask` / `Chat`
 * （见 [displayName] 与 [ModeFlashLabel]）。**显示写法从枚举名推出来，不另抄一份** ——
 * 所以它们不只是内部代号，改的时候要当成 UI 文案改。
 *
 * 调用方也靠它区分来源：设置页要知道自己是从哪一页打开的
 * （从 chat 打开会多一项「用手机管理人设」）。
 */
enum class HomePage {
    ASK,
    CHAT,
    ;

    /**
     * 给用户看的写法：首字母大写（`Ask` / `Chat`）。
     *
     * 由枚举名推出来而不是再写一遍字面量 —— 「文案和枚举名各写一份」正是
     * 改了一处忘另一处的来源。要换显示写法就改这条推导规则。
     */
    val displayName: String get() = name.lowercase().replaceFirstChar { it.uppercaseChar() }
}

/**
 * 顶部模式提示的停留时长。
 *
 * 「出现」跟着滑动动作走，「消失」不跟：手指一停就消失的话，
 * 想确认「我现在在哪一页」根本来不及看。所以手势结束后再多留 2 秒。
 */
private const val MODE_FLASH_HOLD_MS = 2_000L

/**
 * 模式提示文字的竖直落点。
 *
 * ## ⚠️ 它被按钮的位置来回搬过三次，最后一次是「搬回原处」
 *
 * 1.976 是 **28dp** —— 那时按钮行在 126px 起，文字在按钮**上面**那条空档里。
 * 1.978 第一版把按钮抬到 64px 起，上面没地方了，于是挪到 75dp；
 * 第二版按钮落到 109px 起，又挪到 96dp —— 那两次都只能**塞在按钮和内容之间的缝里**，
 * 用户看到的就是「这行字从顶端跑下去了」。
 *
 * 第三轮（用户反馈后）：按钮宽 90dp → **70dp**，圆屏几何允许它重新上移，
 * 而 [TOP_GAP] 又保住了上沿 ≈49dp ⇒ 按钮上方那条空档**又回来了**，
 * 所以文字搬回 **28dp**，也就是它本来该在的地方。
 *
 * ## 落点怎么算的（340dpi 实测）
 *
 * ⚠️⚠️ **`labelLarge` 的墨迹是 30px 高，不是 20px**（实测：块顶 +9px 起算）。
 * 我按 20px 算过一轮，结果闪字的下沿和按钮上沿**差 1px 贴在一起**——
 * 数字得实测，不能按字号估。
 *
 * ⚠️⚠️ **这条带子的容量是定死的，改按钮位置必须回来重排这条线。**
 * 它的高度 = `按钮上沿 − 时间胶囊墨迹底`。按钮越往上，带子越窄，闪字就越被挤。
 *
 * 340dpi 实测（三次排布）：
 * ```
 *                    时间胶囊底   闪字墨迹        按钮上沿   上留白  下留白
 *   第五轮 TOP_GAP=7.4dp    36px   41–67px(24)     73px      5px     6px   ← 太挤
 *   本轮 TOP_GAP=10dp       36px   45–69px(24)     79px     10px    10px   ← 定稿
 * ```
 * ⇒ **把闪字放在这条带子的正中**，两边留白相等；按钮往上挪就要同步把闪字往上挪，
 * 否则下留白先被吃光（第五轮就是下留白只剩 6px，用户反馈「影响提示小字」）。
 * ⚠️ 带子只剩 37px 的时候闪字**必须**用 `labelMedium`（墨迹 24px）；
 * 换回 `labelLarge`（30px）两边就只剩 3px，看着是粘在时间胶囊上的。
 * 本轮带子 44px，仍用 `labelMedium`（换成 labelLarge 也只剩 7px，没有实质收益）。
 *
 * **它是覆盖层，不进列表布局。** 进了的话每次滑动都会把整页内容顶下去一截，
 * 而第二页那点竖直空间是算到像素的（见 [ContactsPage] 里那段几何推导）——
 * 再顶一下就会被圆边切掉。
 *
 * ⚠️ 它同时受 [TOP_GAP] / [TOP_ACTION_WIDTH] 影响（两个都会挪按钮上沿）。
 * **动按钮位置就必须回来重看这一条。**
 */
private val MODE_FLASH_TOP = 17.5.dp

/**
 * 角色卡片右列（右上角日期 / 右下角钟点）与左侧内容（名字 / 用量）之间的**间隙**。
 *
 * ## 右列怎么对齐（**居右**，别改成居左）
 *
 * 这两项分处两个槽（日期在 `title`、钟点在 `subtitle`），前面各有一个 `weight(1f)`
 * （名字 / 用量）。`weight(1f)` 吃满剩余空间、把后面的 `Text` 顶到 Row 末端，而
 * `Text` 在「被 Row 末端顶住 + 自己的盒子只有文字宽」时的**默认行为就是右对齐**
 * —— 于是上下两行的**右边界自动成一条竖线**，这正是要的效果。
 *
 * ⇒ **不要**给这两个 `Text` 加 `Modifier.width(...)`：一加就把盒子固定成左对齐，
 * 右边界反而参差（`昨天` 比 `23:06` 少 2 个字符 ⇒ 右边会往里缩一截）。
 * 规则是：「右边显示文字和左边是 x 轴上对齐，然后就是
 * **各自居左或居右显示**」—— 左列（名字 / 用量）居左、**右列（日期 / 钟点）居右**，
 * 两列各自成列，中间留一道固定间隙。
 *
 * ## 取值
 *
 * 取 8dp：够把名字和日期分开，又不至于让右列整体左移太多。
 * ⚠️ 两处的 `Spacer` **必须同宽** —— 它参与右列的定位，一处 8dp、一处 4dp 的话
 * 上下两行的右边界就会差 4dp，对齐立刻失效。
 */
private val TimeColumnGap = 8.dp

/**
 * 首页 = **横向分页的两页**。
 *
 *  * 第一页 ask：顶部两颗胶囊（＋ 新建对话 / 齿轮 设置）+ 最近对话列表；
 *  * 第二页 chat：结构照搬第一页，顶部同样是两颗胶囊（左 ＋ = 新建角色、右 齿轮 = 设置），
 *    下面排已创建的角色。
 *
 * 两页各自的 ButtonGroup 几何完全相同（[TOP_ACTION_WIDTH] / [TOP_ACTION_GAP]），
 * 所以左右滑动时那一排按钮不会跳一下。
 *
 * 手势分工交给官方的 `HorizontalPager` + `PagerScaffoldDefaults.gestureInclusion`：
 * 它在**第一页**的左边缘（屏宽 15% 以内）主动放行手势，于是那一块仍然归
 * `SwipeDismissableNavHost` 管 —— 从首页左边缘右滑照常退出应用；
 * 从第二页任意位置往右滑则回到第一页。这套判断是库内建的，不要自己写手势层去抢。
 *
 * 顶部还有一行**模式提示**（`Ask` / `Chat`，见 [ModeFlashLabel]）：
 * 每次滑动都出现，停手 2 秒后淡出。它挂在页面**里面**，
 * 所以是跟着页面一起移动的，不会被 AnimatedPage 那套过渡落在后面。
 *
 * 滚动指示器只留一套：竖的那条**关掉**（`scrollIndicator = {}`），
 * 只留底部的页面圆点。两套同时出现时一个在右侧、一个在底部，看着像两套互相矛盾的
 * 位置提示。
 */
@Composable
fun HomeScreen(
    onOpenConversation: (String) -> Unit,
    onNewConversation: () -> Unit,
    /** 参数是**从哪一页点进设置的** —— 设置页拿它决定要不要多给一个「管理人设」入口。 */
    onOpenSettings: (HomePage) -> Unit,
    onOpenPersona: (String) -> Unit,
    onNewPersona: () -> Unit,
    onOpenPersonaDetail: (String) -> Unit,
) {
    val pagerState = rememberPagerState(pageCount = { HomePage.entries.size })

    // 顺手把内存里那些空壳收走：否则点了十次＋再取消，内存里就躺十条占位，
    // 虽然不显示也不落盘，但没有任何理由留着。放在每次进首页时做一次最省事 ——
    // 从对话页返回时首页会重新进入组合，这一下正好把刚放弃的那条清掉。
    //
    // 放在分页外壳这一层而不是某一页里面：两页共用一个首页生命周期，
    // 挂到某一页上会让「翻到第二页」也触发一次（虽然无害，但没必要）。
    LaunchedEffect(Unit) { ChatEngine.discardEmpty() }

    // 模式提示的显隐。
    //
    // 触发条件用 `PagerState.isScrollInProgress`：它只在**真的在滑动**时为 true，
    // 「滑了但没切页」（拖过一点又弹回去）同样算数 —— 用户要的就是这个，
    // 只要手指横向划过就给他一个「你在哪一页」的反馈。
    //
    // 用官方这个信号，别自己套 `pointerInput` 去数手势：第一页左边缘那块手势
    // 是主动放行给外层导航的（见上面 doc），自己加一层会和
    // `PagerScaffoldDefaults.gestureInclusion` 的判断打架。
    var modeFlash by remember { mutableStateOf(false) }
    LaunchedEffect(pagerState.isScrollInProgress) {
        if (pagerState.isScrollInProgress) {
            modeFlash = true
        } else if (modeFlash) {
            // 手指抬起（含回落动画结束）之后再留 2 秒。中途又滑一下的话，
            // key 变化会让这个协程重启，计时自然重来。
            delay(MODE_FLASH_HOLD_MS)
            modeFlash = false
        }
    }

    HorizontalPagerScaffold(pagerState = pagerState) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            // 官方给 Wear M3 调过的吸附手感（阈值 0.35，最多翻一页）。
            // 用默认的 PagerDefaults.snapFlingBehavior 也能跑，但阈值是 0.5，
            // 在圆屏上「滑了一半又弹回去」的挫败感明显更强。
            flingBehavior = PagerScaffoldDefaults.snapWithSpringFlingBehavior(state = pagerState),
            // 表冠不参与翻页：首页两页各自是可竖滚的列表，表冠留给列表。
            rotaryScrollableBehavior = null,
        ) { page ->
            AnimatedPage(pageIndex = page, pagerState = pagerState) {
                Box(modifier = Modifier.fillMaxSize()) {
                    when (page) {
                        0 -> AskPage(
                            onOpenConversation = onOpenConversation,
                            onNewConversation = onNewConversation,
                            // 来源在这里定死，两页自己不用知道自己是第几页
                            onOpenSettings = { onOpenSettings(HomePage.ASK) },
                        )

                        else -> ContactsPage(
                            onOpenPersona = onOpenPersona,
                            onNewPersona = onNewPersona,
                            onOpenSettings = { onOpenSettings(HomePage.CHAT) },
                            onOpenPersonaDetail = onOpenPersonaDetail,
                        )
                    }

                    ModeFlashLabel(
                        text = HomePage.entries[page].displayName,
                        visible = modeFlash,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = MODE_FLASH_TOP),
                    )
                }
            }
        }
    }
}

/**
 * 顶部那行模式提示：滑到哪一页就闪哪一页的名字（`Ask` / `Chat`），停手 2 秒后淡出。
 *
 * 淡出没有随手写时长，用的是 Wear M3 自己发布的动效规范：
 * `MaterialTheme.motionScheme.defaultEffectsSpec()` —— 它专管**透明度、颜色**
 * 这类不涉及位移的变化，正是淡入淡出该用的那一条。
 * 换成自己填的 `tween(200)` 也能动，但那等于把 M3 在低功耗设备上对
 * 时长与缓动的取舍丢掉，也失去了以后跟着主题一起变的能力。
 *
 * 排版是**最朴素的那一版**：`labelLarge` + `onSurfaceVariant`，不加字距、
 * 不做模糊、不拉伸字形。中间试过「小 + 疏 + 虚化」和「大 + 扁 + 压灰」两版，
 * 都被否掉了 —— **别再加回来的东西**：
 *  * `Modifier.blur` —— 这么小的字上只是把笔画糊在一起，而且 `RenderEffect`
 *    要 API 31+（minSdk 30 上是空操作）；
 *  * `letterSpacing` / `graphicsLayer { scaleX }` —— 用户要的不是这两种「宽」；
 *  * 颜色乘 alpha 压暗 —— 不在这一版里。
 */
@Composable
private fun ModeFlashLabel(
    text: String,
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    val spec = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(animationSpec = spec),
        exit = fadeOut(animationSpec = spec),
    ) {
        Text(
            text = text,
            // ⚠️ 这里原来是 `labelLarge`（墨迹实测 30px）。按钮按参考 app 上移到
            // 73px 之后，时间胶囊墨迹底（36px）与按钮上沿之间只剩 37px ——
            // 30px 的闪字塞进去两边各只剩 3px，看着是「粘」在时间胶囊上的。
            // 降到 `labelMedium`（墨迹约 26px）才留得出呼吸。
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 第一页：ask。
 *
 * 1.978 这一页的用户反馈来回调了三轮，全是版面：
 *  1. 顶部留白 32dp → [TOP_GAP]、按钮 48dp → 44dp → **宽 90dp → 70dp**（见那几个常量）；
 *  2. 按钮上角被圆屏切掉 ⇒ 按**上沿**那条弦重算，把按钮放回 [TOP_GAP] 卡得住的位置；
 *  3. 按钮与内容之间太挤 ⇒ 恢复 [ACTIONS_GAP]（两页都给）；
 *  4. 「最近对话」那四个字 ⇒ **第三轮整块删掉**，列表随之上移 32dp
 *     （顺带把底部汇总行和翻页圆点的重叠解掉了）；
 *  5. 列表末尾多一行「共 N tokens」的合计（只在这里，卡片本身不加用量）；
 *  6. 卡片显示面积调小（见 [CONVERSATION_CARD_PADDING]）。
 * 其余（空态文案、长按菜单、滚动指示器）没动。
 */
@Composable
private fun AskPage(
    onOpenConversation: (String) -> Unit,
    onNewConversation: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val conversations by ChatEngine.conversations.collectAsStateWithLifecycle()
    val notice by ChatEngine.notice.collectAsStateWithLifecycle()
    val settings by ChatEngine.settings.state.collectAsStateWithLifecycle()
    // 哪一行正在重命名（AI 总结中）。在页面级订阅一次、当参数传进列表项 ——
    // 列表项内部各订阅一次状态，滚动时会掉帧。
    val renamingId by ChatEngine.renamingId.collectAsStateWithLifecycle()

    val listState = rememberScalingLazyListState()
    /** 长按某一行之后停在它上面：先出两项菜单（重新命名 / 删除）。 */
    var pendingRenameOrDelete by remember { mutableStateOf<Conversation?>(null) }
    /** 菜单里选了「删除」之后，再确认一次才真删。 */
    var pendingDelete by remember { mutableStateOf<Conversation?>(null) }

    // 历史列表只认「说过话」的会话。首页那个＋只是拿一个 id 就跳进对话页，
    // 用户在空白页什么都没问就返回时，这条记录不该出现在这里。
    //
    // 另外**必须排掉角色会话**：它们挂在第二页的通讯录里，掉进 ask 的「最近对话」
    // 就等于同一条聊天在两个地方各出现一次，而其中一个地方还删不掉它。
    val history = conversations.filter { it.messages.isNotEmpty() && it.personaId == null }

    ScreenScaffold(
        scrollState = listState,
        // 竖滚动指示器关掉，只留底部的页面圆点（见 [HomeScreen] 的注释）
        scrollIndicator = {},
    ) { contentPadding ->
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = contentPadding,
            // 列表非空时必须关掉自动居中：否则第 2 项会被强行滚到屏幕正中，
            // 上面那排按钮会被顶出圆屏上沿。
            autoCentering = null,
            // 关掉自动居中之后，item 默认按屏宽左对齐（圆屏上圆内左边距看着很歪），
            // 显式居中，两个胶囊才会稳稳落在圆心里。
            horizontalAlignment = Alignment.CenterHorizontally,
            // 1.982：缩放加强（取值与理由见 [HOME_SCALING]）。
            scalingParams = HOME_SCALING,
        ) {
            // 原来这里有个应用名标题，现在去掉了：
            // 圆屏最顶那一行本来就窄，留给两个主操作更值。
            //
            // 1.978：这一项从 32dp 先压到 3dp、又调回 [TOP_GAP]（24dp）——
            // 回撤的原因写在 [TOP_GAP] 上：压太狠按钮的上角会被圆屏切掉。
            // 数字怎么来的见 [TOP_GAP] 的注释，别凭感觉改。
            item(key = "top_gap") { GapItem(TOP_GAP) }

            item(key = "actions") {
                TopActionButtons(
                    onAdd = onNewConversation,
                    onOpenSettings = onOpenSettings,
                    addDescription = "新建对话",
                )
            }

            notice?.let { text ->
                item(key = "notice") {
                    NoticeCard(text = text, onDismiss = { ChatEngine.consumeNotice() })
                }
            }

            // ⚠️ 这一项是**恢复到存在**的：1.978 第一版把它整个删掉想让内容上提，
            // 结果用户的反馈是「两按钮和最近对话的间距太小了，看着很紧凑」。
            // 它和 [TOP_GAP] 是**两处不同的空隙**：那个在按钮**上面**（避时间胶囊 + 圆边），
            // 这个在按钮**下面**（避开第一块内容）。别再把两个合成一个。
            item(key = "actions_gap") { GapItem(ACTIONS_GAP) }

            if (settings.apiKey.isBlank()) {
                item(key = "need_key") {
                    ActionCard(
                        title = "还没填 API Key",
                        subtitle = "点上面的齿轮去设置",
                        icon = AppIcons.Settings,
                        onClick = onOpenSettings,
                    )
                }
            }

            if (history.isEmpty()) {
                item(key = "empty") {
                    Card(
                        onClick = onNewConversation,
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    ) {
                        Text(
                            text = "还没有对话\n点 ＋ 开始",
                            modifier = Modifier.fillMaxWidth(),
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            } else {
                // ⚠️ 1.978 第三轮：**「最近对话」这个 `ListSubHeader` 整块删掉**
                // （用户原话：「ask 页的『最近对话』四个字删去」）。
                //
                // 为什么删它不亏：这一行原本是「1.976 及以前列表被顶到屏幕中间」的元凶
                // （它自带 `defaultMinSize(48.dp)`，见 [ListSubHeader] 那段踩坑记录），
                // 为了把它压到目标位置，我们跟它的默认内边距斗了三轮
                // （高度 + `contentPadding` 纵向必须同时给，两件事缺一不可）。
                // 现在整块拿掉，那三个数（[TOP_GAP] 之外的 header 高度/内边距）一起消失，
                // 列表整体上移 32dp，而且**上面那块空白由 [ACTIONS_GAP] 一处说了算**。
                //
                // 顺带解决了一个硬冲突：底部那行「共 N tokens」原来会被翻页圆点压住
                // （圆点在 y457–468，汇总行墨迹 442–461 ⇒ 重叠 5px）。
                // 列表上移之后汇总行落到 y≈370 一带，两者不再打架。
                history.forEach { conversation ->
                    item(key = conversation.id) {
                        ConversationRow(
                            conversation = conversation,
                            renaming = renamingId == conversation.id,
                            onClick = { onOpenConversation(conversation.id) },
                            onLongClick = { pendingRenameOrDelete = conversation },
                        )
                    }
                }
            }

            // 需求：Ask 首页底部安静地给一行**全部问答的 token 汇总**。
            //
            // ⚠️ 位置在 `bottom_gap` **之前**、列表**末尾**：它是整份列表的合计，
            // 不是某一条的用量。
            // ⚠️⚠️ **不要把它理解成「给每张卡片加用量」** —— 项目硬约定
            // 「**ask 第一页列表永不加用量**」，每个 item 一行是违约。
            // 这里只有列表末尾这一条汇总行，卡片本身一个字都不加。
            //
            // 空列表不显示（那时没有「全部问答」可言）。
            //
            // 老会话的 `totalTokens` 就是 0（字段是后加的），**不许写迁移补算** ——
            // 显示成 0 是正确行为，见 `Models.kt` 的注释。
            if (history.isNotEmpty()) {
                item(key = "ask_total_tokens") {
                    // ⚠️⚠️ 1.989：文案按用户要求改成
                    // 「xx 个对话 · 共 xx tokens 消耗」（用户原话：
                    // 「从『共24.8k tokens』到『xx个对话·共24.8k tokens消耗』（1.990 又去掉「最近」二字）。
                    //  若一行太挤可换行显示」）。
                    //
                    // * 条数 = [history].size（「最近对话」列表正是这批会话）；
                    // * **单行放不下就让它自然换行**（fillMaxWidth + 居中，不加
                    //   maxLines/固定宽 —— 那会把换行变裁字，项目栽过的坑）；
                    //   换行后整体仍居中，不影响翻页圆点（那个 12dp 的
                    //   ASK_TOTAL_TOP_GAP 兜底仍在，见下）。
                    // ⚠️⚠️ 1.990：文案「xx 个最近对话」→ **「xx 个对话」**（用户原话）。
                    // 「最近」两个字多余——这一行本来就在「最近对话」列表末尾，
                    // 写「最近」是重复。
                    Text(
                        text = "${history.size} 个对话 · " +
                            "共 ${formatTokens(history.sumOf { it.totalTokens })} tokens 消耗",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        // ⚠️ 这行**必须离翻页圆点那条带子远一点**。
                        // 翻页圆点（`HorizontalPagerScaffold` 的指示器）是**固定**画在
                        // 屏幕底部正中的覆盖层，而这一行的位置随记录数线性下移：
                        //   1 条 → 墨迹 366–388（圆点 456–468）✓
                        //   2 条 → 墨迹 464–478 ⇒ **正好压在圆点上**，视觉上变成
                        //          「共 30.9●k tokens」，两个东西叠在一起 ✗
                        //   3 条以上 → 本来就在折叠线以下，滑到底才看得到 ✓
                        // 顶上加 12dp：2 条时这一行被整体推到折叠线以下（变成「要滑一下才看到」，
                        // 那是列表的正常行为），1 条时是 391–405，离圆点还有 51px ✓。
                        // **别把这 12dp 删掉。**
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = ASK_TOTAL_TOP_GAP),
                    )
                }
            }

            item(key = "bottom_gap") { GapItem(28.dp) }
        }
    }

    // 圆屏上没地方放长按提示，所以长按只是「进入待处理」，真正动手要在这再选一次
    // （选「重新命名」或「删除」，然后各自再确认一次）——
    // 绝不能让长按直接落到「删掉」，那是最容易误触的一步。
    pendingRenameOrDelete?.let { target ->
        WhaleChatMenuDialog(
            title = target.displayTitle,
            onDismiss = { pendingRenameOrDelete = null },
            actions = buildList {
                // 只有 ask 会话给「重新命名」：角色会话的标题恒等于角色名（建会话时就置位了），
                // 叫模型去总结一遍会把角色名冲掉，而且纯属白烧钱。
                // 判据与会话自己的 `personaId` 对齐，不另造一个。
                if (target.personaId == null) {
                    add(
                        DialogAction("重新命名") {
                            pendingRenameOrDelete = null
                            ChatEngine.regenerateTitle(target.id)
                        },
                    )
                }
                add(
                    DialogAction("删除", destructive = true) {
                        pendingRenameOrDelete = null
                        pendingDelete = target
                    },
                )
            },
        )
    }

    pendingDelete?.let { target ->
        WhaleChatDialog(
            title = "删除这个对话？",
            body = "「${target.displayTitle}」和它的全部消息都会一起删掉，无法恢复。",
            confirmLabel = "删除",
            destructive = true,
            onConfirm = {
                ChatEngine.deleteConversation(target.id)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

/**
 * 第二页：chat 模式的通讯录（管的是「人」）。
 *
 * 长按一个角色出三件事：**人设详情 / 重新开始 / 删角色**。
 * 「话」的操作（编辑重发、停止生成、说话 / 打字）全在对话页，那边完全复用 ask 那套，
 * 一个字都没为新模式改。两套操作互不越界 —— 通讯录里不提供任何针对单条消息的东西。
 *
 * 「改名」和「人设详情」里那一行名字是同一个入口、同一条路由（改短字段），
 * 不是两套代码；既然详情页里点得到，菜单里就不重复放一份。
 */
@Composable
private fun ContactsPage(
    onOpenPersona: (String) -> Unit,
    onNewPersona: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenPersonaDetail: (String) -> Unit,
) {
    val personas by ChatEngine.personas.collectAsStateWithLifecycle()
    val notice by ChatEngine.notice.collectAsStateWithLifecycle()
    // 只为把「角色列表显示用量」这个开关传进 PersonaRow。**在这里订阅一次**，
    // 不在列表项内部订阅 —— 每个 item 各订阅一次状态，滚动时会掉帧。
    val settings by ChatEngine.settings.state.collectAsStateWithLifecycle()

    val listState = rememberScalingLazyListState()

    /** 长按某一行之后停在哪个角色上（菜单 / 二次确认都从这里派生）。 */
    var menuFor by remember { mutableStateOf<Persona?>(null) }
    /** 正在确认「重新开始」。 */
    var restartTarget by remember { mutableStateOf<Persona?>(null) }
    /** 正在确认「删角色」。 */
    var deleteTarget by remember { mutableStateOf<Persona?>(null) }

    ScreenScaffold(
        scrollState = listState,
        scrollIndicator = {},
    ) { contentPadding ->
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = contentPadding,
            autoCentering = null,
            horizontalAlignment = Alignment.CenterHorizontally,
            // 与第一页同一个缩放强度（理由见 [HOME_SCALING]：两页左右滑动，
            // 强度不一致会「跳一下」）。
            scalingParams = HOME_SCALING,
        ) {
            item(key = "top_gap") { GapItem(TOP_GAP) }

            item(key = "actions") {
                TopActionButtons(
                    onAdd = onNewPersona,
                    onOpenSettings = onOpenSettings,
                    addDescription = "新建角色",
                )
            }

            notice?.let { text ->
                item(key = "notice") {
                    NoticeCard(text = text, onDismiss = { ChatEngine.consumeNotice() })
                }
            }

            // 与第一页同一个 [ACTIONS_GAP]（1.976 这里是 0dp，最挤的就是这一页）。
            item(key = "actions_gap") { GapItem(ACTIONS_GAP) }

            // ── 第二页的竖直几何（1.978 重算过三轮）──────────────────────────
            // 这一页没有 `ListSubHeader`，所以首屏那张角色卡片的位置由
            // [TOP_GAP] + 按钮高度 + [ACTIONS_GAP] + 项间距决定。按 **2.125 px/dp**
            // （模拟器 340dpi，用户那批参考图的实际密度）× 480px 圆屏（半径 240px）算：
            //
            //   卡片：428px 宽 × ≈145px 高（居中、未缩放时），圆角半径 ≈55px。
            //   圆屏在 y 处的弦宽 = 2*sqrt(240² − (y−240)²)。
            //   卡片是**圆角矩形**，判定用它的**最宽那条中线**（顶边往下 72.5px）——
            //   那是它左右两侧真正顶到 x=26/454 的地方；上下两条边因为圆角，
            //   在端点处已经收进去了（1.976 的注释就是按这条判的，别再换成「上下边」）。
            //
            // 1.978 第二轮的落点（行宽已收窄到 144dp）：
            //   contentPadding.top ≈23dp(49px) + 项间距 8.5 + top_gap 18dp(38px)
            //   ⇒ 按钮顶 ≈96px、高 44dp(93.5px) ⇒ 按钮底 ≈190px
            //   ⇒ 卡片顶 = 190 + 8.5 + actions_gap 10dp(21px) + 8.5 ≈ 228px
            //   ⇒ 中线 = 228 + 72.5 = 300.5px
            //   该处弦宽 = 2*sqrt(240² − 60.5²) ≈ 465px ≥ 428px ✓（余量 37px）
            //   顺带核按钮自己：上沿 96px 处弦宽 ≈ 411px ≥ 行宽 306px ✓（**余量很大**）
            //   （**第一版就是漏了「上沿」这一步才被切角的**，见 [TOP_GAP]）
            //
            // ⚠️ 1.976 及以前这一页的首卡顶在 248px 附近，当时是靠「去掉 ListSubHeader +
            // actions_gap 给 0dp」硬抠出来的。本轮不必再抠，但**结论要按上面重算**：
            // 动 [TOP_GAP] / [ACTIONS_GAP] / [TOP_ACTION_WIDTH] 之后必须重新截图确认四角没被切。
            //
            // ⚠️ 另外那条「第一页的『最近对话』属于『一个字都不要动』」的旧说法，
            // **在 1.978 第三轮彻底作废**：那个标题最后是被**删掉**的（不是「一个字都不要动」，
            // 也不是「挪到某个 y」）。见上面 AskPage 里那段注释。
            if (personas.isEmpty()) {
                item(key = "empty") {
                    Card(
                        onClick = onNewPersona,
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    ) {
                        Text(
                            text = "还没有角色\n点 ＋ 造一个",
                            modifier = Modifier.fillMaxWidth(),
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            } else {
                // 直接排卡片，不加 ListSubHeader —— 原因见上面那段几何推导。
                personas.forEach { persona ->
                    item(key = persona.id) {
                        PersonaRow(
                            persona = persona,
                            showUsageOnPersona = settings.showUsageOnPersona,
                            onClick = { onOpenPersona(persona.id) },
                            onLongClick = { menuFor = persona },
                        )
                    }
                }
            }

            item(key = "bottom_gap") { GapItem(28.dp) }
        }
    }

    // 长按 → 三选一。就这三件事，不设下拉菜单、不加二级页面。
    menuFor?.let { persona ->
        WhaleChatMenuDialog(
            title = persona.name,
            onDismiss = { menuFor = null },
            actions = listOf(
                DialogAction("人设详情") {
                    menuFor = null
                    onOpenPersonaDetail(persona.id)
                },
                DialogAction("重新开始") {
                    menuFor = null
                    restartTarget = persona
                },
                DialogAction("删角色", destructive = true) {
                    menuFor = null
                    deleteTarget = persona
                },
            ),
        )
    }

    // 「重新开始」= 人设不动、清空消息、把存的 greeting 再上架一次。不联网、瞬间完成，
    // 所以文案要写明它做什么，不要写成「重置」这种含糊的词。
    restartTarget?.let { persona ->
        WhaleChatDialog(
            title = "跟 ${persona.name} 重新开始？",
            body = "和 TA 的聊天记录会一起清空，无法恢复。TA 会说回那句老开场白。",
            confirmLabel = "重新开始",
            onConfirm = {
                ChatEngine.restartPersona(persona.id)
                restartTarget = null
            },
            onDismiss = { restartTarget = null },
        )
    }

    deleteTarget?.let { persona ->
        WhaleChatDialog(
            title = "删掉 ${persona.name}？",
            body = "和 TA 的聊天记录会一起删掉，无法恢复。",
            confirmLabel = "删除",
            destructive = true,
            onConfirm = {
                ChatEngine.deletePersona(persona.id)
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null },
        )
    }
}

/**
 * 顶部那两颗胶囊。**两页共用同一份实现**，为的是几何完全一致。
 *
 * 用 **ButtonGroup** 而不是 Row + weight。Wear M3 里这一排按钮的正确容器就是
 * ButtonGroup，它比 Row 多出来的一件事正好是我们要的：`Modifier.animateWidth()`。
 * 那颗 Modifier 只在 ButtonGroup 里有意义 —— 按下时**这颗按钮按
 * ButtonGroupDefaults.ExpansionWidth（24dp）涨出去、旁边那颗同步缩回来，
 * 总宽一动不动**。变窄的那颗宽度缩到接近高度时，胶囊形状自然就变成一个圆。
 * 参考 app（微思应用商店）那两个胶囊按下后的形变就是这个 ——
 * 不是自己画的动画，是官方容器自带的按压反馈。
 *
 * [addDescription] 只用于无障碍描述，两页分别写「新建对话」「新建角色」。
 */
@Composable
private fun TopActionButtons(
    onAdd: () -> Unit,
    onOpenSettings: () -> Unit,
    addDescription: String,
) {
    ButtonGroup(
        modifier = Modifier.width(TOP_ACTION_ROW_WIDTH),
        spacing = TOP_ACTION_GAP,
        // 默认的 fullWidthPaddings() 是给「占满全屏宽」的用法算的
        // （左右各留屏高的 5.2%）。这里总宽已经被圆屏几何钉死在 184dp 了，
        // 再吃一遍内缩两颗都会变窄、还会被顶出圆屏上沿。
        contentPadding = PaddingValues(0.dp),
    ) {
        // 每颗胶囊一个独立的 interactionSource：animateWidth 全靠它听按压状态。
        // 共用一颗的话，按左边会把右边也一起撑开。
        val addPress = remember { MutableInteractionSource() }
        val settingsPress = remember { MutableInteractionSource() }

        Button(
            onClick = onAdd,
            modifier = Modifier
                .height(TOP_ACTION_HEIGHT)
                .animateWidth(addPress),
            interactionSource = addPress,
            // 宽度由 ButtonGroup 定，内边距清零，居中交给里面那个 fillMaxWidth。
            //
            // 以前是靠一组**对称 padding**（水平 34dp）把图标顶到正中的，
            // 那个数只在「宽度恒为 90dp」时成立 —— 现在按下会变宽/变窄，
            // 硬编码的 padding 立刻歪掉。
            //
            // 顺带纠正一条以前的误解：Button 内部是 `width(IntrinsicSize.Max)`
            // 的 Row，约束**不定宽**时它只按内容量，那才是 fillMaxWidth 撑不开的
            // 真实原因；ButtonGroup 会给每颗按钮一个定宽约束，这里就撑得开了。
            contentPadding = PaddingValues(0.dp),
        ) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = AppIcons.Add,
                    contentDescription = addDescription,
                    modifier = Modifier.size(TOP_ACTION_ICON_SIZE),
                )
            }
        }

        FilledTonalButton(
            onClick = onOpenSettings,
            modifier = Modifier
                .height(TOP_ACTION_HEIGHT)
                .animateWidth(settingsPress),
            interactionSource = settingsPress,
            contentPadding = PaddingValues(0.dp),
        ) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = AppIcons.Settings,
                    contentDescription = "设置",
                    modifier = Modifier.size(TOP_ACTION_ICON_SIZE),
                )
            }
        }
    }
}

@Composable
private fun ConversationRow(
    conversation: Conversation,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    /** 这一行正在被重新命名（AI 总结中）。true 时副标题位置换成变形进度条。 */
    renaming: Boolean = false,
) {
    TitleCard(
        onClick = onClick,
        // 1.978 第三轮：卡片显示面积调小（统一值 + 理由见 [CARD_CONTENT_PADDING]）。
        // ⚠️ `height` 不能省：只给 contentPadding 的话 `defaultMinSize(64.dp)` 会把卡片撑回去。
        modifier = Modifier.fillMaxWidth().height(CARD_HEIGHT),
        contentPadding = CARD_CONTENT_PADDING,
        title = {
            Text(
                text = conversation.displayTitle,
                // 1.978 第五轮：用户要求「卡片的标题字样稍微做小一点」。
                // `titleMedium`(16sp) → `titleSmall`(14sp)，**只动会话卡片**（就是用户
                // 指着的那批）；角色卡片的名字维持 `titleMedium` —— 那是「谁在说话」，
                // 层级要比一句提问高。副标题照旧 `labelSmall`(11sp)，标题/副标题仍有 3sp 落差。
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // ⚠️⚠️ 1.986：**挂 `align(CenterVertically)`**（「标题过于靠上」的修法）。
                //
                // 1.985 曾把卡高压到 50dp 想消灭「Column 底部剩余空间」，
                // 用户实测「卡片怎么改的好小」⇒ 1.986 回到 56dp，偏上问题换杠杆解决：
                //
                // `TitleCard` 的 title 行是 `verticalAlignment = Alignment.Top`
                // （库源码 `Card.kt:1096`）⇒ 标题贴着行顶。挂 RowScope 的
                // `align(CenterVertically)` 后，**title 行盒在自己的可用高里垂直居中**
                // —— 剩余空间不再全落在底部，观感回正。
                //
                // ⚠️ 通讯录那张卡（`PersonaRow`）的 title 槽**从一开始就挂着这个 align**，
                // 两页行为不一致也是「偶发」错觉的来源之一（只在 ask 首页这批卡上明显）。
                modifier = Modifier.align(Alignment.CenterVertically),
            )
        },
        subtitle = {
            if (renaming) {
                // 重命名要发一次 `complete()` 调用，来回可能好几秒。
                // 没有反馈的话用户点完菜单那一下就什么都看不到，只能猜是不是坏了。
                // 进度条用和「捏人中 / 正在思考」同一个 M3E 变形环，三处等待语言一致。
                Row(verticalAlignment = Alignment.CenterVertically) {
                    WavyProgressIndicator(diameter = 24.dp, compact = true)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "正在总结…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Text(
                    text = relativeTime(conversation.sortKey),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    // ⚠️ 1.985：subtitle 槽是 **ColumnScope**，`align` 只收
                    // `Alignment.Horizontal` ⇒ **没有垂直居中可用**（挂了编译不过）。
                )
            }
        },
        onLongClick = onLongClick,
        // 1.978 起长按出的是**两项菜单**（重新命名 / 删除），不再是「直接进待删除」，
        // 所以无障碍描述也跟着改 —— 还写「删除对话」会让读屏用户以为长按就会删。
        onLongClickLabel = "更多操作",
    )
}

/**
 * 通讯录里的一行。
 *
 * 版面是**两行、四个角各放一样东西**：
 * ```
 * 小夜                        昨天    ← 名字 titleMedium（左）；日期右对齐（右）
 * 19.2k tokens               19:32    ← 用量（左）；钟点右对齐（右）
 * ```
 *
 *  * 没聊过 → **两行都不出时间和用量**，副标题写「还没聊过」（那时能拿来算时间的
 *    只有创建时间，写出来是「刚刚」，等于说「你刚建了 TA」，不是说「你们聊过」）；
 *  * 聊过、拿到用量 → 左下角写 `19.2k tokens`（**带复数 s**）；
 *  * 聊过、没拿到用量 / 开关关掉 → 左下角**空着**（用 `Spacer(weight)` 顶开），
 *    右下角的钟点**必须还在**。
 *
 * ## 时间从右上角挪走，拆成右上日期 + 右下钟点
 *
 * 更早时右上角是 `lastChatTime()`（同一自然日给 `HH:mm`、跨日给 `N 天前`）。
 * 后来改成：**右上角给日期**（`今日`/`昨天`/`M月d日`），
 * **右下角给钟点**（`HH:mm`）。所以副标题槽从一个 Text 变成了一个 `Row`。
 *
 * ⚠️ 联动里最容易写错的一处：**用量开关关掉时，右下角那个钟点必须还在。**
 * 把两个信息拼成一个字符串再整体判 `null` 是最省事的写法，也是**假绿**的写法 ——
 * 「关掉用量，时间也没了」。要拆成 Row 里的两个子项分别判，左边没内容用
 * `Spacer(Modifier.weight(1f))` 顶开。
 *
 * ⚠️ **时间不用 `TitleCard` 的 `time` 槽**，两道理由：
 *  1. `time` 只在 `content != null` 时才贴到名字同一行右侧；而 `content != null`
 *     会把 subtitle 上方间距从 2dp 顶成 6dp，**卡片凭空高 4dp**（第二页几何是卡死的）；
 *  2. `time` 槽被 `timeWithTextStyle()` 强制套上 `TimeTypography`（bodyMedium，
 *     14sp/字重 450），比现在的 `labelSmall`（13sp/字重 500）更大更粗 ——
 *     用户明确说了「原先显示时间的字体大小效果就挺好」，**别再缩、也别换大**。
 *
 * 所以名字和时间都放进 `title` 槽（它的类型是 `RowScope.() -> Unit`，本来就在那层
 * `Row(Modifier.weight(1f))` 里，可以直接放多个子项）。副标题那个 Row 同理，
 * `subtitle` 的类型是 `ColumnScope`，里面当然可以再套一个 `Row`。
 *
 * [Persona.tagline]（那句简介）**不进这一行**：角色是用户自己造的，他不需要在列表上
 * 被反复提醒「TA 是什么样的人」，时间和用量才是他每次打开列表真正要看的。
 *
 * [showUsageOnPersona] 是设置里那个开关的值，由调用方订阅后**当参数传进来**，
 * 不在这里内部订阅 settings（列表项里重复订阅状态，滚动会掉帧）。
 * **开关只管左下角那个用量** —— 关掉时右上角的日期和右下角的钟点照常显示。
 */
@Composable
private fun PersonaRow(
    persona: Persona,
    showUsageOnPersona: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val conversation = persona.id.let { id ->
        ChatEngine.personaConversation(id)
    }
    val tokens = conversation?.totalTokens ?: 0L
    val tokenLine = if (showUsageOnPersona && tokens > 0L) {
        "${formatTokens(tokens)} tokens"
    } else {
        null
    }
    val chatted = persona.lastChatAt > 0L
    TitleCard(
        onClick = onClick,
        // 与会话卡片共用同一组值：两页卡片大小不一致的话，滑动时会看出「一张鼓出来」。
        modifier = Modifier.fillMaxWidth().height(CARD_HEIGHT),
        contentPadding = CARD_CONTENT_PADDING,
        title = {
            Text(
                text = persona.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // ⭐ weight 给名字、不给 Spacer。Row 先量非 weight 的子项，
                // 名字会把想要的宽度全拿走、把时间挤成 0 宽（表现为「时间不见了」，
                // 而短名字时看起来完全正常）。weight 给名字 + Ellipsis，长名字截断、时间永远在。
                modifier = Modifier
                    .weight(1f)
                    .align(Alignment.CenterVertically),
            )
            if (chatted) {
                // ⚠️ 这个间隙必须和 subtitle 槽里那个**同宽**，否则上下两行的右列
                // 左右边界会各差一截（那样「对齐」就白做了）。见 [TimeColumnGap]。
                Spacer(modifier = Modifier.width(TimeColumnGap))
                Text(
                    // 右上角 = 日期。更早那版这里是 `lastChatTime()` 的
                    // 「HH:mm / N 天前」，规则已作废 —— 别改回去。
                    text = lastChatDate(persona.lastChatAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    // ⭐ 库里那两层 Row 的 verticalAlignment 都写死 Top，槽里改不了 Row 的对齐，
                    // 只能给子项自己 align。不写这行的话 16sp 名字和 13sp 日期都贴顶，行首差一截。
                    //
                    // ⭐⭐ 右列**居右**（别加 `width()`）：名字的 `weight(1f)` 吃满剩余空间，
                    // 把这个 `Text` 顶到 Row/卡片右端；盒子只有文字宽、又被顶住 ⇒ 默认就是右对齐，
                    // 上下两行的右边界自动成一条竖线。
                    //
                    // ⚠️ **别给这个 Text 加 `Modifier.width(...)`**：一加盒子就固定成左对齐，
                    // 右边界反而参差（`昨天` 比 `23:06` 少 2 个字符 ⇒ 右边会往里缩一截）。
                    // 规则：「各自居左或居右显示」= 左列居左、右列居右。
                    //
                    // ⚠️ 前面那个 `Spacer(TimeColumnGap)` 是**两处都要有**的：名字（或用量）
                    // 的 `weight(1f)` 会把右列顶走，没有这个间隙的话名字和日期会贴着写。
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
            }
        },
        // 副标题是一个 Row：**左用量、右钟点**，两个信息各判各的。
        //
        // 三种情况分开处理（这是本版最容易假绿的一处，见上面 KDoc）：
        //  1. 没聊过 → 整槽给「还没聊过」，**不出时间也不出用量**；
        //  2. 聊过 → 出 Row：左边用量（没有就用 Spacer 顶开），右边钟点恒在；
        //  3. 没内容且没聊过 —— 已在 1 里覆盖，这里不必再写 `null` 分支。
        //
        // 注：`subtitle` 的类型是可空的 `@Composable (ColumnScope.() -> Unit)?`，
        // 传 `null` 就是「不渲染这一槽」；**别传 `Text("")`** —— 空 Text 仍占一整行
        // 16sp 行高，卡片会凭空长高，而界面上完全看不出来。
        subtitle = if (!chatted) {
            {
                Text(
                    text = "还没聊过",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (tokenLine != null) {
                        Text(
                            text = tokenLine,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            // 和名字同一个道理：用量先量、钟点会被挤掉。weight 给左边那个。
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        // ⚠️ 用量关掉时**必须留一个占位**把钟点顶到右端。直接不渲染左边，
                        // 右侧那个 Text 会贴到左边缘 —— 时间和名字一样长时会看不出来，
                        // 短名字 + 短时间时一眼就歪。用 Spacer 承担 weight，不要用
                        // `Arrangement.SpaceBetween`：那会让「有关键内容时」的间距也跟着变。
                        Spacer(modifier = Modifier.weight(1f))
                    }
                    // ⚠️ 与 title 槽里那个间隙**同宽**（见 [TimeColumnGap]），
                    // 否则上下两行的右列会各差一截。
                    Spacer(modifier = Modifier.width(TimeColumnGap))
                    Text(
                        // 右下角 = 钟点，恒为 HH:mm，跟右上角的日期是一对。
                        //
                        // ⭐⭐ 右列**居右**（别加 `width()`）：左边用量的 `weight(1f)` 把这里
                        // 顶到 Row 末端 ⇒ 默认右对齐，和右上角的日期右边界落在同一条竖线。
                        // 别加 `Modifier.width(...)`、也别改成 `IntrinsicSize.Max` —— 会把
                        // 右列的右边界打散（`昨天` 和 `23:06` 会各缩各的）。
                        text = lastChatClock(persona.lastChatAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        },
        onLongClick = onLongClick,
        onLongClickLabel = "更多操作",
    )
}

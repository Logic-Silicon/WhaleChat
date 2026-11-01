package com.naoh.whalechat.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberOverscrollEffect
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyListState
import androidx.wear.compose.material3.AlertDialog
import androidx.wear.compose.material3.AlertDialogDefaults
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CardDefaults
import androidx.wear.compose.material3.CompactButton
import androidx.wear.compose.material3.Dialog
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffoldDefaults
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TextToggleButtonDefaults
import androidx.wear.compose.material3.TextToggleButtonShapes
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 列表里的纯留白项。
 *
 * 圆屏上想让内容避开时间胶囊，只能靠真实 item 撑开：
 * `ScalingLazyColumn` 关掉 autoCentering 之后 `contentPadding.top` 推不动第一项，
 * 只有插空白 item 才有确定的像素位移。
 */
@Composable
fun GapItem(height: Dp) {
    Box(Modifier.size(1.dp, height))
}

/**
 * 一次性提示条：缺 Key、请求被拒之类的本地消息。
 *
 * ⚠️ **这个卡片在对话页里必须排在消息列表末尾**。
 * 更早它在列表第 1 项，后果是每次提示出现都要把用户从底部闪到顶端、
 * 再自己滑回来。位置归调用点管（`ChatScreen`），
 * 这里只管长什么样 —— 但换位置的时候别把它又摆回列表开头。
 */
@Composable
fun NoticeCard(text: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        onClick = onDismiss,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.filledTonalButtonColors(),
            ) {
                Text("知道了")
            }
        }
    }
}

/**
 * 带头行图标的小卡片，首页和设置页共用。
 *
 * ## 1.982：多了一个 [contentPadding] 形参
 *
 * 起因是设置页那两张「一行文字却有两行高度」的卡（见
 * `SettingsScreen.SINGLE_LINE_CARD_HEIGHT`）：要压掉 `Card` 自带的
 * `defaultMinSize(64.dp)`，**唯一有效杠杆是显式 `Modifier.height(h)`**
 * （从 [modifier] 传进来），而**给了高度就必须把纵向内边距一起压到 0** ——
 * 否则内边距把内容吃光、`Text` 量成 0 高、**整张卡画不出来**。
 *
 * 所以这里把 `contentPadding` 开放出来，**默认值仍是库的
 * `CardDefaults.ContentPadding`（横 12dp / 纵 12dp）** ——
 * 其余十几个调用点一个字都不用改，行为与 1.981 完全一致。
 *
 * ## ⚠️⚠️ 1.982 补：压掉纵向内边距会让文字**贴到卡片上沿**（实测踩到）
 *
 * 这是我第一版漏掉的一件事：**「卡片矮了」和「文字在卡片里居中」是两件事**。
 *
 * * `Card` 内部的容器是个 `Column`，用的是**默认的 `Arrangement.Top`**；
 * * 纵向 `contentPadding` 压到 0 之后，**唯一把文字往下推的东西就没了**；
 * * 而 `Modifier.height(h)` 又把那个 `Column` 钉成正好 h ⇒
 *   多出来的空间**全落在文字下面**。
 *
 * 340dpi 实测（`h = 48dp`、卡片 102px、文字 49px）：
 * **上空隙 0px / 下空隙 53px** —— 文字贴在顶上，肉眼一眼能看出偏。
 *
 * 修法是给内容行挂 [Modifier.fillMaxHeight] + `verticalAlignment = CenterVertically`：
 * `fillMaxHeight` 只在**高度有界**时生效（`Size.kt` 的 `FillNode.measure`：
 * `if (constraints.hasBoundedHeight && …)`），所以
 *  * **钉了高度的卡**（设置页这两张）⇒ 行撑满 h，里面那行文字/图标**垂直居中**；
 *  * **没钉高度的卡**（其余十几个调用点，进 lazy 列表时 maxHeight 是 `Infinity`）
 *    ⇒ `fillMaxHeight` 是**空操作**，行为与 1.981 逐字一致。
 */
@Composable
fun ActionCard(
    title: String,
    subtitle: String? = null,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = CardDefaults.ContentPadding,
    /**
     * 卡片内容色。默认 `onSurface`（= 2.1 及以前全部 20 个调用点的行为，逐字不变）。
     *
     * ⚠️ 2.2 新增的唯一用途：设置页「清空对话」要「灰容器 + error 色图标文字」
     * （破坏性语义，和长按菜单里破坏性项同一套判断，见 `WhaleChatMenuDialog` 的 KDoc）。
     * 传 `colorScheme.error` 时图标（走 `LocalContentColor`）和标题**一起**变色 ——
     * 不要只给 `Text` 上色、把图标漏掉：那种「图标还是白的」最像没做完。
     */
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    /**
     * 标题最多几行。默认不限（原有调用点不受影响）。
     *
     * ⚠️ 2.2：设置页「清空对话 · 共 N 条」必须钉成 1 行 + Ellipsis —— 这个值在 1.978
     * 是**实测**出来的：可用宽约 214dp，`titleMedium` 下「清空所有对话 · 共 999 条」已经
     * 超过一行，不钉行数就会换行、整张卡长高，把圆屏下沿的东西顶出去。
     */
    titleMaxLines: Int = Int.MAX_VALUE,
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        contentPadding = contentPadding,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            contentColor = contentColor,
            // ⚠️⚠️ **可点 `Card` 的 `LocalContentColor` 给的是 `titleColor`，不是
            // `contentColor`**（Wear M3 1.5.6 `Card.kt:139`：
            // `LocalContentColor provides colors.titleColor`；只有不可点的那个重载才用
            // `colors.contentColor`，见 `Card.kt:379`）。
            // 只传 `contentColor` 时界面**像素级不变、也不报错** —— 实测标题仍是
            // `onSurface`(234,226,245)，而不是 error。所以两个都要给。
            titleColor = contentColor,
        ),
    ) {
        // ⚠️⚠️ 1.982 补：外面套一个**撑满**的 `Box`，只做**垂直**居中。
        //
        // 为什么需要它（实测踩到）：`Card` 内部容器是个 `Column`、
        // 用的是默认的 `Arrangement.Top`；纵向 `contentPadding` 压到 0 之后，
        // **唯一把内容往下推的东西就没了**，而 `Modifier.height(h)` 又把那个 `Column`
        // 钉成正好 h ⇒ 多出来的空间**全落在内容下面**。
        // 340dpi 实测（h=48dp / 卡片 102px / 文字 49px）：**上空隙 0px、下空隙 53px**。
        //
        // ⚠️⚠️ **必须是 `Alignment.CenterStart`，不能写 `Alignment.Center`。**
        // `Alignment.Center` 是**两个方向都居中**，会对横向也生效 ——
        // 而 `ActionCard` 的观感一直是「图标 + 文字**靠左**」，
        // 横向居中是**这一版不允许引入的回归**（它会影响全部 20 个调用点）。
        // `CenterStart` = 垂直居中 + 水平起点对齐 ⇒ 横向行为与 1.981 **逐字相同**。
        //
        // ⚠️ 对**没钉高度**的调用点（其余 20 处，进 lazy 列表时 maxHeight 无界）
        // 这里整段是**空操作**：`fillMaxSize` 的 `FillNode` 在 `hasBoundedHeight == false`
        // 时原样透传约束 ⇒ Box 高度 = 内容自然高 ⇒ 垂直居中无处可移。
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.CenterStart,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier
                            .size(20.dp)
                            .padding(end = 6.dp),
                    )
                }
                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = titleMaxLines,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!subtitle.isNullOrBlank()) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 贴边按钮与列表内容之间留多少空。
 *
 * 库默认是 `ScreenScaffoldDefaults.EdgeButtonSpacing`（16dp）。1.981 压到**库允许的最小值**
 * （`EdgeButtonMinSpacing`，3dp）—— 用户要的是「中间那片留白小一点」。
 *
 * ⚠️ 这是 `ScreenScaffold` 的**公开参数**，不是我硬塞的 padding。它同时决定
 * 「列表底部内边距」和「按钮满高」两个量（库里两处读的是同一个值，见
 * `ScreenScaffold.kt` 的 `effectiveEdgeButtonSpacing`），所以一处取值两边一致、不会失配。
 *
 * ⚠️ 附带一条实测结论（用来核对有没有改坏）：`edgeButtonSpacing` **不会**改变按钮
 * 满高那一刻的高度 ——
 * ```
 * 列表底部内边距 = intrinsicButtonHeight + (spacing − minSpacing)
 * 按钮满高       = 列表底部内边距   − (spacing − minSpacing) = intrinsicButtonHeight
 * ```
 * 两处 `(spacing − minSpacing)` 正好抵消。所以调它只动「内容 ↔ 按钮」那条缝。
 */
val BackEdgeButtonSpacing: Dp = ScreenScaffoldDefaults.EdgeButtonMinSpacing

/**
 * 贴边按钮必须挂的 `Modifier.scrollable(...)`。
 *
 * **官方 Wear Lists 指南「列表中的边缘按钮」一节的示例里就带这一句**，原文注释：
 * > Apply overscroll to the EdgeButton for proper scrolling behavior.
 *
 * 它把按钮接进列表**同一条滚动链路**（`reverseDirection = true`）：
 * 手指落在按钮上往上拖，列表照样跟着滚，按钮也随之浮起/沉下。
 *
 * ⚠️ **五处贴边按钮（聊天页 / 扫码页 / 语音识别 / 显示设置 / 关于）一个都不能漏。**
 * 漏掉的后果不是「少个手势」：按钮会把落在它身上的触摸整个吃掉，按钮的滚动状态
 * 和列表对不上，而 `ScreenScaffold` 的高度恰恰由 `isScrollInProgress` 决定走
 * `snapTo` 还是 `animateTo` 分支（见 `ScreenScaffold.kt`），
 * 于是高度会在两个分支之间来回切 —— 1.980 报上来的那次「像果冻一样自己一直上下起伏」，
 * 复现数据见 `_1980_work/jiggle*.py` 与 `WHALECHAT_1.981_修复报告.md`。
 *
 * ⚠️ `overscrollEffect` 必须是**现取一个** `rememberOverscrollEffect()`：
 * 官方示例就是这么写的，注释里点名了「否则滚动行为不对」。
 */
@Composable
fun Modifier.edgeButtonScrollable(state: ScalingLazyListState): Modifier =
    this.scrollable(
        state = state,
        orientation = Orientation.Vertical,
        reverseDirection = true,
        overscrollEffect = rememberOverscrollEffect(),
    )

/**
 * 同一天内的钟点。**应用里只此一份 24 小时制定义** —— chat 卡片的时间也复用它，
 * 不另起 `SimpleDateFormat`（`SimpleDateFormat` 不是线程安全的，两份还会改一处漏一处）。
 *
 * 已知取舍：写死 `HH:mm`，**不跟随系统的 12/24 小时制**。所以系统设成 12 小时制时，
 * 顶部时间胶囊（跟系统）显示 `7:32`、卡片显示 `19:32`，两处不一致。
 * 全项目一个格式比「跟系统」更重要，且 12 小时制要靠 `Context` 拿
 * （`DateFormat.getTimeFormat`），会把这里的函数签名拖上 `Context` —— 不值。
 */
private val sameDay = SimpleDateFormat("HH:mm", Locale.CHINA)
private val thisYear = SimpleDateFormat("M月d日", Locale.CHINA)
private val fullDate = SimpleDateFormat("yyyy年M月d日", Locale.CHINA)

/** 列表里的时间：越近越具体，越远越粗略。 */
fun relativeTime(millis: Long, now: Long = System.currentTimeMillis()): String {
    val diff = now - millis
    if (diff < 0) return sameDay.format(Date(millis))
    return when {
        diff < 60_000L -> "刚刚"
        diff < 3_600_000L -> "${diff / 60_000L} 分钟前"
        diff < 86_400_000L -> "${diff / 3_600_000L} 小时前"
        diff < 172_800_000L -> "昨天"
        diff < 604_800_000L -> "${diff / 86_400_000L} 天前"
        diff < 31_536_000_000L -> thisYear.format(Date(millis))
        else -> fullDate.format(Date(millis))
    }
}

/**
 * ⚠️ 这里原来有一个 `lastChatTime()`：同一自然日给钟点、跨日给 `N 天前`（一直数天数）。
 * **已删除**，因为改了 chat 卡片的时间形态 ——
 * 现在是右上角 [lastChatDate]（日期）+ 右下角 [lastChatClock]（钟点）。
 *
 * 删除而不是留着：留着会有人以为「跨日给几天前」那条规则还在，改回旧形态。
 *
 * ⚠️ [relativeTime]（ask 第一页）**不受这次改动影响**，两页时间档位故意不一样这条仍然成立。
 */

/**
 * 消息气泡右上角那个时间。
 *
 * ⚠️ **只用在用户自己的气泡上**：助手回答不挂时间。
 * 判据在调用处（`ChatScreen.MessageCard` 的 `isUser && showBubbleTime`），
 * 这个函数本身不知道是谁的气泡。
 *
 * 档位是**两段拼的**：日期部分越近越粗略、钟点只在当天/昨天给。
 *
 *  * 今天 → `HH:mm`
 *  * 昨天 → `昨天 HH:mm`
 *  * 更早、同年 → `M月d日 HH:mm`
 *  * 跨年 → `yyyy年M月d日`（**不带钟点**）
 *
 * ## 为什么复用它而不是另写一套
 *
 * 判据是**自然日**（[dayDiff]），跟 [lastChatDate] 同一套。`yesterday` 那一档
 * 不能写成 `now - millis < 172_800_000L`（48 小时窗口）—— 那是 [relativeTime] 的算法，
 * 会把「前天 23:00 说、今天 01:00 看」（26 小时前）判成「昨天」。**两套算法别混。**
 *
 * ## 为什么跨年那档砍掉钟点
 *
 * `yyyy年M月d日 HH:mm` 在这个宽度上是 17 个字符，放不进气泡名字那行的右端
 * （名字本身还要占地方）。而且跨年的消息本来就少、也不差那点精度 —— 年份比钟点重要。
 * **别为此把字号缩到 11sp 以下**：气泡里最小可读的是当前这个 `labelSmall`(13sp)。
 *
 * @param now 注入「现在」是为了能在脚本里断言边界，照 [relativeTime] 的写法。
 */
fun bubbleTime(millis: Long, now: Long = System.currentTimeMillis()): String {
    val days = dayDiff(millis, now)
    val clock = sameDay.format(Date(millis))
    return when {
        // 未来时间（手表时钟被改过）当作「今天」，给钟点，别显示成负数的「前天」。
        days <= 0L -> clock
        days == 1L -> "昨天 $clock"
        startOfDay(millis) >= startOfDay(now) - 365L * 86_400_000L -> "${thisYear.format(Date(millis))} $clock"
        else -> fullDate.format(Date(millis))
    }
}

/**
 * chat 第二页角色卡片**右上角**那个日期。
 *
 * `今日` / `昨天` / `M月d日` / 跨年 `yyyy年M月d日`。
 *
 * ## 这一版推翻了什么
 *
 * 更早的 chat 卡片右上角是那个已删除的 `lastChatTime()`（同一自然日给 `HH:mm`、
 * 跨日给 `N 天前`，一直数到 91 天）。后来改了需求：
 * **右上角给日期、右下角给钟点**，两个信息各占一角。
 *
 * 判据仍是**自然日**（[dayDiff]），不是满 24 小时 —— 这条老规矩没变。
 *
 * @param now 注入「现在」是为了能断言边界（1 号 23:59 → 2 号 00:00 必须是「昨天」）。
 */
fun lastChatDate(millis: Long, now: Long = System.currentTimeMillis()): String {
    if (millis <= 0L) return ""
    return when (dayDiff(millis, now)) {
        in Long.MIN_VALUE..0L -> "今日"
        1L -> "昨天"
        else -> if (startOfDay(millis) >= startOfDay(now) - 365L * 86_400_000L) {
            thisYear.format(Date(millis))
        } else {
            fullDate.format(Date(millis))
        }
    }
}

/**
 * chat 第二页角色卡片**右下角**那个钟点。恒为 `HH:mm`。
 *
 * 和 [lastChatDate] 是一对，同一条 `persona.lastChatAt` 派生出来。
 * 复用同一个 [sameDay] —— 全应用只此一份 24 小时制定义。
 *
 * 和右上角的日期不同，这里**不做「几天前」的换算**：那件事现在由日期那一角承担
 * （「昨天」/「3月5日」），同一张卡上再写一遍天数没有信息量。
 */
fun lastChatClock(millis: Long): String =
    if (millis <= 0L) "" else sameDay.format(Date(millis))

/**
 * 两个时间点之间**隔了几个自然日**：各自先截断到当天 0 点，再相减整除。
 *
 * 不能直接 `(now - millis) / 86_400_000L` —— 那算的是「满了几个 24 小时」，
 * 会在「1 号 23:59 → 2 号 00:00」这种只差 1 分钟的情况上返回 0（显示成钟点）。
 *
 * 已知取舍：截断后整除在**有夏令时的地区**理论上会差 1 天（那天只有 23 或 25 小时）。
 * 本应用主场景在中国（无夏令时），按这个写；要严格正确得上
 * `java.time.LocalDate` + `ChronoUnit.DAYS`。
 */
private fun dayDiff(millis: Long, now: Long): Long =
    (startOfDay(now) - startOfDay(millis)) / 86_400_000L

/** 把某一刻截断到当天 0 点（本地时区）。 */
private fun startOfDay(millis: Long): Long = Calendar.getInstance().apply {
    timeInMillis = millis
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis

/**
 * 应用里所有「几个选项里选一个」的开关组共用这一套形状。
 *
 * 官方默认的 `TextToggleButtonDefaults.shapes()` 是**所有状态共用一个形状**（整圆胶囊），
 * 于是选中与否只靠颜色区分。而 Wear M3 专门为「选一个」这种开关备了
 * `variantAnimatedShapes()`：未选中是整圆胶囊、**选中拉成 18dp 圆角矩形**，
 * 再叠一层按下时圆角收紧到 66% 的形变。
 *
 * 这就是 M3 Expressive 里「形状本身也在表达状态」那一条。一排在圆屏上只有两三个字的
 * 按钮，光靠色块看不太出哪个是选中的，形状一变就一眼分明 —— 这是设置页最常被读的一处控件。
 */
@Composable
fun selectionToggleShapes(): TextToggleButtonShapes =
    TextToggleButtonDefaults.variantAnimatedShapes()

/**
 * 一行说明文字。设置页各子页共用，故意做成不适合点击的样子。
 *
 * ## ⚠️ 1.986：加了 [inCard] 形参（用户原话：「语音识别最底下文字描述卡片
 * 直接改成只剩文字，**不用放在那个大空卡片里** 很丑」）
 *
 * 默认 `true`（保持原来的静态卡片形态）——**其余调用点一个字都不用改**。
 * 语音识别页那处传 `false`，渲染成**纯文字**（居中、`onSurfaceVariant`，
 * 与卡片版同一套排版 token），不再套那张大空卡。
 */
@Composable
fun HintText(text: String, inCard: Boolean = true) {
    if (!inCard) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            textAlign = TextAlign.Center,
        )
        return
    }
    // 静态卡片：Wear M3 的 Card 没有非点击重载，静态版本 = onClick = {} + enabled = false，
    // 只写 onClick = {} 会带上水波纹，看着像能点。
    Card(
        onClick = {},
        enabled = false,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * M3E 的**波浪形圆形进度指示器**（indeterminate / 不定进度）。
 *
 * ## 这是「移植」，不是「照着照片仿制」
 *
 * 上一版是我按用户给的一张截图反解形状自己写的（余弦波瓣环），
 * 用户的评价是「丑、不还原」，并给了两处参考：
 * `m3.material.io/components/progress-indicators/specs` 和
 * `github.com/JAICHANGPARK/material3_indicators`。
 * ⇒ 本版**逐行移植**那个仓库的 `WavyCircularProgressIndicator`，不再自创形状。
 *
 * 移植来源：`lib/src/wavy_circular_progress_indicator.dart`（MIT）。
 * **公式、默认值、动效时序逐条照搬，一处没改**：
 *
 * | 项 | 参考实现 | 这里 |
 * |---|---|---|
 * | 底轨半径 | `size/2 − (amplitude + max(strokeWidth, trackStrokeWidth)/2)` | 同 |
 * | 波浪半径 | `r(θ) = baseRadius + amplitude·scale·sin(frequency·θ − phase)` | 同 |
 * | 相位 | `phase = animationValue · waveSpeed · 2π` | 同 |
 * | 边缘包络 | `fadeZone = min(π/4, totalArc/3)`，两端 `scale` 从 0 线性升到 1 | 同 |
 * | 频率 | `frequency = 8.0`（一圈 8 个波峰） | 同 |
 * | 其它默认 | `strokeWidth 4 / trackStrokeWidth 2 / amplitude 3 / waveSpeed 4`（对 48 逻辑像素而言） | 同（按直径等比换算） |
 * | 动效周期 | `AnimationController(duration: 2s)..repeat()` | 2.0s |
 * | 不定进度的头尾 | `head = easeInOut(clamp(v·2))`、`tail = easeInOut(clamp((v−0.5)·2))`，再加 `spin = v·2π` | 同 |
 * | 缓动 | `Curves.easeInOut` = cubic-bezier(0.42, 0, 0.58, 1) | `CubicBezierEasing(0.42f, 0f, 0.58f, 1f)` |
 *
 * ⚠️ 一个数字**不是**照搬的：那个库是 Flutter，尺寸单位是逻辑像素；
 * 这里用**直径的比例**（`4/48`、`2/48`、`3/48`）换算，所以任何 `diameter` 下比例都不变。
 *
 * ## 为什么是「自己画」（唯一一处破例，理由写完再动）
 *
 * 项目铁律是「组件只用 `androidx.wear.compose.material3.*`，别自己画」。**这一处必须破例**：
 *  * 这个效果在 **Wear M3 1.5.6 里根本不存在** —— 解包 `classes.jar` + `sources.jar`
 *    逐个核对过，那一版跟进度沾边的只有 `CircularProgressIndicator`（含 indeterminate /
 *    溢出变体）、`LinearProgressIndicator`、`ArcProgressIndicator`、
 *    `SegmentedCircularProgressIndicator`，**一个都没有波浪**；
 *  * 官方实现（`androidx.compose.material3` 1.4+ 的 `MaterialShapes` / wavy 系列）要
 *    **升依赖**，而本项目不许升。
 *
 * ⇒ 只能按参考实现复刻。**这一段是本版唯一的「非官方组件」**，改它之前先读上面那张对照表。
 *
 * ## ⚠️ 驱动方式：不用 `rememberInfiniteTransition`（实测踩出来的）
 *
 * 第一版用 `rememberInfiniteTransition().animateFloat(...)`（教科书写法），
 * 在模拟器上**实测是静止的**：截图逐帧差分 0 像素、`screenrecord` 抽帧只有压缩噪声、
 * `dumpsys gfxinfo` 的 `Total frames rendered` 3 秒里 1470 → 1470（一个帧都没出）。
 *
 * 原因：`InfiniteTransition` 走 `withInfiniteAnimationFrameNanos`，推进靠 **vsync 帧回调**，
 * 而这台模拟器闲置时不给帧；Wear 还会用 `InfiniteAnimationPolicy` 插手无限动画。
 * ⇒ **别把「转不转」押在帧回调上**。这里用 `LaunchedEffect` + `delay(16ms)` 自推时间
 * （16ms 对应参考实现跑在 60fps 上的观感）：`delay` 走主线程 Handler，
 * 每写一次时间就 invalidate 一次，**它自己把帧要出来**。
 *
 * ## 形态说明（别看错）
 *
 * 参考实现的不定进度是「**平的细底轨 + 一条粗的波浪活弧**」，
 * 活弧在一个 2s 周期里从无长到整圈、再缩回无，同时整体还转一圈。
 * 用户给的截图里那一圈「整圈波浪」是同一个组件的 **确定进度 value=1 那一帧**
 * （整圈都是活弧、只有一个 45° 的包络收口）—— 我们这里是不定进度，
 * 所以那一帧只在每个周期的中段出现一下，这是对的，不是没还原。
 *
 * @param diameter 外径。所有粗细/振幅都按它的比例推（见 [WAVY_*] 常量），
 *   所以任何尺寸下相对比例都与参考实现一致。
 * @param compact 小尺寸（列表行里那颗 24dp）用。等比例的振幅在这个尺寸下不足 2px，
 *   会看不出波浪，所以小尺寸**刻意把振幅和线宽的比例放大**（这是唯一一处偏离参考比例的地方）。
 */
@Composable
fun WavyProgressIndicator(
    modifier: Modifier = Modifier,
    diameter: Dp = 48.dp,
    compact: Boolean = false,
) {
    // 自推时间（原因见上面那段 ⚠️）。用 `mutableFloatStateOf` 而不是 `mutableStateOf`：
    // 每 16ms 写一次，装箱/拆箱在长时间运行下是白花的。
    var elapsed by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(WAVY_TICK_MS)
            elapsed += WAVY_TICK_MS / 1000f
        }
    }

    val activeColor = MaterialTheme.colorScheme.primary
    // 参考实现底轨用的是 `surfaceContainerHighest`（手机版 M3 的 token），
    // Wear M3 的 ColorScheme 里**没有**这个名字 —— 用语义最接近的 `surfaceContainerHigh`。
    val trackColor = MaterialTheme.colorScheme.surfaceContainerHigh
    val strokeRatio = if (compact) WAVY_STROKE_RATIO_COMPACT else WAVY_STROKE_RATIO
    val trackRatio = if (compact) WAVY_TRACK_RATIO_COMPACT else WAVY_TRACK_RATIO
    val ampRatio = if (compact) WAVY_AMPLITUDE_RATIO_COMPACT else WAVY_AMPLITUDE_RATIO

    Canvas(modifier = modifier.size(diameter)) {
        // 参考实现：`animationValue` 就是一个 0→1 的循环（AnimationController..repeat()）。
        val av = (elapsed % WAVY_CYCLE_SECONDS) / WAVY_CYCLE_SECONDS

        val box = size.minDimension
        val stroke = box * strokeRatio
        val trackStroke = box * trackRatio
        val amplitude = box * ampRatio
        // 参考实现：底轨半径要把振幅与线宽让出来，否则波峰会被画到框外。
        val baseRadius = box / 2f - (amplitude + max(stroke, trackStroke) / 2f)
        if (baseRadius <= 0f) return@Canvas

        // 1) 平的底轨（参考实现也是先画一个正圆底轨）。
        drawCircle(
            color = trackColor,
            radius = baseRadius,
            center = center,
            style = Stroke(width = trackStroke),
        )

        // 2) 波浪活弧：不定进度 = 头尾各扫一次 + 整体自转。
        val phase = av * WAVY_WAVE_SPEED * TWO_PI
        val spin = av * TWO_PI
        val head = WAVY_EASE_IN_OUT.transform((av * 2f).coerceIn(0f, 1f))
        val tail = WAVY_EASE_IN_OUT.transform(((av - 0.5f) * 2f).coerceIn(0f, 1f))
        val start = -PI_F / 2f + tail * TWO_PI + spin
        val end = -PI_F / 2f + head * TWO_PI + spin
        if (end <= start) return@Canvas

        drawPath(
            path = buildWavyArcPath(center, baseRadius, amplitude, start, end, phase),
            color = activeColor,
            style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

/**
 * 参考实现里的 `_buildWavyArcPath` + `_calculateRadius`，逐行对应。
 *
 * 两个关键点：
 *  1. 采样步长固定 **2°**（`π/90`），跟着弧长走 —— 弧越长点越多，短弧不会被画成折线；
 *  2. **边缘包络线**：`fadeZone = min(π/4, 总弧长/3)`，两端 `scale` 从 0 线性升到 1，
 *     让波浪在弧的头尾**收到 0 振幅**，于是接头处平滑并进底轨、不会突然「折断」。
 *     这就是那个库 README 里说的「边缘包络线（Edge Envelope）」。
 */
private fun buildWavyArcPath(
    center: Offset,
    baseRadius: Float,
    amplitude: Float,
    startAngle: Float,
    endAngle: Float,
    phase: Float,
): Path {
    val path = Path()
    val totalArc = endAngle - startAngle
    if (totalArc <= 0f) return path

    val fadeZone = min(PI_F / 4f, totalArc / 3f)
    val steps = max(8, (totalArc / (PI_F / 90f)).toInt())

    for (i in 0..steps) {
        val theta = startAngle + totalArc * i / steps
        val scale = when {
            theta - startAngle < fadeZone -> (theta - startAngle) / fadeZone
            endAngle - theta < fadeZone -> (endAngle - theta) / fadeZone
            else -> 1f
        }
        val r = baseRadius + amplitude * scale * sin(WAVY_FREQUENCY * theta - phase)
        val x = center.x + r * cos(theta)
        val y = center.y + r * sin(theta)
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    return path
}

/**
 * 一圈里的波峰数。**参考实现的默认值就是 8**，而且用户那张截图反解出来也正好是 8 个波瓣
 * （FFT 的 8 次分量幅值 1621，7/9 次只有 60 上下）—— 两边独立对上了，别再改这个数。
 */
private const val WAVY_FREQUENCY = 8f

/** 波浪沿圆周传播的速度（弧度倍数）。参考实现默认 4.0。 */
private const val WAVY_WAVE_SPEED = 4f

/** 一个完整循环的时长，秒。参考实现是 `AnimationController(duration: 2s)..repeat()`。 */
private const val WAVY_CYCLE_SECONDS = 2.0f

/** 自推时间的步长，毫秒。16ms ≈ 60fps，对应参考实现跑在 Flutter 上的观感。 */
private const val WAVY_TICK_MS = 16L

/**
 * 粗细则按**直径的比例**给（参考实现的 4/2/3 是「对 48 逻辑像素」而言的）：
 * `strokeWidth 4/48`、`trackStrokeWidth 2/48`、`amplitude 3/48`。
 */
private const val WAVY_STROKE_RATIO = 4f / 48f
private const val WAVY_TRACK_RATIO = 2f / 48f
private const val WAVY_AMPLITUDE_RATIO = 3f / 48f

/**
 * 小尺寸档（24dp）：**唯一一处刻意偏离参考比例的地方**。
 * 等比例下振幅只有 24×3/48 = 1.5dp（≈3px），在这个尺寸上根本看不出波浪；
 * 这里把振幅提到 9%、线宽提到 11%，小环上才还看得出「在波动」。
 */
private const val WAVY_STROKE_RATIO_COMPACT = 0.11f
private const val WAVY_TRACK_RATIO_COMPACT = 0.055f
private const val WAVY_AMPLITUDE_RATIO_COMPACT = 0.09f

/** 参考实现的 `Curves.easeInOut`，数值就是 cubic-bezier(0.42, 0, 0.58, 1)。 */
private val WAVY_EASE_IN_OUT = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)

private const val PI_F = Math.PI.toFloat()
private const val TWO_PI = 2f * PI_F

/**
 * 屏幕上沿那行「说完就散」的短暂提示：复制成功（`已复制`）、模式切换（`Ask` / `Chat`）。
 *
 * ## ⚠️ 为什么要有这个组件（2.2 用户原话）
 *
 * 「复制文本后的『已复制』提示显示和**切换模式的提示文本显示效果统一**」。
 *
 * 2.1 之前这是**两份各写各的实现**：
 *  * `ChatScreen` 里 `if (copied) Text(...)` —— `labelSmall` + `onSurfaceVariant` × 0.85 +
 *    `top = 30.dp`、**没有动画**、停 1.4s；
 *  * `HomeScreen` 里那个私有闪字组件（2.2 已删除）—— `labelMedium` + `onSurfaceVariant` 不透明 +
 *    `top = 17.5.dp`、`fadeIn/fadeOut` + `defaultEffectsSpec`、停 2s。
 *
 * 两处各调一遍「看着差不多」不算统一：字号/颜色/位置/动画/时长任何一处漂移都会重新分叉。
 * 所以**收成一份**，两处都只准调这个组件。
 *
 * ## 规格（为什么是这几个值）
 *
 * | 维度 | 值 | 依据 |
 * |---|---|---|
 * | 字号 | `labelMedium` | 取两处里较大的那个；`labelSmall`(13sp) 在 240dp 圆屏上偏小，`labelLarge`(15sp→墨迹 30px) 会顶到时间胶囊（技能册 06 分册 §76④ 量过） |
 * | 颜色 | `onSurfaceVariant` **不加 alpha** | 与模式提示原有取值一致；带 alpha 会让两处深浅不同（用户要的就是一致）。⚠️ 技能册 §76④b 记过 `onSurfaceVariant` 在莫奈取色下偏亮带紫，另一种中性灰写法是 `onSurface × 0.55` —— 本轮按提示词规格选了前者，观感若再被否是**一个常量**的事 |
 * | 位置 | [TransientLabelTop]（相对屏幕顶部） | 原模式提示的 17.5dp，装机调过的值；复制提示从 30dp 并过来 |
 * | 动画 | `fadeIn/fadeOut` + `motionScheme.defaultEffectsSpec` | 淡入淡出属于「透明度/颜色」类，正是这条 spec 管的；不许手写 `tween` |
 * | 停留时长 | [TransientLabelHoldMs]（**调用方**计时） | 两处共用一个常量，别各写一个 |
 *
 * ## 无状态（计时留在调用方）
 *
 * 组件只负责「长什么样 + 怎么淡入淡出」，`visible` 与停留时长由调用方管：
 *  * `HomeScreen` 的计时挂在 `pagerState.isScrollInProgress` 上（滑动中显示、停手后留 N 秒）；
 *  * `ChatScreen` 的计时挂在 `copied` 上（复制成功 → 显示 → N 秒后置 false）。
 * 计时放进组件会让两处的触发语义糊在一起（一个是手势、一个是动作），也不便于复用。
 *
 * ⚠️ 用 `AnimatedVisibility` 而不是 `if (visible)`：`if` 会让「出现/消失」是硬切，
 * 而 M3 的转场要求走 motionScheme —— 这也是两处原先唯一的差别，统一后必须两边都动。
 */
@Composable
fun TransientLabel(
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
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * [TransientLabel] 距屏幕上沿的距离（dp）。
 *
 * 17.5dp 是 `HomeScreen` 模式提示原来那个落点：@340dpi ≈ 37px，
 * 时间胶囊墨迹底约 36px，所以文字上沿刚好在胶囊下方、下沿约 37+26=63px。
 * ⚠️ 装机实测过「不压时间胶囊」；但**对话页没有时间胶囊**（`ChatScreen` 的
 * `ScreenScaffold` 没传 `timeText`），那边这行字会浮在正文上方 —— 这是覆盖层，
 * 不遮滚动、不位移，见报告里的截图。
 */
val TransientLabelTop: Dp = 17.5.dp

/**
 * [TransientLabel] 的停留时长（毫秒）—— **两处共用这一个数**。
 *
 * 取模式提示原来的 2 秒（复制提示原来是 1.4 秒）：这是「说完就散」的长短，
 * 2 秒足够读完「已复制」三个字又不会赖在屏幕上。改就只改这里。
 */
const val TransientLabelHoldMs: Long = 2_000L

/**
 * 长按唤出菜单时的触感反馈。
 *
 * 为什么要有它（2.2 §1.5 第 3 条）：Wear 上长按**没有触感反馈**时，用户按 400ms 不知道
 * 到底触发没触发，手指一抬菜单没出来就会以为「这功能没做」。Compose 的 `combinedClickable`
 * 只负责手势判定，**不会**替你震一下。
 *
 * ⚠️ 用平台的 `HapticFeedbackType.LongPress`（力度/时长由系统定），别自己造震动 ——
 * 手表马达很轻，自选参数只会更难感觉。
 * ⚠️ 返回的是**一个 lambda**而不是 `Modifier`：长按入口是 `Card(onLongClick = …)`，
 * 没有 modifier 可挂。调用点必须在 Composable 作用域里（要读 `LocalHapticFeedback`）。
 */
@Composable
fun rememberLongPressHaptic(action: () -> Unit): () -> Unit {
    val haptic = LocalHapticFeedback.current
    return remember(haptic, action) {
        {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            action()
        }
    }
}

/**
 * 菜单按钮按住时的轻微缩放（M3 Expressive 的「按压反馈」表达之一）。
 *
 * M3E 对按压的表达有两条路：形状变形（胶囊 → 圆角矩形）和**容器缩放**。
 * Wear M3 1.5.6 的 `Button`/`FilledTonalButton` 都没有原生形状动画
 * （`IconButton`/`ToggleButton` 族才有 `animatedShapes()`，见技能册 §71），
 * 所以这里用 graphicsLayer 缩放补上；曲线走
 * `MaterialTheme.motionScheme`（expressive，主题层已全局启用），不手写 tween。
 *
 * 缩放比 0.97：肉眼可感的「按进去了」，又不会让整宽按钮在圆屏边缘撞上别的元素。
 * 返回时弹回用 defaultSpatialSpec（慢弹簧）。
 */
@Composable
private fun Modifier.menuButtonPressScale(
    interactionSource: InteractionSource,
): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = if (pressed) {
            MaterialTheme.motionScheme.fastSpatialSpec<Float>()
        } else {
            MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
        },
        label = "menuButtonPressScale",
    )
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/**
 * 菜单弹窗里的一项。
 *
 * [destructive] 用 error 色，跟普通项拉开距离 —— 「删角色」和上面那几项并排放在一起，
 * 光靠文字分不出轻重，颜色是最省事也最有效的那层区分。
 *
 * [icon] 可选。每项前面带一个图标（`AppIcons` 里已有的），没有合适图标的项传 null，
 * 宁可不加不硬凑。
 */
data class DialogAction(
    val label: String,
    val destructive: Boolean = false,
    /** 可选图标。列表见 [AppIcons]；没有贴切的就传 null（默认），别硬凑。 */
    val icon: ImageVector? = null,
    val onClick: () -> Unit,
)

/**
 * 一个标题 + 若干项动作的菜单弹窗 —— **按官方样例原样实现**（用户 2026-11-01 拍板）。
 *
 * ## 官方依据（不是凭感觉，都是与工程同版本的源码）
 *
 * 1. **Wear M3 1.5.6 没有菜单组件**：`classes.jar` 里与弹层有关的顶层类只有
 *    `AlertDialog` / `AlertDialogDefaults` / `ConfirmationDialog` / `OpenOnPhoneDialog` / `Dialog`。
 * 2. 官方的「动作列表」写法见 `AlertDialogWithContentGroupsSample`（samples 源码）：
 *
 *    ```kotlin
 *    AlertDialog(title = { Text("Share your location") }, edgeButton = { … }) {
 *        item { FilledTonalButton(modifier = Modifier.fillMaxWidth(), label = { Text("Never share") }) }
 *        item { FilledTonalButton(modifier = Modifier.fillMaxWidth(), label = { Text("Share always") }) }
 *    }
 *    ```
 *
 *    即：**一项一个 `item`、整宽 `FilledTonalButton`、不自定宽度**。
 * 3. `AlertDialog` 内部是 `ScalingLazyColumn(scalingParams = scalingParams(minTransitionArea = 0.2f))`，
 *    内容超出视口时**滚动**，靠边的项按官方曲线缩小；底部留白 `noEdgeButtonBottomPaddingFraction
 *    = 0.3646f`（屏高 36.46%，右滑关闭的手势区）。
 *    ⇒ 「3 项一屏、不滚动、且完全等宽」在官方体系里**没有这种形态**（这也是 2.2 那版
 *    自定义缩放/收窄的由来）。
 *
 * ## 保留的增强（2.1.1 已过装机验收的三样，都写在代码里）
 *
 *  * 破坏性项：灰调容器 + **error 色图标与文字**（官方 ConfirmationDialog failure 色的语义：
 *    ErrorDim 图标 + 弱化文字），**不整块红**；
 *  * 每项独立 `interactionSource` + 按压缩放 0.97（M3E 按压反馈，曲线走 motionScheme）；
 *  * 每项带 26dp 图标（`ButtonDefaults.IconSize`）。
 *
 * 退出：右滑关闭 + 返回键（`AlertDialog` 窗口自带回调，另有页面层 `BackHandler` 兜底）；
 * 点菜单项立刻关闭，不会挡住紧随其后的确认框。
 */
@Composable
fun WhaleChatMenuDialog(
    title: String,
    actions: List<DialogAction>,
    onDismiss: () -> Unit,
) {
    BackHandler { onDismiss() }
    AlertDialog(
        visible = true,
        onDismissRequest = onDismiss,
        // 官方示例：title 用库默认样式（titleMedium / 居中 / 3 行 Ellipsis）。
        title = { Text(text = title) },
        // 官方留白：上 10% 屏高、左右 5.2%、下 36.46%（右滑关闭手势区）。
        contentPadding = AlertDialogDefaults.contentPadding(),
    ) {
        actions.forEach { action ->
            item {
                val icon = action.icon
                val label: @Composable RowScope.() -> Unit = {
                    Text(
                        text = action.label,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                val buttonIcon: (@Composable BoxScope.() -> Unit)? = if (icon != null) {
                    {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            modifier = Modifier.size(ButtonDefaults.IconSize),
                        )
                    }
                } else {
                    null
                }
                // 每项一颗独立 interactionSource：按压缩放靠它听按压状态。
                val pressSource = remember { MutableInteractionSource() }
                val buttonModifier = Modifier
                    .fillMaxWidth()
                    .menuButtonPressScale(pressSource)
                if (action.destructive) {
                    Button(
                        onClick = action.onClick,
                        modifier = buttonModifier,
                        interactionSource = pressSource,
                        colors = ButtonDefaults.filledTonalButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                            iconColor = MaterialTheme.colorScheme.error,
                        ),
                        icon = buttonIcon,
                        label = label,
                    )
                } else {
                    FilledTonalButton(
                        onClick = action.onClick,
                        modifier = buttonModifier,
                        interactionSource = pressSource,
                        icon = buttonIcon,
                        label = label,
                    )
                }
            }
        }
    }
}

/** Key 在界面上永远只露头尾，手表上被人瞟一眼也拿不到完整串。 */
fun maskKey(key: String): String =
    if (key.length <= 8) "已保存" else "${key.take(5)}····${key.takeLast(4)}"

/**
 * token 数格式化：`1.2k` / `12.4k` / `1.2M`。
 *
 * 手表副标题就那么宽，`12345678` 这种裸数字会把卡片撑坏。规则：
 *  * 不到 1000 直接给整数（`864`）；
 *  * 1000 到 100 万给 `x.xk`（保留一位小数，去掉多余的 0）；
 *  * 百万以上给 `x.xM`。
 *
 * 「小于 1」这件事这里**不做兜底**：`tokens = 0` 会原样返回 `"0"`，不做语义判断。
 * 是否该显示由调用方决定 —— 两个调用方（对话页那行、角色卡片副标题）都先判了 `> 0`，
 * 没拿到用量时整块不出现，而不是显示一个「0」（见 PersonaRow）。
 */
fun formatTokens(tokens: Long): String {
    if (tokens < 1_000L) return tokens.toString()
    if (tokens < 1_000_000L) return trimZero(tokens / 1_000.0) + "k"
    return trimZero(tokens / 1_000_000.0) + "M"
}

/** 把 `1.0` 这种小数末位的 0 去掉，只保留一位有效小数。 */
private fun trimZero(value: Double): String {
    val text = String.format(Locale.US, "%.1f", value)
    return if (text.endsWith(".0")) text.dropLast(2) else text
}

/**
 * 应用里所有「要不要」的确认框都走这一个。
 *
 * ## 为什么不用 `ConfirmationDialog`
 *
 * 那个控件是给「操作成功了 / 失败了」这类一次性反馈设计的 —— 它把确认动作渲染成一个
 * 旋转 45° 的**圆形图标按钮**，再压在一块圆角方形容器上。在圆屏上就是一个红圆叠一个
 * 方片，跟应用里其余卡片的形状语言完全脱节（用户的评价是「好丑」），而且它只有图标、
 * 没有文字余地，「删除」这种要说清楚后果的场景根本放不下。
 *
 * ## 1.978：改成 M3E 的**底部边按钮**布局（用户对旧版的原话：「太丑了」）
 *
 * 旧版用的是 `AlertDialog` 的「confirmButton + dismissButton」两个槽：确认是右下角一颗
 * 小胶囊、取消是左下角的**纯文字**。两颗按钮都缩在底部一角，既不像按钮，也没能表达
 * 「这一步是这一页最重要的动作」。
 *
 * 现在换成 Wear M3 1.5.6 的 **`edgeButton` 重载** —— 这是这一版给对话框准备的
 * M3 Expressive 形态：
 *
 *  * 主要动作是 `AlertDialogDefaults.EdgeButton`：**整条占满屏宽、贴住对话框底部**，
 *    形状跟着圆屏下沿走。危险动作用 `errorContainer` 上色（见 [destructive]）。
 *  * 次要动作（取消）放进 `content` 槽，做成一颗**整宽**的 `FilledTonalButton`。
 *    这也是官方 `AlertDialogWithContentGroupsSample` 的写法 ——
 *    对话框里那列东西本来就该是整宽的按钮，不是缩在角上的一行字。
 *  * 退出仍然是三条路都通：点那颗取消、点对话框外面（scrim）、按返回键
 *    （`AlertDialog` 内部把 Back 也接到 `onDismissRequest`）。
 *
 * ⚠️ **不要退回「自绘两颗粒子按钮」那版**：那不是 M3 的对话框形态，
 * 也是这次被点名要改掉的东西。
 *
 * @param quote 需要给用户看原文的场合（比如「你要改的是这一句」），作为强调行排在
 *   解释文字上方。传 null 就没有这一行。
 * @param destructive 危险动作（删除之类）用 error 色，跟普通确认拉开距离。
 */
@Composable
fun WhaleChatDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
    quote: String? = null,
) {
    val quoted = quote
    AlertDialog(
        visible = true,
        onDismissRequest = onDismiss,
        // 标题离顶部的时间胶囊要留出空间,但官方默认上留白(10% 屏高)+ 标题前的
        // 固定间隔,在「标题+两行正文+两颗按钮」这种短内容场景里把整块内容压得偏下
        // (实测标题顶 y≈59,正文与取消之间 gap 42px,而取消与删除 EdgeButton 之间
        // 只有 8px —— 节奏不均)。这里收窄上留白(48px→28px),让标题往上走,
        // 正文→取消的间距由内容高度自然回归;底部间距由 ScreenScaffold 的
        // edgeButtonSpacing(4dp)管理,不动。
        // start/end 保持官方 5.2%(240dp 屏 = 12dp;本应用不做 RTL,直接写死)。
        // ⚠️ bottom 在 EdgeButton 场景下被库忽略(ScreenScaffold 源码原文:
        // 「The bottom padding value is always ignored because we instead use
        // edgeButtonSpacing」),写 24dp 只是占位 —— 取消→删除的实际间距由
        // 取消 item 的 padding(bottom = 12.dp) 提供(见下面 content 槽)。
        contentPadding = PaddingValues(start = 12.dp, top = 28.dp, end = 12.dp, bottom = 24.dp),
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        // 原文和解释挤在同一个 text 槽里，而不是给原文单独套一张卡片。
        //
        // 这是量出来的：卡片自带的内容内边距（上下各 16dp）光为了一行字就要吃掉 90px，
        // 整段内容因此溢出到圆屏下沿。合成一段之后，原文靠字号和颜色跟解释拉开层次，
        // 省下的全是底部那颗边按钮的空间。
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (quoted != null) {
                    Text(
                        text = quoted,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        edgeButton = {
            AlertDialogDefaults.EdgeButton(
                onClick = onConfirm,
                colors = if (destructive) {
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    )
                } else {
                    ButtonDefaults.buttonColors()
                },
            ) {
                // EdgeButton 内部那个 Row 已经是 `Arrangement.Center` + `TextAlign.Center`，
                // 所以这里**不要再加 `fillMaxWidth()`** —— 它会把内容顶成整行宽，
                // 反而让 `TextAlign.Center` 失去可居中的余量（旧版那颗胶囊踩过这个坑）。
                Text(text = confirmLabel)
            }
        },
    ) {
        item {
            // 取消是**次要动作**：整宽的 tonal 按钮 —— 官方
            // `AlertDialogWithContentGroupsSample` 的形态:EdgeButton(主操作贴底)
            // 场景里,content 槽的按钮就是**整宽**的(样例里的 "Never share" /
            // "Share always" 两颗都是 fillMaxWidth,和 EdgeButton 并存)。
            //
            // ## 第八轮返工(收窄取消)作废
            //
            // 曾把取消收窄到 240px 想「与 EdgeButton 弧形带对齐」,装机效果是
            // 屏幕中部悬一颗小胶囊、下面贴一颗全宽弧形按钮 —— 两个形状语言
            // 完全不同的元素上下排,比不收窄更难看(用户原话:「你告诉我这对齐了?」)。
            // 官方样例里两者并存的正确关系就是「取消整宽 + 删除贴底全宽」:
            // content 按钮受 5.2% 边距约束、EdgeButton 是全屏组件,宽度差是
            // 官方形态自身的层次表达(次要 < 主要),不需要人为补齐。
            //
            // 与删除的间距:EdgeButton 贴底由 edgeButtonSpacing(4dp)管理,
            // 给取消的 item 加 12dp 底距,总间隙 ~34px,与正文→取消的间距呼应。
            FilledTonalButton(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                onClick = onDismiss,
                label = {
                    Text(
                        text = "取消",
                        maxLines = 1,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                },
            )
        }
    }
}

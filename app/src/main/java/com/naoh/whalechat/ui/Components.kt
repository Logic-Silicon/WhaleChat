package com.naoh.whalechat.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
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
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffoldDefaults
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TextToggleButtonDefaults
import androidx.wear.compose.material3.TextToggleButtonShapes
import kotlinx.coroutines.delay
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
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        contentPadding = contentPadding,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            contentColor = MaterialTheme.colorScheme.onSurface,
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
                    Text(text = title, style = MaterialTheme.typography.titleMedium)
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
 * 菜单弹窗里的一项。
 *
 * [destructive] 用 error 色，跟普通项拉开距离 —— 「删角色」和上面那几项并排放在一起，
 * 光靠文字分不出轻重，颜色是最省事也最有效的那层区分。
 */
data class DialogAction(
    val label: String,
    val destructive: Boolean = false,
    val onClick: () -> Unit,
)

/**
 * 一个标题 + 若干项动作的菜单弹窗。
 *
 * 用 `AlertDialog` 的 **`content: ScalingLazyListScope` 重载**（而不是
 * confirmButton/dismissButton 那套两按钮版本）：长按一个角色要选的是三件事，
 * 塞进「确认 / 取消」两个槽里就得自己写一堆 Column，而 content 重载本来
 * 就是为「对话框里放一列东西」准备的。
 *
 * ## 为什么这里跟 [WhaleChatDialog] 长得不一样（按钮不撑满、三项挤在同一个 item 里）
 *
 * 这块屏只有 240dp 见方（半径 240px），而 Wear M3 的 `Button` 布局高度**恒为 48dp**
 * —— 想压矮没用，`minimumInteractiveComponentSize` 会把布局重新撑回 48dp
 * （`ButtonDefaults.CompactButtonHeight` 也是 48dp，那 32dp 只是可见高度）。
 * 所以「三行按钮 + 一行标题」总共要 ~430px，而 480px 的屏幕里真正能放东西的
 * 只有圆心附近那一块：一个宽 w 的元素，整条都落在圆内的纵向区间是
 * `y ∈ [240 ± sqrt(240² - (w/2)²)]`，越宽越窄。
 *
 * 两个后果，都是量出来的：
 *
 * 1. **所有项塞进同一个 `item {}` 里，自己用 Column 排行距。** 每一项单独做 item
 *    时，对话框内部的 ScalingLazyColumn 会按自己的档位加行距（实测每项占 120px，
 *    比 48dp 的按钮高出 24px）；合成一项之后行距由下面这个 4dp 说了算，
 *    省下 ~48px。这是让「删角色」不被切掉的关键那一下。
 * 2. **按钮宽度收在 118dp（不是 `fillMaxWidth`）。** 撑满 428px 时安全纵向区间
 *    只剩 [84,396]，第三行「删角色」必然掉出去、胶囊底被圆边切平（截图确认过）；
 *    收到 236px 后区间扩到 [31,449]，三行就都完整了。118dp 也是标签量出来的：
 *    「重新开始」四个字配 Wear 的按钮内边距大约 92dp，比它宽一点，三个按钮
 *    宽度自然统一。
 *
 * 3. **名字不写进 `title` 槽，而是当内容区第一行的小字。** `title` 槽是必填的
 *    （Wear 四个重载都要它），但传空 lambda 高度就是 0（`Title()` 只负责套一层
 *    横向 padding，还会额外插一个 `AlertContentTopSpacing`）。实测把名字写在
 *    title 槽里那一行占 72px，挪进内容区当 labelMedium 只要 ~35px，省下的 40px
 *    正好是「删角色」能不能整条落进圆内所需要的那一段。
 *
 * ## 为什么不放「取消」
 *
 * 不是漏了。加回来后就是**四项**（三件事 + 取消）。四项放不下 —— 「取消」整个落在
 * 屏幕外，长按之后根本看不见 —— 那才是真的把人堵住。去掉它剩三项，三项都能完整看见。
 *
 * 退出走两条 Wear 惯用路径，都通：
 * 1. 点对话框外面（`AlertDialog` 的 scrim 本来就接 `onDismissRequest`）；
 * 2. 按手表返回键（`AlertDialog` 内部把 Back 也接到 `onDismissRequest`）。
 *
 * 所以「取消」在这里是纯冗余，占的却是最稀缺的那一行的位置。
 */
@Composable
fun WhaleChatMenuDialog(
    title: String,
    actions: List<DialogAction>,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        visible = true,
        onDismissRequest = onDismiss,
        // 名字放回 title 槽，但字号压到 labelMedium。
        //
        // 试过把它挪进内容区当第一行小字，结果反而更差（首个按钮从 128px 掉到
        // 142px）：`alertDialogCommonContent` 在标题和内容之间**永远**会插一个
        // `AlertContentTopSpacing`，所以「标题槽里的一行字」比「内容区里的一行字 +
        // 那个固定间隔」更省。别绕这一圈。
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        // 上下留白钉死。默认那套是「整屏组件」的量法：上 10% 屏高（24dp）、
        // 下 **36.46%**（87dp）—— 那个 36% 是留给「底部还有个 EdgeButton」的形态的，
        // 我们这种「一列按钮、没有确认/取消」用不着。内容在这个对话框里是**顶对齐**
        // 的（实测：把下留白从 87dp 改成 0 只挪动了 2px），所以真正有用的是上留白，
        // 它必须 ≥24dp 才能避开屏幕顶端的系统时间胶囊 —— 保持默认值不动。
        contentPadding = PaddingValues(start = 12.dp, top = 24.dp, end = 12.dp, bottom = 0.dp),
    ) {
        item {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                // **4dp。** 每个 Wear 按钮钉了 `.height(48.dp)` 之后，布局高**实测 96px**
                // （density 2.0 ⇒ 48dp；不钉 height 的默认是 111px，见下面那段）。
                // 三颗 = 288px，加两段 4dp（8px）行距 = 304px。对话框内容顶对齐、
                // 上留白 24dp（48px），三行落在 y≈124–428px，整段都在圆内
                // （宽 236px 时安全纵向区间 [31,449]）⇒ 「删角色」上下圆角都完整、底边不被切平
                // （截图确认）。4dp 比原来的 0dp 肉眼能分出间隙，又不会把第三行顶出圆外。
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                actions.forEach { action ->
                    Button(
                        onClick = action.onClick,
                        // Wear M3 的 `ButtonDefaults.shape` 是绝对半径 26dp，48dp 高的
                        // 小按钮会被画成歪鸭蛋（和 [WhaleChatDialog] 里那段同一个坑）。
                        shape = RoundedCornerShape(percent = 50),
                        // **显式钉 48dp。** 不写这一行的话，按钮默认占 111px（55.5dp），
                        // 三颗就多出 45px —— 而圆屏只肯给我们 414px 的纵向额度。
                        // 顶部那排胶囊（[TopActionButtons]）也是这么钉的，同一套做法。
                        modifier = Modifier
                            .height(48.dp)
                            .widthIn(min = 118.dp, max = 128.dp),
                        colors = if (action.destructive) {
                            ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        } else {
                            ButtonDefaults.buttonColors()
                        },
                    ) {
                        Text(
                            text = action.label,
                            maxLines = 1,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
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
            // 取消是**次要动作**：整宽的 tonal 按钮。见类注释里为什么不用 `TextButton`。
            FilledTonalButton(
                modifier = Modifier.fillMaxWidth(),
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
